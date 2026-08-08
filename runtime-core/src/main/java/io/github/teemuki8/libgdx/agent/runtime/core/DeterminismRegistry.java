package io.github.teemuki8.libgdx.agent.runtime.core;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Application-dispatched bounded repeated-scenario execution and comparison. */
public final class DeterminismRegistry {
    private static final String EQUAL_MESSAGE =
            "equal for the configured observable state; whole-program determinism is not proven";
    private final AgentRuntime runtime;
    private final DeterminismLimits limits;
    private final LinkedHashMap<String, Evidence> operations = new LinkedHashMap<>();
    private final LinkedHashMap<String, DeterminismSpec> evictedOperations =
            new LinkedHashMap<>();

    DeterminismRegistry(AgentRuntime runtime, DeterminismLimits limits) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Submits or polls one at-most-once bounded determinism comparison. */
    public DeterminismOperation check(
            DeterminismSpec spec, String requestId, Duration timeout) {
        Objects.requireNonNull(spec, "spec");
        IdentifierSupport.validate(requestId, "determinism request id");
        CommandDispatch dispatch = runtime.commands().orElseThrow(() ->
                new IllegalStateException("determinism comparison requires command dispatch"));
        long timeoutNanos = requireTimeout(timeout, dispatch.limits().maximumTimeoutNanos());
        validate(spec);
        Evidence evidence;
        synchronized (this) {
            evidence = operations.get(requestId);
            if (evidence != null) {
                if (!evidence.spec.equals(spec)) {
                    throw new IllegalArgumentException(
                            "determinism request id is bound to a different specification");
                }
                return snapshot(requestId, evidence, dispatch.status(requestId));
            }
            DeterminismSpec evictedSpec = evictedOperations.get(requestId);
            if (evictedSpec != null) {
                if (!evictedSpec.equals(spec)) {
                    throw new IllegalArgumentException(
                            "determinism request id is bound to a different specification");
                }
                return evicted(requestId, spec, dispatch.status(requestId));
            }
            if (dispatch.status(requestId).kind() != CommandLookup.Kind.UNKNOWN) {
                throw new IllegalArgumentException(
                        "determinism request correlation evidence is no longer retained");
            }
            makeRoom(dispatch);
            evidence = new Evidence(spec);
            operations.put(requestId, evidence);
        }
        Evidence retained = evidence;
        long executionNanos = Math.min(timeoutNanos, limits.maximumExecutionNanos());
        long deadline = deadline(executionNanos);
        CommandLookup lookup = dispatch.submit(requestId, timeout, () ->
                retained.result = Optional.of(execute(spec, deadline, executionNanos)));
        return snapshot(requestId, retained, lookup);
    }

    /** Returns configured hard determinism bounds. */
    public DeterminismLimits limits() {
        return limits;
    }

    private DeterminismResult execute(
            DeterminismSpec spec, long deadline, long executionNanos) {
        ArrayList<RunEvidence> runs = new ArrayList<>();
        Counters counters = new Counters();
        boolean previouslyPaused;
        try {
            previouslyPaused = runtime.controls().pauseForDeterminism();
        } catch (RuntimeException | Error failure) {
            return inconclusive(spec, counters, executionNanos,
                    "simulation pause failed: " + diagnostic("determinism.pause", failure));
        }
        long uiEvictions = runtime.uiCorrelations().evictedFrameCount();
        try {
            for (int repeat = 0; repeat < spec.repeatCount(); repeat++) {
                if (expired(deadline)) {
                    return inconclusive(spec, counters, executionNanos,
                            "execution deadline elapsed");
                }
                FrameId baseline = runtime.scenarios().resetForDeterminism(
                        spec.scenarioId(), new ScenarioResetContext(
                                OptionalLong.of(spec.randomSeed()), spec.configuration()));
                ArrayList<FrameEvidence> frames = new ArrayList<>();
                FrameSnapshot baselineSnapshot = runtime.frame(baseline).orElseThrow();
                frames.add(capture(baselineSnapshot, spec.profile(), counters));
                for (int tick = 1; tick <= spec.ticksPerRepeat(); tick++) {
                    if (expired(deadline)) {
                        return inconclusive(spec, counters, executionNanos,
                                "execution deadline elapsed");
                    }
                    FrameSnapshot frame = runtime.controls().tickForDeterminism(spec.deltaNanos());
                    frames.add(capture(frame, spec.profile(), counters));
                }
                runs.add(new RunEvidence(runtime.currentEpoch(), List.copyOf(frames)));
                counters.completedRepeats++;
                if (counters.incompleteReason.isPresent()) {
                    return inconclusive(spec, counters, executionNanos,
                            counters.incompleteReason.orElseThrow());
                }
                if (spec.profile().includeUiCorrelations()
                        && runtime.uiCorrelations().evictedFrameCount() != uiEvictions) {
                    return inconclusive(spec, counters, executionNanos,
                            "UI correlation evidence was evicted");
                }
            }
            return compare(spec, runs, counters, executionNanos);
        } catch (RuntimeException | Error failure) {
            return inconclusive(spec, counters, executionNanos,
                    "scenario reset or tick failed: "
                            + diagnostic("determinism.execute", failure));
        } finally {
            runtime.controls().restorePauseAfterDeterminism(previouslyPaused);
        }
    }

    private FrameEvidence capture(FrameSnapshot frame, DeterminismProfile profile, Counters counters) {
        SnapshotComparisonScope scope = profile.comparisonScope();
        List<EntitySnapshot> entities = frame.entities().stream()
                .filter(entity -> scope.entityIds().isEmpty()
                        || scope.entityIds().contains(entity.id()))
                .map(entity -> comparableEntity(entity, scope))
                .toList();
        List<ComparableEvent> events = scope.includeEvents()
                ? frame.events().stream().map(ComparableEvent::from).toList() : List.of();
        List<ComparableDecision> decisions = scope.includeDecisions()
                ? frame.decisions().stream().map(ComparableDecision::from).toList() : List.of();
        List<ComparableUi> ui = profile.includeUiCorrelations()
                ? runtime.uiCorrelations().correlationsFor(
                        frame.executionEpochId(), frame.frameId()).stream()
                        .map(ComparableUi::from).toList()
                : List.of();
        long facts = entities.stream().mapToLong(value -> value.properties().size()).sum()
                + events.size() + decisions.size() + ui.size();
        counters.observedEntities += entities.size();
        counters.observedFacts += facts;
        if (entities.size() > limits.maximumEntitiesPerFrame()
                || facts > limits.maximumFactsPerFrame()) {
            counters.incompleteReason = Optional.of("determinism evidence count limit exceeded");
        }
        if (!frame.stats().diagnostics().isEmpty() || !frame.stats().truncations().isEmpty()
                || entities.stream().anyMatch(EntitySnapshot::truncated)
                || frame.events().stream().anyMatch(value -> !value.truncations().isEmpty())
                || frame.decisions().stream().anyMatch(value -> !value.truncations().isEmpty())) {
            counters.incompleteReason = Optional.of(
                    "capture diagnostics or truncation could hide a divergence");
        }
        FrameEvidence evidence = new FrameEvidence(
                frame.frameId(), entities, events, decisions, ui);
        counters.encodedBytes += evidence.toString().getBytes(StandardCharsets.UTF_8).length;
        if (counters.encodedBytes > limits.maximumEncodedEvidenceBytes()) {
            counters.incompleteReason = Optional.of("encoded determinism evidence limit exceeded");
        }
        return evidence;
    }

    private DeterminismResult compare(DeterminismSpec spec, List<RunEvidence> runs,
            Counters counters, long executionNanos) {
        RunEvidence reference = runs.getFirst();
        for (int repeat = 1; repeat < runs.size(); repeat++) {
            RunEvidence candidate = runs.get(repeat);
            for (int tick = 0; tick < reference.frames.size(); tick++) {
                FrameEvidence left = reference.frames.get(tick);
                FrameEvidence right = candidate.frames.get(tick);
                Optional<DeterminismDifference> difference = difference(left, right);
                if (difference.isPresent()) {
                    return new DeterminismResult(
                            DeterminismStatus.DIVERGED,
                            "first divergence in configured observable state",
                            spec.profile(), OptionalInt.of(tick), Optional.of(reference.epoch),
                            Optional.of(candidate.epoch), Optional.of(left.frameId),
                            Optional.of(right.frameId), difference,
                            bounds(spec, counters, executionNanos));
                }
            }
        }
        return new DeterminismResult(
                DeterminismStatus.EQUAL, EQUAL_MESSAGE, spec.profile(), OptionalInt.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), bounds(spec, counters, executionNanos));
    }

    private Optional<DeterminismDifference> difference(FrameEvidence left, FrameEvidence right) {
        Map<EntityId, EntitySnapshot> leftEntities = index(left.entities);
        Map<EntityId, EntitySnapshot> rightEntities = index(right.entities);
        LinkedHashSet<EntityId> entityIds = new LinkedHashSet<>(leftEntities.keySet());
        entityIds.addAll(rightEntities.keySet());
        List<EntityId> orderedIds = entityIds.stream().sorted().toList();
        for (EntityId id : orderedIds) {
            EntitySnapshot leftEntity = leftEntities.get(id);
            EntitySnapshot rightEntity = rightEntities.get(id);
            if (leftEntity == null || rightEntity == null
                    || !leftEntity.type().equals(rightEntity.type())
                    || !leftEntity.displayName().equals(rightEntity.displayName())) {
                return Optional.of(new DeterminismDifference(
                        DeterminismDifferenceKind.ENTITY_LIFECYCLE, Optional.of(id.value()),
                        entityIdentity(leftEntity), entityIdentity(rightEntity)));
            }
            Optional<DeterminismDifference> property = propertyDifference(leftEntity, rightEntity);
            if (property.isPresent()) {
                return property;
            }
        }
        Optional<DeterminismDifference> event = listDifference(
                DeterminismDifferenceKind.EVENT, "event", left.events, right.events);
        if (event.isPresent()) {
            return event;
        }
        Optional<DeterminismDifference> decision = listDifference(
                DeterminismDifferenceKind.DECISION, "decision", left.decisions, right.decisions);
        if (decision.isPresent()) {
            return decision;
        }
        return listDifference(DeterminismDifferenceKind.UI_CORRELATION,
                "uiCorrelation", left.ui, right.ui);
    }

    private Optional<DeterminismDifference> propertyDifference(
            EntitySnapshot left, EntitySnapshot right) {
        Map<String, RuntimeValue> leftProperties = properties(left);
        Map<String, RuntimeValue> rightProperties = properties(right);
        LinkedHashSet<String> names = new LinkedHashSet<>(leftProperties.keySet());
        names.addAll(rightProperties.keySet());
        for (String name : names.stream().sorted().toList()) {
            Optional<RuntimeValue> leftValue = Optional.ofNullable(leftProperties.get(name));
            Optional<RuntimeValue> rightValue = Optional.ofNullable(rightProperties.get(name));
            if (!leftValue.equals(rightValue)) {
                return Optional.of(new DeterminismDifference(
                        DeterminismDifferenceKind.PROPERTY,
                        Optional.of(left.id().value() + ':' + name), leftValue, rightValue));
            }
        }
        return Optional.empty();
    }

    private static <T> Optional<DeterminismDifference> listDifference(
            DeterminismDifferenceKind kind, String name, List<T> left, List<T> right) {
        int maximum = Math.max(left.size(), right.size());
        for (int index = 0; index < maximum; index++) {
            Optional<T> leftValue = index < left.size()
                    ? Optional.of(left.get(index)) : Optional.empty();
            Optional<T> rightValue = index < right.size()
                    ? Optional.of(right.get(index)) : Optional.empty();
            if (!leftValue.equals(rightValue)) {
                return Optional.of(new DeterminismDifference(kind,
                        Optional.of(name + ':' + index),
                        leftValue.map(value -> RuntimeValues.string(value.toString())),
                        rightValue.map(value -> RuntimeValues.string(value.toString()))));
            }
        }
        return Optional.empty();
    }

    private static EntitySnapshot comparableEntity(
            EntitySnapshot entity, SnapshotComparisonScope scope) {
        List<RuntimeValue.Field> properties = entity.properties().stream()
                .filter(property -> scope.properties().isEmpty()
                        || scope.properties().contains(property.name()))
                .filter(property -> !scope.excludedProperties().contains(property.name()))
                .toList();
        return new EntitySnapshot(
                entity.id(), entity.type(), entity.displayName(), properties, List.of());
    }

    private static Map<EntityId, EntitySnapshot> index(List<EntitySnapshot> entities) {
        LinkedHashMap<EntityId, EntitySnapshot> index = new LinkedHashMap<>();
        entities.stream().sorted(Comparator.comparing(EntitySnapshot::id))
                .forEach(value -> index.put(value.id(), value));
        return index;
    }

    private static Map<String, RuntimeValue> properties(EntitySnapshot entity) {
        LinkedHashMap<String, RuntimeValue> result = new LinkedHashMap<>();
        entity.properties().forEach(value -> result.put(value.name(), value.value()));
        return result;
    }

    private static Optional<RuntimeValue> entityIdentity(EntitySnapshot entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(RuntimeValues.string(
                entity.type().value() + ':' + entity.displayName().orElse("")));
    }

    private DeterminismResult inconclusive(DeterminismSpec spec, Counters counters,
            long executionNanos, String message) {
        return new DeterminismResult(
                DeterminismStatus.INCONCLUSIVE, message, spec.profile(), OptionalInt.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), bounds(spec, counters, executionNanos));
    }

    private static final int DIAGNOSTIC_LENGTH = 384;

    private String diagnostic(String category, Throwable failure) {
        String message = runtime.diagnostics().describe(category, failure);
        return message.length() <= DIAGNOSTIC_LENGTH
                ? message : message.substring(0, DIAGNOSTIC_LENGTH);
    }

    private DeterminismBounds bounds(
            DeterminismSpec spec, Counters counters, long executionNanos) {
        return new DeterminismBounds(counters.completedRepeats, spec.ticksPerRepeat(),
                counters.observedEntities, counters.observedFacts,
                counters.encodedBytes, executionNanos);
    }

    private void validate(DeterminismSpec spec) {
        if (spec.repeatCount() > limits.maximumRepeats()
                || spec.ticksPerRepeat() > limits.maximumTicksPerRepeat()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "determinism execution limit exceeded");
        }
        long configurationBytes = spec.configuration().toString()
                .getBytes(StandardCharsets.UTF_8).length;
        if (spec.configuration().fields().size() > limits.maximumFactsPerFrame()
                || configurationBytes > limits.maximumEncodedEvidenceBytes()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "determinism configuration limit exceeded");
        }
        if (!runtime.controls().available()) {
            throw new IllegalStateException(
                    "determinism comparison requires simulation control");
        }
    }

    private synchronized DeterminismOperation snapshot(
            String requestId, Evidence evidence, CommandLookup lookup) {
        return new DeterminismOperation(evidence.spec, requestId, lookup, evidence.result);
    }

    private DeterminismOperation evicted(
            String requestId, DeterminismSpec spec, CommandLookup lookup) {
        DeterminismResult result = new DeterminismResult(
                DeterminismStatus.INCONCLUSIVE, "determinism result evidence was evicted",
                spec.profile(), OptionalInt.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                new DeterminismBounds(
                        0, spec.ticksPerRepeat(), 0, 0, 0, limits.maximumExecutionNanos()));
        return new DeterminismOperation(spec, requestId, lookup, Optional.of(result));
    }

    private void makeRoom(CommandDispatch dispatch) {
        if (operations.size() < limits.retainedOperations()) {
            return;
        }
        String oldest = operations.keySet().iterator().next();
        CommandLookup lookup = dispatch.status(oldest);
        if (lookup.kind() != CommandLookup.Kind.FOUND
                || lookup.status().orElseThrow().state() == CommandState.QUEUED
                || lookup.status().orElseThrow().state() == CommandState.EXECUTING) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "determinism operation retention is full");
        }
        Evidence removed = operations.remove(oldest);
        evictedOperations.put(oldest, removed.spec);
        while (evictedOperations.size() > dispatch.limits().retainedRequestIds()) {
            evictedOperations.remove(evictedOperations.keySet().iterator().next());
        }
    }

    private long deadline(long executionNanos) {
        try {
            return Math.addExact(runtime.monotonicTimeNanos(), executionNanos);
        } catch (ArithmeticException failure) {
            return Long.MAX_VALUE;
        }
    }

    private boolean expired(long deadline) {
        return runtime.monotonicTimeNanos() >= deadline;
    }

    private static long requireTimeout(Duration timeout, long maximumNanos) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        final long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("timeout exceeds the supported range", failure);
        }
        if (nanos > maximumNanos) {
            throw new IllegalArgumentException("timeout exceeds the configured limit");
        }
        return nanos;
    }

    synchronized void close() {
        operations.clear();
        evictedOperations.clear();
    }

    private static final class Evidence {
        private final DeterminismSpec spec;
        private Optional<DeterminismResult> result = Optional.empty();

        private Evidence(DeterminismSpec spec) {
            this.spec = spec;
        }
    }

    private static final class Counters {
        private int completedRepeats;
        private long observedEntities;
        private long observedFacts;
        private long encodedBytes;
        private Optional<String> incompleteReason = Optional.empty();
    }

    private record RunEvidence(ExecutionEpochId epoch, List<FrameEvidence> frames) {}

    private record FrameEvidence(FrameId frameId, List<EntitySnapshot> entities,
            List<ComparableEvent> events, List<ComparableDecision> decisions,
            List<ComparableUi> ui) {}

    private record ComparableEvent(EventType type, Optional<EntityId> subject,
            Optional<EntityId> source, FactMetadata metadata,
            List<RuntimeValue.Field> attributes) {
        private static ComparableEvent from(RuntimeEvent value) {
            return new ComparableEvent(value.type(), value.subject(), value.source(),
                    value.metadata(), value.attributes());
        }
    }

    private record ComparableDecision(DecisionType type, EntityId actor,
            List<DecisionCandidate> candidates, Optional<EntityId> chosenCandidate,
            Optional<Reason> choiceReason, FactMetadata metadata,
            DecisionTrace.Completion completion) {
        private static ComparableDecision from(DecisionTrace value) {
            return new ComparableDecision(value.type(), value.actor(), value.candidates(),
                    value.chosenCandidate(), value.choiceReason(), value.metadata(),
                    value.completion());
        }
    }

    private record ComparableUi(String uiSessionId, Optional<String> uiFrameId,
            Optional<String> correlationToken) {
        private static ComparableUi from(UiFrameCorrelation value) {
            return new ComparableUi(
                    value.uiSessionId(), value.uiFrameId(), value.correlationToken());
        }
    }
}
