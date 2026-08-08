package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Capture-thread-owned runtime with immutable, concurrently queryable completed history.
 *
 * <p>The runtime creates no threads. All provider evaluation occurs on the configured capture
 * thread. Query methods synchronize only around the retained frame deque.
 */
public final class AgentRuntime implements AutoCloseable {
    private static final DecisionScope DISABLED_DECISION = new DisabledDecisionScope();

    private final SessionId sessionId;
    private final RuntimeConfiguration configuration;
    private final RuntimeLimits limits;
    private final ApplicationDiagnostics diagnostics;
    private final MonotonicClock monotonicClock;
    private final Clock wallClock;
    private final Thread captureThread;
    private final Optional<CommandDispatch> commands;
    private final ScenarioRegistry scenarios;
    private final ActionRegistry actions;
    private final AssertionEvaluator assertions;
    private final SimulationControlRegistry controls;
    private final InputRegistry inputs;
    private final CheckpointRegistry checkpoints;
    private final UiCorrelationRegistry uiCorrelations;
    private final RecordingRegistry recordings;
    private final DeterminismRegistry determinism;
    private final EntityRegistry entities = new EntityRegistry(this);
    private final LinkedHashMap<EntityId, InspectableEntity> staticEntities = new LinkedHashMap<>();
    private final LinkedHashMap<String, Supplier<? extends Stream<InspectableEntity>>> sources =
            new LinkedHashMap<>();
    private final Object historyLock = new Object();
    private final ArrayDeque<FrameSnapshot> history = new ArrayDeque<>();
    private final Map<ChangeKey, ChangeCause> pendingCauses = new HashMap<>();
    private final List<RuntimeEvent> pendingEvents = new ArrayList<>();
    private final List<MutableDecision> pendingDecisions = new ArrayList<>();

    private volatile RuntimeStatus status = RuntimeStatus.CREATED;
    private FrameSnapshot previousFrame;
    private FrameId activeFrame;
    private volatile ExecutionEpochId currentEpoch = new ExecutionEpochId(0);
    private Optional<BaselineKind> activeBaseline = Optional.empty();
    private long activeDeltaNanos;
    private long nextFrame;
    private long nextEvent;
    private long nextDecision;
    private MutableDecision openDecision;
    private boolean callbackFailed;

    private AgentRuntime(Builder builder) {
        sessionId = builder.sessionId;
        configuration = builder.configuration;
        limits = configuration.limits();
        diagnostics = new ApplicationDiagnostics(
                builder.applicationFailureSanitizer, sessionId.value());
        monotonicClock = builder.monotonicClock;
        wallClock = builder.wallClock;
        captureThread = builder.captureThread;
        commands = Optional.ofNullable(builder.commandDispatcher)
                .filter(ignored -> configuration.enabled()).map(dispatcher ->
                new CommandDispatch(dispatcher, builder.commandDispatchLimits,
                        monotonicClock, captureThread, diagnostics));
        scenarios = new ScenarioRegistry(this, builder.scenarioLimits);
        actions = new ActionRegistry(this, builder.actionLimits);
        assertions = new AssertionEvaluator(this);
        controls = new SimulationControlRegistry(this, builder.controlLimits);
        inputs = new InputRegistry(this, builder.inputLimits);
        checkpoints = new CheckpointRegistry(this, builder.checkpointLimits);
        uiCorrelations = new UiCorrelationRegistry(this, builder.uiCorrelationLimits);
        recordings = new RecordingRegistry(this, builder.recordingLimits);
        determinism = new DeterminismRegistry(this, builder.determinismLimits);
    }

    /** Creates a runtime builder owned by the calling thread by default. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns this runtime's stable session ID. */
    public SessionId sessionId() {
        return sessionId;
    }

    /**
     * Returns the current observable lifecycle state.
     *
     * <p>Safe for concurrent readers without a monitor; the backing field is volatile and
     * transitions remain capture-thread-owned and monotonic.
     */
    public RuntimeStatus status() {
        return status;
    }

    /** Returns the immutable configuration. */
    public RuntimeConfiguration configuration() {
        return configuration;
    }

    /** Returns the explicit registration surface. */
    public EntityRegistry entities() {
        return entities;
    }

    /** Returns command dispatch only when the application explicitly configured it. */
    public Optional<CommandDispatch> commands() {
        return commands;
    }

    /** Returns the explicit registry for application-owned resettable scenarios. */
    public ScenarioRegistry scenarios() {
        return scenarios;
    }

    /** Returns the explicit registry for bounded typed semantic actions. */
    public ActionRegistry actions() {
        return actions;
    }

    /** Returns the read-only declarative assertion evaluator. */
    public AssertionEvaluator assertions() {
        return assertions;
    }

    /** Returns the optional application-owned simulation control registration surface. */
    public SimulationControlRegistry controls() {
        return controls;
    }

    /** Returns the explicit bounded registry for controlled-tick input facts. */
    public InputRegistry inputs() {
        return inputs;
    }

    /** Returns the optional application-owned opaque checkpoint registry. */
    public CheckpointRegistry checkpoints() {
        return checkpoints;
    }

    /** Returns explicit bounded bidirectional runtime/UI correlation evidence. */
    public UiCorrelationRegistry uiCorrelations() {
        return uiCorrelations;
    }

    /** Returns bounded versioned input and execution recording evidence. */
    public RecordingRegistry recordings() {
        return recordings;
    }

    /** Returns bounded repeated-scenario determinism comparison operations. */
    public DeterminismRegistry determinism() {
        return determinism;
    }

    /**
     * Starts capture and records frame zero as a baseline.
     *
     * <p>Disabled runtimes retain no providers or frames.
     */
    public void start() {
        requireCaptureThread();
        if (status != RuntimeStatus.CREATED) {
            throw lifecycle("start requires CREATED status");
        }
        if (!configuration.enabled()) {
            staticEntities.clear();
            sources.clear();
            status = RuntimeStatus.DISABLED;
            return;
        }
        status = RuntimeStatus.RUNNING;
        activeFrame = new FrameId(0);
        activeBaseline = Optional.of(BaselineKind.INITIAL);
        activeDeltaNanos = 0;
        endFrameInternal();
        nextFrame = 1;
    }

    /** Opens the next capture frame. */
    public void beginFrame(long deltaNanos) {
        if (!configuration.enabled()) {
            return;
        }
        requireCaptureThread();
        requireRunning();
        if (deltaNanos < 0) {
            throw new IllegalArgumentException("deltaNanos must be non-negative");
        }
        if (activeFrame != null) {
            throw lifecycle("a frame is already open");
        }
        activeFrame = new FrameId(nextFrame++);
        activeBaseline = Optional.empty();
        activeDeltaNanos = deltaNanos;
        callbackFailed = false;
    }

    /** Completes the open frame and publishes its immutable snapshot. */
    public void endFrame() {
        if (!configuration.enabled()) {
            return;
        }
        requireCaptureThread();
        requireRunning();
        if (activeFrame == null) {
            throw lifecycle("endFrame requires an open frame");
        }
        endFrameInternal();
    }

    /** Captures a discontinuity baseline as the first frame of a new execution epoch. */
    public FrameId startEpoch(BaselineKind baselineKind) {
        requireCaptureThread();
        requireRunning();
        Objects.requireNonNull(baselineKind, "baselineKind");
        if (baselineKind == BaselineKind.INITIAL) {
            throw new IllegalArgumentException("INITIAL is reserved for frame zero");
        }
        if (activeFrame != null) {
            throw lifecycle("an epoch cannot start while a frame is open");
        }
        currentEpoch = new ExecutionEpochId(Math.addExact(currentEpoch.value(), 1));
        activeFrame = new FrameId(nextFrame++);
        activeDeltaNanos = 0;
        activeBaseline = Optional.of(baselineKind);
        FrameId baselineFrame = activeFrame;
        endFrameInternal();
        return baselineFrame;
    }

    /** Returns the current execution epoch. */
    public synchronized ExecutionEpochId currentEpoch() {
        return currentEpoch;
    }

    /** Returns bounded retained frame summaries for one execution epoch. */
    public EpochFramePage frames(ExecutionEpochId epochId, int limit) {
        Objects.requireNonNull(epochId, "epochId");
        validateQueryLimit(limit);
        synchronized (historyLock) {
            List<FrameSnapshot> matching = history.stream()
                    .filter(frame -> frame.executionEpochId().equals(epochId)).toList();
            boolean hasMore = matching.size() > limit;
            List<FrameSummary> items = matching.stream().limit(limit).map(FrameSummary::from).toList();
            boolean partiallyEvicted = epochId.compareTo(currentEpoch) <= 0
                    && (matching.isEmpty() ? epochId.compareTo(currentEpoch) < 0
                            : matching.getFirst().baselineKind().isEmpty());
            return new EpochFramePage(epochId, items, hasMore, partiallyEvicted,
                    Optional.ofNullable(history.peekFirst()).map(FrameSnapshot::frameId),
                    Optional.ofNullable(history.peekLast()).map(FrameSnapshot::frameId));
        }
    }

    /** Runs application work inside an exception-safe capture frame. */
    public void frame(long deltaNanos, Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        if (!configuration.enabled()) {
            callback.run();
            return;
        }
        beginFrame(deltaNanos);
        Throwable failure = null;
        try {
            callback.run();
        } catch (Throwable callbackFailure) {
            callbackFailed = true;
            failure = callbackFailure;
        }
        try {
            endFrame();
        } catch (Throwable captureFailure) {
            if (failure == null) {
                failure = captureFailure;
            } else {
                failure.addSuppressed(captureFailure);
            }
        }
        if (failure != null) {
            AgentRuntime.<RuntimeException>throwUnchecked(failure);
        }
    }

    /** Runs application work inside a frame using a duration delta. */
    public void frame(Duration delta, Runnable callback) {
        Objects.requireNonNull(delta, "delta");
        frame(delta.toNanos(), callback);
    }

    /**
     * Emits one event into the currently open frame.
     *
     * @return the event ID, or empty when capture is disabled
     */
    public Optional<EventId> emit(EventSpec event) {
        if (!configuration.enabled()) {
            return Optional.empty();
        }
        requireCaptureThread();
        requireOpenFrame("events may only be emitted inside a frame");
        Objects.requireNonNull(event, "event");
        EventId id = new EventId(++nextEvent);
        List<Truncation> truncations = new ArrayList<>();
        List<RuntimeValue.Field> attributes = limitFields(
                event.attributeList(), limits.attributesPerItem(), "event.attributes", truncations);
        pendingEvents.add(new RuntimeEvent(id, activeFrame, event.eventType(),
                event.optionalSubject(), event.optionalSource(), event.metadata(), attributes,
                truncations));
        return Optional.of(id);
    }

    /** Opens one non-nested decision trace in the current frame. */
    public DecisionScope beginDecision(DecisionType type, EntityId actor) {
        return beginDecision(type, actor, FactMetadata.empty());
    }

    /** Opens one non-nested decision with explicit application-provided fact metadata. */
    public DecisionScope beginDecision(
            DecisionType type, EntityId actor, FactMetadata metadata) {
        if (!configuration.enabled()) {
            return DISABLED_DECISION;
        }
        requireCaptureThread();
        requireOpenFrame("decisions may only be opened inside a frame");
        if (openDecision != null) {
            throw lifecycle("nested decisions are not supported in V1");
        }
        MutableDecision decision =
                new MutableDecision(new DecisionId(++nextDecision), activeFrame, type, actor,
                        metadata);
        openDecision = decision;
        pendingDecisions.add(decision);
        return decision;
    }

    /** Associates the next observed property difference with an explicit cause. */
    public void causeNextChange(EntityId entityId, String property, ChangeCause cause) {
        if (!configuration.enabled()) {
            return;
        }
        requireCaptureThread();
        requireOpenFrame("change causes may only be supplied inside a frame");
        ChangeKey key = new ChangeKey(Objects.requireNonNull(entityId, "entityId"),
                IdentifierSupport.validate(property, "property"));
        if (pendingCauses.putIfAbsent(key, Objects.requireNonNull(cause, "cause")) != null) {
            throw lifecycle("a cause is already registered for " + entityId.value() + "." + property);
        }
    }

    /** Returns the latest completed frame. */
    public Optional<FrameSnapshot> latestFrame() {
        synchronized (historyLock) {
            return Optional.ofNullable(history.peekLast());
        }
    }

    /** Returns one retained completed frame. */
    public Optional<FrameSnapshot> frame(FrameId id) {
        Objects.requireNonNull(id, "id");
        synchronized (historyLock) {
            return history.stream().filter(frame -> frame.frameId().equals(id)).findFirst();
        }
    }

    /** Returns bounded frame summaries in ascending order. */
    public QueryPage<FrameSummary> frames(FrameRange range, int limit) {
        Objects.requireNonNull(range, "range");
        validateQueryLimit(limit);
        synchronized (historyLock) {
            return page(history.stream()
                    .filter(frame -> contains(range, frame.frameId()))
                    .map(FrameSummary::from).toList(), range, limit);
        }
    }

    /** Returns bounded matching property differences. */
    public QueryPage<PropertyChange> changes(ChangeQuery query) {
        Objects.requireNonNull(query, "query");
        validateQueryLimit(query.limit());
        synchronized (historyLock) {
            List<PropertyChange> values = history.stream()
                    .filter(frame -> contains(query.range(), frame.frameId()))
                    .flatMap(frame -> frame.changes().stream())
                    .filter(change -> query.entityId().map(change.entityId()::equals).orElse(true))
                    .filter(change -> query.entityType().map(change.entityType()::equals).orElse(true))
                    .filter(change -> query.property()
                            .map(name -> change.property().map(name::equals).orElse(false))
                            .orElse(true))
                    .filter(change -> matchesMetadata(change.cause().metadata(),
                            query.sourceSubsystem(), query.correlationId()))
                    .toList();
            return page(values, query.range(), query.limit());
        }
    }

    /** Returns bounded matching events. */
    public QueryPage<RuntimeEvent> events(EventQuery query) {
        Objects.requireNonNull(query, "query");
        validateQueryLimit(query.limit());
        synchronized (historyLock) {
            List<RuntimeEvent> values = history.stream()
                    .filter(frame -> contains(query.range(), frame.frameId()))
                    .flatMap(frame -> frame.events().stream())
                    .filter(event -> matchesType(event.type().value(), query.type(), query.typePrefix()))
                    .filter(event -> query.subject()
                            .map(subject -> event.subject().map(subject::equals).orElse(false))
                            .orElse(true))
                    .filter(event -> query.source()
                            .map(source -> event.source().map(source::equals).orElse(false))
                            .orElse(true))
                    .filter(event -> matchesMetadata(event.metadata(),
                            query.sourceSubsystem(), query.correlationId()))
                    .toList();
            return page(values, query.range(), query.limit());
        }
    }

    /** Returns bounded matching semantic decisions. */
    public QueryPage<DecisionTrace> decisions(DecisionQuery query) {
        Objects.requireNonNull(query, "query");
        validateQueryLimit(query.limit());
        synchronized (historyLock) {
            List<DecisionTrace> values = history.stream()
                    .filter(frame -> contains(query.range(), frame.frameId()))
                    .flatMap(frame -> frame.decisions().stream())
                    .filter(trace -> query.type().map(trace.type()::equals).orElse(true))
                    .filter(trace -> query.actor().map(trace.actor()::equals).orElse(true))
                    .filter(trace -> query.chosenCandidate()
                            .map(candidate -> trace.chosenCandidate()
                                    .map(candidate::equals).orElse(false))
                            .orElse(true))
                    .filter(trace -> matchesReason(trace, query.reasonCode()))
                    .filter(trace -> matchesMetadata(trace.metadata(),
                            query.sourceSubsystem(), query.correlationId()))
                    .toList();
            return page(values, query.range(), query.limit());
        }
    }

    /** Returns the latest retained state for an entity. */
    public Optional<EntitySnapshot> entity(EntityId id) {
        Objects.requireNonNull(id, "id");
        synchronized (historyLock) {
            FrameSnapshot latest = history.peekLast();
            return latest == null ? Optional.empty() : latest.entity(id);
        }
    }

    /** Returns bounded chronological history for one entity. */
    public EntityHistory entityHistory(EntityId id, FrameRange range) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(range, "range");
        synchronized (historyLock) {
            List<EntityHistory.Version> versions = history.stream()
                    .filter(frame -> contains(range, frame.frameId()))
                    .flatMap(frame -> frame.entity(id).stream()
                            .map(entity -> new EntityHistory.Version(frame.frameId(), entity)))
                    .limit(limits.queryResults())
                    .toList();
            ChangeQuery query = new ChangeQuery(
                    range, Optional.of(id), Optional.empty(), Optional.empty(),
                    limits.queryResults());
            return new EntityHistory(id, versions, changes(query));
        }
    }

    /**
     * Closes capture and releases every live provider and pending executable state.
     *
     * <p>Every registry close hook runs even when an earlier hook fails; the first failure is
     * rethrown after the runtime publishes {@code CLOSED} and suppresses later failures.
     * Completed immutable history, catalogs, and terminal evidence remain queryable.
     */
    @Override
    public void close() {
        requireCaptureThread();
        if (status == RuntimeStatus.CLOSED) {
            return;
        }
        if (activeFrame != null) {
            throw lifecycle("runtime cannot close while a frame is open");
        }
        Throwable firstFailure = closeHooks();
        staticEntities.clear();
        sources.clear();
        pendingCauses.clear();
        pendingEvents.clear();
        pendingDecisions.clear();
        openDecision = null;
        status = RuntimeStatus.CLOSED;
        rethrow(firstFailure);
    }

    private Throwable closeHooks() {
        Throwable firstFailure = null;
        firstFailure = attempt(firstFailure, recordings::close);
        firstFailure = attempt(firstFailure, determinism::close);
        firstFailure = attempt(firstFailure,
                () -> commands.ifPresent(CommandDispatch::close));
        firstFailure = attempt(firstFailure, checkpoints::close);
        firstFailure = attempt(firstFailure, uiCorrelations::close);
        firstFailure = attempt(firstFailure, scenarios::close);
        firstFailure = attempt(firstFailure, actions::close);
        firstFailure = attempt(firstFailure, controls::close);
        firstFailure = attempt(firstFailure, inputs::close);
        return firstFailure;
    }

    private static Throwable attempt(Throwable firstFailure, Runnable hook) {
        try {
            hook.run();
            return firstFailure;
        } catch (RuntimeException | Error failure) {
            return firstFailure == null ? failure : firstFailure;
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    EntityRegistration registerStatic(InspectableEntity entity) {
        if (!configuration.enabled()) {
            return () -> {};
        }
        requireMutableRegistration();
        Objects.requireNonNull(entity, "entity");
        if (staticEntities.putIfAbsent(entity.id(), entity) != null) {
            throw new AgentRuntimeException(RuntimeErrorCode.DUPLICATE_ENTITY,
                    "duplicate static entity ID: " + entity.id().value());
        }
        return registration(() -> staticEntities.remove(entity.id(), entity));
    }

    EntityRegistration registerSource(String name,
            Supplier<? extends Stream<InspectableEntity>> source) {
        if (!configuration.enabled()) {
            return () -> {};
        }
        requireMutableRegistration();
        IdentifierSupport.validate(name, "source name");
        Objects.requireNonNull(source, "source");
        if (sources.putIfAbsent(name, source) != null) {
            throw new AgentRuntimeException(RuntimeErrorCode.DUPLICATE_ENTITY,
                    "duplicate source name: " + name);
        }
        return registration(() -> sources.remove(name, source));
    }

    private void endFrameInternal() {
        if (openDecision != null) {
            openDecision.abort();
        }
        List<DecisionTrace> decisions = pendingDecisions.stream()
                .limit(limits.decisionsPerFrame())
                .map(decision -> decision.snapshot(callbackFailed))
                .toList();
        List<Truncation> frameTruncations = new ArrayList<>();
        if (pendingDecisions.size() > decisions.size()) {
            frameTruncations.add(new Truncation("frame.decisions", pendingDecisions.size(),
                    decisions.size(), limits.decisionsPerFrame()));
        }
        List<RuntimeEvent> events = pendingEvents.stream()
                .limit(limits.retainedEvents()).toList();
        if (pendingEvents.size() > events.size()) {
            frameTruncations.add(new Truncation("frame.events", pendingEvents.size(),
                    events.size(), limits.retainedEvents()));
        }

        CaptureResult capture = captureEntities();
        frameTruncations.addAll(capture.truncations());
        List<PropertyChange> changes = activeBaseline.isPresent()
                ? List.of() : diff(previousFrame, capture.entities(), activeFrame);
        FrameSnapshot completed = new FrameSnapshot(
                sessionId, activeFrame, currentEpoch, activeBaseline,
                requireMonotonicTime(), activeDeltaNanos,
                Instant.now(wallClock), capture.entities(), changes, events, decisions,
                new SnapshotStats(capture.observed(), capture.entities().size(),
                        capture.diagnostics(), frameTruncations));
        retain(completed);
        previousFrame = completed;
        activeFrame = null;
        recordings.recordFrame(completed);
        activeBaseline = Optional.empty();
        pendingEvents.clear();
        pendingDecisions.clear();
        pendingCauses.clear();
        openDecision = null;
        callbackFailed = false;
    }

    private CaptureResult captureEntities() {
        List<CaptureDiagnostic> diagnostics = new ArrayList<>();
        List<NamedEntity> observed = new ArrayList<>();
        staticEntities.values().forEach(entity -> observed.add(new NamedEntity("static", entity)));
        for (Map.Entry<String, Supplier<? extends Stream<InspectableEntity>>> source
                : sources.entrySet()) {
            try (Stream<InspectableEntity> stream =
                    Objects.requireNonNull(source.getValue().get(), "source stream")) {
                stream.forEach(entity -> observed.add(
                        new NamedEntity(source.getKey(), Objects.requireNonNull(entity, "entity"))));
            } catch (RuntimeException failure) {
                diagnostics.add(diagnostic(
                        "provider.source", source.getKey(), null, null, failure));
            }
        }
        observed.sort(Comparator.comparing((NamedEntity value) -> value.entity().id())
                .thenComparingInt(value -> "static".equals(value.provider()) ? 0 : 1)
                .thenComparing(NamedEntity::provider));
        List<EntitySnapshot> snapshots = new ArrayList<>();
        List<Truncation> truncations = new ArrayList<>();
        EntityId priorId = null;
        for (NamedEntity named : observed) {
            if (named.entity().id().equals(priorId)) {
                diagnostics.add(new CaptureDiagnostic(named.provider(),
                        Optional.of(named.entity().id()), Optional.empty(),
                        new ApplicationFailureEvidence("capture.duplicateEntityId",
                                AgentRuntimeException.class.getName(),
                                AgentRuntime.this.diagnostics.nextCorrelationId(),
                                Optional.empty())));
                continue;
            }
            priorId = named.entity().id();
            if (snapshots.size() >= limits.entitiesPerSnapshot()) {
                continue;
            }
            snapshots.add(captureEntity(named, diagnostics));
        }
        long uniqueEntities = observed.stream().map(value -> value.entity().id()).distinct().count();
        if (uniqueEntities > limits.entitiesPerSnapshot()) {
            truncations.add(new Truncation("snapshot.entities", uniqueEntities, snapshots.size(),
                    limits.entitiesPerSnapshot()));
        }
        return new CaptureResult(List.copyOf(snapshots), observed.size(),
                List.copyOf(diagnostics), List.copyOf(truncations));
    }

    private EntitySnapshot captureEntity(
            NamedEntity named, List<CaptureDiagnostic> diagnostics) {
        InspectableEntity entity = named.entity();
        List<Truncation> truncations = new ArrayList<>();
        Optional<String> displayName;
        try {
            String name = entity.displayName().get();
            displayName = name == null ? Optional.empty()
                    : Optional.of(limitString(name, "entity.displayName", truncations));
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic(
                    "provider.displayName", named.provider(), entity.id(), null, failure));
            displayName = Optional.empty();
        }

        List<EntityInspector.PropertyProvider> providers =
                new ArrayList<>(entity.properties());
        providers.sort(Comparator.comparing(EntityInspector.PropertyProvider::name));
        List<RuntimeValue.Field> values = new ArrayList<>();
        String previousName = null;
        for (EntityInspector.PropertyProvider property : providers) {
            if (property.name().equals(previousName)) {
                diagnostics.add(new CaptureDiagnostic(named.provider(), Optional.of(entity.id()),
                        Optional.of(property.name()),
                        new ApplicationFailureEvidence("capture.duplicateProperty",
                                IllegalArgumentException.class.getName(),
                                AgentRuntime.this.diagnostics.nextCorrelationId(),
                                Optional.empty())));
                continue;
            }
            previousName = property.name();
            if (values.size() >= limits.propertiesPerEntity()) {
                continue;
            }
            try {
                RuntimeValue value =
                        Objects.requireNonNull(property.provider().get(), "property value");
                values.add(new RuntimeValue.Field(property.name(),
                        limitValue(value, 1, "property." + property.name(), truncations)));
            } catch (RuntimeException failure) {
                diagnostics.add(diagnostic(
                        "provider.property", named.provider(), entity.id(),
                        property.name(), failure));
            }
        }
        if (providers.size() > limits.propertiesPerEntity()) {
            truncations.add(new Truncation("entity.propertyProvidersEvaluated", providers.size(),
                    limits.propertiesPerEntity(),
                    limits.propertiesPerEntity()));
        }
        return new EntitySnapshot(
                entity.id(), entity.type(), displayName, values, truncations);
    }

    private List<PropertyChange> diff(
            FrameSnapshot previous, List<EntitySnapshot> current, FrameId frameId) {
        if (previous == null) {
            return List.of();
        }
        Map<EntityId, EntitySnapshot> before = index(previous.entities());
        Map<EntityId, EntitySnapshot> after = index(current);
        List<PropertyChange> changes = new ArrayList<>();
        List<EntityId> ids = Stream.concat(before.keySet().stream(), after.keySet().stream())
                .distinct().sorted().toList();
        for (EntityId id : ids) {
            EntitySnapshot oldEntity = before.get(id);
            EntitySnapshot newEntity = after.get(id);
            if (oldEntity == null) {
                changes.add(entityChange(frameId, newEntity, ChangeKind.ENTITY_ADDED));
            } else if (newEntity == null) {
                changes.add(entityChange(frameId, oldEntity, ChangeKind.ENTITY_REMOVED));
            } else {
                diffProperties(frameId, oldEntity, newEntity, changes);
            }
        }
        return List.copyOf(changes);
    }

    private void diffProperties(FrameId frameId, EntitySnapshot before, EntitySnapshot after,
            List<PropertyChange> destination) {
        Map<String, RuntimeValue> oldValues = propertyIndex(before);
        Map<String, RuntimeValue> newValues = propertyIndex(after);
        List<String> names = Stream.concat(oldValues.keySet().stream(), newValues.keySet().stream())
                .distinct().sorted().toList();
        for (String name : names) {
            RuntimeValue oldValue = oldValues.get(name);
            RuntimeValue newValue = newValues.get(name);
            ChangeKind kind;
            if (oldValue == null) {
                kind = ChangeKind.PROPERTY_ADDED;
            } else if (newValue == null) {
                kind = ChangeKind.PROPERTY_REMOVED;
            } else if (!oldValue.equals(newValue)) {
                kind = ChangeKind.PROPERTY_CHANGED;
            } else {
                continue;
            }
            ChangeCause cause =
                    pendingCauses.getOrDefault(new ChangeKey(after.id(), name), ChangeCause.unknown());
            destination.add(new PropertyChange(frameId, after.id(), after.type(), kind,
                    Optional.of(name), Optional.ofNullable(oldValue),
                    Optional.ofNullable(newValue), cause));
        }
    }

    private static PropertyChange entityChange(
            FrameId frameId, EntitySnapshot entity, ChangeKind kind) {
        return new PropertyChange(frameId, entity.id(), entity.type(), kind,
                Optional.empty(), Optional.empty(), Optional.empty(), ChangeCause.unknown());
    }

    private void retain(FrameSnapshot completed) {
        synchronized (historyLock) {
            history.addLast(completed);
            int retainedEventCount =
                    history.stream().mapToInt(frame -> frame.events().size()).sum();
            while (history.size() > limits.retainedFrames()
                    || retainedEventCount > limits.retainedEvents()) {
                FrameSnapshot removed = history.removeFirst();
                retainedEventCount -= removed.events().size();
            }
        }
    }

    private RuntimeValue limitValue(
            RuntimeValue value, int depth, String dimension, List<Truncation> truncations) {
        if (depth > limits.nestingDepth()) {
            truncations.add(new Truncation(dimension + ".depth", depth, limits.nestingDepth(),
                    limits.nestingDepth()));
            return RuntimeValues.nullValue();
        }
        return switch (value) {
            case RuntimeValue.StringValue string ->
                    RuntimeValues.string(limitString(string.value(), dimension, truncations));
            case RuntimeValue.EnumValue enumeration -> RuntimeValues.enumValue(
                    limitString(enumeration.value(), dimension, truncations));
            case RuntimeValue.ListValue list -> {
                int retained = Math.min(list.values().size(), limits.collectionLength());
                if (retained < list.values().size()) {
                    truncations.add(new Truncation(dimension + ".items", list.values().size(),
                            retained, limits.collectionLength()));
                }
                List<RuntimeValue> values = new ArrayList<>(retained);
                for (int index = 0; index < retained; index++) {
                    values.add(limitValue(
                            list.values().get(index), depth + 1, dimension, truncations));
                }
                yield RuntimeValues.list(values);
            }
            case RuntimeValue.ObjectValue object -> {
                int retained = Math.min(object.fields().size(), limits.collectionLength());
                if (retained < object.fields().size()) {
                    truncations.add(new Truncation(dimension + ".fields", object.fields().size(),
                            retained, limits.collectionLength()));
                }
                List<RuntimeValue.Field> fields = new ArrayList<>(retained);
                for (int index = 0; index < retained; index++) {
                    RuntimeValue.Field field = object.fields().get(index);
                    fields.add(RuntimeValues.field(field.name(), limitValue(
                            field.value(), depth + 1, dimension + "." + field.name(), truncations)));
                }
                yield new RuntimeValue.ObjectValue(fields);
            }
            default -> value;
        };
    }

    private List<RuntimeValue.Field> limitFields(List<RuntimeValue.Field> fields, int maximum,
            String dimension, List<Truncation> truncations) {
        List<RuntimeValue.Field> sorted = new ArrayList<>(fields);
        sorted.sort(Comparator.comparing(RuntimeValue.Field::name));
        int retained = Math.min(sorted.size(), maximum);
        if (retained < sorted.size()) {
            truncations.add(new Truncation(dimension, sorted.size(), retained, maximum));
        }
        List<RuntimeValue.Field> result = new ArrayList<>(retained);
        for (int index = 0; index < retained; index++) {
            RuntimeValue.Field field = sorted.get(index);
            result.add(RuntimeValues.field(field.name(),
                    limitValue(field.value(), 1, dimension + "." + field.name(), truncations)));
        }
        return List.copyOf(result);
    }

    private String limitString(
            String value, String dimension, List<Truncation> truncations) {
        Objects.requireNonNull(value, "value");
        if (value.length() <= limits.stringLength()) {
            return value;
        }
        truncations.add(new Truncation(
                dimension + ".length", value.length(), limits.stringLength(), limits.stringLength()));
        return value.substring(0, limits.stringLength());
    }

    private void validateQueryLimit(int limit) {
        if (limit <= 0 || limit > limits.queryResults()) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "query limit must be between 1 and " + limits.queryResults());
        }
    }

    private <T> QueryPage<T> page(List<T> values, FrameRange range, int limit) {
        boolean hasMore = values.size() > limit;
        List<T> retained = values.stream().limit(limit).toList();
        Optional<FrameId> oldest =
                Optional.ofNullable(history.peekFirst()).map(FrameSnapshot::frameId);
        Optional<FrameId> newest =
                Optional.ofNullable(history.peekLast()).map(FrameSnapshot::frameId);
        boolean evicted = oldest.map(id -> range.from().compareTo(id) < 0).orElse(false);
        return new QueryPage<>(retained, hasMore, evicted, oldest, newest);
    }

    private EntityRegistration registration(Runnable removal) {
        return new Registration(removal);
    }

    private void requireMutableRegistration() {
        requireCaptureThread();
        if (status == RuntimeStatus.CLOSED) {
            throw new AgentRuntimeException(RuntimeErrorCode.RUNTIME_CLOSED, "runtime is closed");
        }
        if (activeFrame != null) {
            throw lifecycle("providers cannot change while a frame is open");
        }
    }

    void requireScenarioRegistration() {
        requireMutableRegistration();
    }

    void requireActionRegistration() {
        requireMutableRegistration();
    }

    void requireControlRegistration() {
        requireMutableRegistration();
    }

    void requireInputRegistration() {
        requireMutableRegistration();
    }

    void requireCheckpointRegistration() {
        requireMutableRegistration();
    }

    void requireUiCorrelationMutation() {
        requireMutableRegistration();
    }

    void requireRecordingMutation() {
        requireMutableRegistration();
    }

    /** Rejects new submissions once the runtime has closed. */
    void requireSubmissionsOpen() {
        if (status == RuntimeStatus.CLOSED) {
            throw new AgentRuntimeException(RuntimeErrorCode.RUNTIME_CLOSED, "runtime is closed");
        }
    }

    long monotonicTimeNanos() {
        return monotonicClock.nanoTime();
    }

    Instant wallTime() {
        return Instant.now(wallClock);
    }

    void requireCheckpointMutation() {
        requireCaptureThread();
        requireRunning();
        if (activeFrame != null) {
            throw lifecycle("a checkpoint cannot mutate state while a frame is open");
        }
    }

    FrameId executeScenarioReset(Runnable reset) {
        requireCaptureThread();
        requireRunning();
        if (activeFrame != null) {
            throw lifecycle("a scenario cannot reset while a frame is open");
        }
        reset.run();
        return startEpoch(BaselineKind.SCENARIO_RESET);
    }

    private void requireCaptureThread() {
        if (Thread.currentThread() != captureThread) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.WRONG_THREAD, "operation requires the capture thread");
        }
    }

    private void requireRunning() {
        if (status == RuntimeStatus.CLOSED) {
            throw new AgentRuntimeException(RuntimeErrorCode.RUNTIME_CLOSED, "runtime is closed");
        }
        if (status != RuntimeStatus.RUNNING) {
            throw lifecycle("runtime must be started");
        }
    }

    private void requireOpenFrame(String message) {
        requireRunning();
        if (activeFrame == null) {
            throw lifecycle(message);
        }
    }

    private long requireMonotonicTime() {
        long value = monotonicClock.nanoTime();
        if (value < 0) {
            throw new IllegalStateException("monotonic clock returned a negative value");
        }
        return value;
    }

    private static AgentRuntimeException lifecycle(String message) {
        return new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE, message);
    }

    ApplicationDiagnostics diagnostics() {
        return diagnostics;
    }

    private CaptureDiagnostic diagnostic(String category, String provider, EntityId entity,
            String property, RuntimeException failure) {
        return new CaptureDiagnostic(provider, Optional.ofNullable(entity),
                Optional.ofNullable(property), diagnostics.describe(category, failure));
    }

    private static Map<EntityId, EntitySnapshot> index(List<EntitySnapshot> entities) {
        Map<EntityId, EntitySnapshot> result = new LinkedHashMap<>();
        entities.forEach(entity -> result.put(entity.id(), entity));
        return result;
    }

    private static Map<String, RuntimeValue> propertyIndex(EntitySnapshot entity) {
        Map<String, RuntimeValue> result = new LinkedHashMap<>();
        entity.properties().forEach(property -> result.put(property.name(), property.value()));
        return result;
    }

    private static boolean contains(FrameRange range, FrameId id) {
        return id.compareTo(range.from()) >= 0 && id.compareTo(range.to()) <= 0;
    }

    private static boolean matchesType(
            String value, Optional<String> filter, boolean prefix) {
        return filter.map(expected -> prefix ? value.startsWith(expected) : value.equals(expected))
                .orElse(true);
    }

    private static boolean matchesReason(DecisionTrace trace, Optional<String> reason) {
        return reason.map(code ->
                trace.choiceReason().map(value -> value.code().equals(code)).orElse(false)
                || trace.candidates().stream().anyMatch(
                        candidate -> candidate.reason().code().equals(code))).orElse(true);
    }

    private static boolean matchesMetadata(FactMetadata metadata,
            Optional<String> subsystem, Optional<String> correlationId) {
        return subsystem.map(value -> metadata.sourceSubsystem().map(value::equals).orElse(false))
                .orElse(true)
                && correlationId.map(value -> metadata.correlationId()
                        .map(value::equals).orElse(false)).orElse(true);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }

    private record NamedEntity(String provider, InspectableEntity entity) {}

    private record CaptureResult(
            List<EntitySnapshot> entities,
            long observed,
            List<CaptureDiagnostic> diagnostics,
            List<Truncation> truncations) {}

    private record ChangeKey(EntityId entityId, String property) {}

    private final class Registration implements EntityRegistration {
        private Runnable removal;

        Registration(Runnable removal) {
            this.removal = Objects.requireNonNull(removal, "removal");
        }

        @Override public void close() {
            if (removal == null) {
                return;
            }
            requireMutableRegistration();
            Runnable action = removal;
            removal = null;
            action.run();
        }
    }

    private final class MutableDecision implements DecisionScope {
        private final DecisionId id;
        private final FrameId frameId;
        private final DecisionType type;
        private final EntityId actor;
        private final FactMetadata metadata;
        private final List<DecisionCandidate> candidates = new ArrayList<>();
        private final List<Truncation> truncations = new ArrayList<>();
        private EntityId chosen;
        private Reason choiceReason;
        private DecisionTrace.Completion completion = DecisionTrace.Completion.ABORTED;
        private boolean closed;

        MutableDecision(DecisionId id, FrameId frameId, DecisionType type, EntityId actor,
                FactMetadata metadata) {
            this.id = id;
            this.frameId = frameId;
            this.type = Objects.requireNonNull(type, "type");
            this.actor = Objects.requireNonNull(actor, "actor");
            this.metadata = Objects.requireNonNull(metadata, "metadata");
        }

        @Override
        public void reject(
                EntityId candidate, Reason reason, RuntimeValue.Field... attributes) {
            add(candidate, DecisionCandidate.Status.REJECTED, reason, attributes);
        }

        @Override
        public void accept(
                EntityId candidate, Reason reason, RuntimeValue.Field... attributes) {
            add(candidate, DecisionCandidate.Status.ACCEPTED, reason, attributes);
        }

        @Override
        public void choose(EntityId candidate, Reason reason) {
            requireMutable();
            chosen = Objects.requireNonNull(candidate, "candidate");
            choiceReason = Objects.requireNonNull(reason, "reason");
        }

        @Override
        public Optional<DecisionId> id() {
            return Optional.of(id);
        }

        @Override
        public void close() {
            requireCaptureThread();
            if (closed) {
                return;
            }
            completion = DecisionTrace.Completion.COMPLETED;
            closed = true;
            if (openDecision == this) {
                openDecision = null;
            }
        }

        void abort() {
            closed = true;
            completion = DecisionTrace.Completion.ABORTED;
            if (openDecision == this) {
                openDecision = null;
            }
        }

        DecisionTrace snapshot(boolean failedCallback) {
            DecisionTrace.Completion actual = failedCallback
                    ? DecisionTrace.Completion.ABORTED : completion;
            return new DecisionTrace(id, frameId, type, actor, candidates,
                    Optional.ofNullable(chosen), Optional.ofNullable(choiceReason),
                    metadata, actual, truncations);
        }

        private void add(EntityId candidate, DecisionCandidate.Status status, Reason reason,
                RuntimeValue.Field[] attributes) {
            requireMutable();
            if (candidates.size() >= limits.candidatesPerDecision()) {
                if (truncations.isEmpty()) {
                    truncations.add(new Truncation("decision.candidates",
                            candidates.size() + 1L, candidates.size(),
                            limits.candidatesPerDecision()));
                } else {
                    Truncation prior = truncations.getFirst();
                    truncations.set(0, new Truncation(prior.dimension(), prior.observed() + 1,
                            prior.retained(), prior.limit()));
                }
                return;
            }
            List<RuntimeValue.Field> fields = limitFields(List.of(attributes),
                    limits.attributesPerItem(), "decision.candidate.attributes", truncations);
            candidates.add(new DecisionCandidate(candidate, status, reason, fields));
        }

        private void requireMutable() {
            requireCaptureThread();
            if (closed) {
                throw lifecycle("decision scope is closed");
            }
            if (activeFrame == null || !frameId.equals(activeFrame)) {
                throw lifecycle("decision scope no longer belongs to the open frame");
            }
        }
    }

    private static final class DisabledDecisionScope implements DecisionScope {
        @Override public void reject(
                EntityId candidate, Reason reason, RuntimeValue.Field... attributes) {}
        @Override public void accept(
                EntityId candidate, Reason reason, RuntimeValue.Field... attributes) {}
        @Override public void choose(EntityId candidate, Reason reason) {}
        @Override public Optional<DecisionId> id() {
            return Optional.empty();
        }
        @Override public void close() {}
    }

    /** Builder for an explicitly owned runtime instance. */
    public static final class Builder {
        private SessionId sessionId = SessionId.of(UUID.randomUUID().toString());
        private RuntimeConfiguration configuration = RuntimeConfiguration.developmentDefaults();
        private MonotonicClock monotonicClock = MonotonicClock.system();
        private Clock wallClock = Clock.systemUTC();
        private Thread captureThread = Thread.currentThread();
        private ApplicationCommandDispatcher commandDispatcher;
        private ApplicationFailureSanitizer applicationFailureSanitizer;
        private CommandDispatchLimits commandDispatchLimits =
                CommandDispatchLimits.developmentDefaults();
        private ScenarioLimits scenarioLimits = ScenarioLimits.developmentDefaults();
        private ActionLimits actionLimits = ActionLimits.developmentDefaults();
        private ControlLimits controlLimits = ControlLimits.developmentDefaults();
        private InputLimits inputLimits = InputLimits.developmentDefaults();
        private CheckpointLimits checkpointLimits = CheckpointLimits.developmentDefaults();
        private UiCorrelationLimits uiCorrelationLimits =
                UiCorrelationLimits.developmentDefaults();
        private RecordingLimits recordingLimits = RecordingLimits.developmentDefaults();
        private DeterminismLimits determinismLimits = DeterminismLimits.developmentDefaults();

        private Builder() {}

        /** Sets a stable caller-selected session ID. */
        public Builder sessionId(SessionId value) {
            sessionId = Objects.requireNonNull(value, "sessionId");
            return this;
        }

        /** Sets feature state and bounds. */
        public Builder configuration(RuntimeConfiguration value) {
            configuration = Objects.requireNonNull(value, "configuration");
            return this;
        }

        /** Sets the monotonic capture clock. */
        public Builder clock(MonotonicClock value) {
            monotonicClock = Objects.requireNonNull(value, "clock");
            return this;
        }

        /** Sets informational wall-clock metadata. */
        public Builder wallClock(Clock value) {
            wallClock = Objects.requireNonNull(value, "wallClock");
            return this;
        }

        /** Sets the sole thread allowed to mutate capture state. */
        public Builder captureThread(Thread value) {
            captureThread = Objects.requireNonNull(value, "captureThread");
            return this;
        }

        /** Registers the application-owned bridge used by bounded mutating commands. */
        public Builder commandDispatcher(ApplicationCommandDispatcher value) {
            commandDispatcher = Objects.requireNonNull(value, "commandDispatcher");
            return this;
        }

        /**
         * Sets the optional application-owned translation of callback failures into bounded public
         * detail.
         *
         * <p>Without a sanitizer, failure evidence contains only the stable category, exception
         * class, and deterministic correlation identifier; raw exception messages and stack traces
         * are never exposed. A present sanitizer result is truncated to the configured string
         * bound. A throwing sanitizer fails closed: the original failure is logged locally and no
         * raw message becomes queryable.
         */
        public Builder applicationFailureSanitizer(ApplicationFailureSanitizer value) {
            applicationFailureSanitizer =
                    Objects.requireNonNull(value, "applicationFailureSanitizer");
            return this;
        }

        /** Sets hard queue, outcome, request-ID, and diagnostic bounds. */
        public Builder commandDispatchLimits(CommandDispatchLimits value) {
            commandDispatchLimits = Objects.requireNonNull(value, "commandDispatchLimits");
            return this;
        }

        /** Configures hard bounds for application-registered scenarios. */
        public Builder scenarioLimits(ScenarioLimits value) {
            scenarioLimits = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Configures hard bounds for application-registered semantic actions. */
        public Builder actionLimits(ActionLimits value) {
            actionLimits = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Configures hard bounds for optional simulation control. */
        public Builder controlLimits(ControlLimits value) {
            controlLimits = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Configures hard bounds for registered and scheduled input facts. */
        public Builder inputLimits(InputLimits value) {
            inputLimits = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Configures hard bounds for retained opaque checkpoints and operation evidence. */
        public Builder checkpointLimits(CheckpointLimits value) {
            checkpointLimits = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Configures hard bounds for runtime/UI bindings and frame mappings. */
        public Builder uiCorrelationLimits(UiCorrelationLimits value) {
            uiCorrelationLimits = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Configures independent recording, retention, size, and chunk bounds. */
        public Builder recordingLimits(RecordingLimits value) {
            recordingLimits = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Configures repeated execution, evidence, and deadline bounds. */
        public Builder determinismLimits(DeterminismLimits value) {
            determinismLimits = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Builds an unstarted runtime. */
        public AgentRuntime build() {
            return new AgentRuntime(this);
        }
    }
}
