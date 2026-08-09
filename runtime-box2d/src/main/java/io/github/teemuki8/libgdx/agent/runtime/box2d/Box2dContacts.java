package io.github.teemuki8.libgdx.agent.runtime.box2d;

import com.badlogic.gdx.physics.box2d.Contact;
import com.badlogic.gdx.physics.box2d.ContactImpulse;
import com.badlogic.gdx.physics.box2d.ContactListener;
import com.badlogic.gdx.physics.box2d.Manifold;
import io.github.teemuki8.libgdx.agent.runtime.core.ActiveSimulationTick;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityInspector;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityRegistration;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.ExecutionEpochId;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTickId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTickQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTickRangeStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.Truncation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Application-owned bounded capture of explicitly registered Box2D contact callbacks. */
public final class Box2dContacts implements AutoCloseable {
    private final AgentRuntime runtime;
    private final Box2dInspection inspection;
    private final String worldId;
    private final Box2dContactLimits limits;
    private final Box2dContactPolicy policy;
    private final Thread ownerThread;
    private final ContactListener listener = new EvidenceListener();
    private final Object historyLock = new Object();
    private final ArrayDeque<Box2dContactTick> history = new ArrayDeque<>();
    private final TreeMap<Box2dContactRecord.Key, Box2dContactTick.ActiveContact> active =
            new TreeMap<>();
    private final EnumMap<Box2dContactTick.DiagnosticCode, Long> lifecycleDiagnostics =
            new EnumMap<>(Box2dContactTick.DiagnosticCode.class);
    private final EnumMap<Box2dContactTick.DiagnosticCode, Long> persistentDiagnostics =
            new EnumMap<>(Box2dContactTick.DiagnosticCode.class);
    private ContactListener applicationListener;
    private ContactListener composedListener;
    private EntityRegistration entityRegistration;
    private Capture capture;
    private SimulationTickId lastCapturedTick;
    private Box2dContactTick latestTick;
    private Box2dContactTick pendingTick;
    private ExecutionEpochId observedEpoch;
    private long activeObserved;
    private long evictedThroughTickId;
    private boolean closed;

    Box2dContacts(AgentRuntime runtime, Box2dInspection inspection, String worldId,
            Box2dContactLimits limits, Box2dContactPolicy policy, Thread ownerThread) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.inspection = Objects.requireNonNull(inspection, "inspection");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
        observedEpoch = runtime.currentEpoch();
    }

    void registerEntity() {
        EntityId entityId = EntityId.of("box2d.contacts." + worldId);
        entityRegistration = runtime.entities().register(entityId, EntityType.of("box2d.contacts"),
                () -> worldId, this::declareEntity);
    }

    /** Returns the evidence listener without installing it on the application-owned world. */
    public ContactListener listener() {
        requireOpen();
        return listener;
    }

    /** Returns one evidence-first, application-second listener composition. */
    public ContactListener compose(ContactListener application) {
        requireOwnerOpen();
        Objects.requireNonNull(application, "applicationListener");
        if (application == listener) {
            throw new IllegalArgumentException("cannot compose the evidence listener with itself");
        }
        if (composedListener != null) {
            throw new IllegalStateException("Box2D contact listener is already composed");
        }
        applicationListener = application;
        composedListener = new ComposedListener();
        return composedListener;
    }

    /**
     * Captures callbacks from exactly one application-owned world step in the active runtime tick.
     * The original unchecked step or listener failure is rethrown after bounded finalization.
     * Disabled runtimes execute the step once and retain no contact evidence.
     */
    public void captureStep(Runnable worldStep) {
        requireOwnerOpen();
        Objects.requireNonNull(worldStep, "worldStep");
        if (!runtime.configuration().enabled()) {
            worldStep.run();
            return;
        }
        settlePending();
        ActiveSimulationTick activeTick = runtime.simulation().activeTick()
                .orElseThrow(() -> new IllegalStateException(
                        "Box2D contact capture requires an active simulation tick"));
        if (capture != null || activeTick.simulationTickId().equals(lastCapturedTick)) {
            throw new IllegalStateException(
                    "Box2D contact capture allows one world step per simulation tick");
        }
        Capture next = new Capture(activeTick);
        lifecycleDiagnostics.remove(Box2dContactTick.DiagnosticCode.EPOCH_RESET);
        lifecycleDiagnostics.forEach(next::diagnostic);
        lifecycleDiagnostics.clear();
        persistentDiagnostics.forEach(next::diagnostic);
        long afterCloseCallbacks = inspection.takeCallbacksAfterClose(worldId);
        if (afterCloseCallbacks > 0) {
            next.diagnostic(Box2dContactTick.DiagnosticCode.CALLBACK_AFTER_CLOSE,
                    afterCloseCallbacks);
            persistentDiagnostic(Box2dContactTick.DiagnosticCode.CALLBACK_AFTER_CLOSE,
                    afterCloseCallbacks);
        }
        capture = next;
        lastCapturedTick = activeTick.simulationTickId();
        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        try {
            worldStep.run();
        } catch (RuntimeException failure) {
            next.diagnostic(Box2dContactTick.DiagnosticCode.STEP_FAILED, 1);
            persistentDiagnostic(Box2dContactTick.DiagnosticCode.STEP_FAILED, 1);
            runtimeFailure = failure;
        } catch (Error failure) {
            next.diagnostic(Box2dContactTick.DiagnosticCode.STEP_FAILED, 1);
            persistentDiagnostic(Box2dContactTick.DiagnosticCode.STEP_FAILED, 1);
            errorFailure = failure;
        }
        try {
            finish(next);
        } catch (RuntimeException finalizationFailure) {
            if (runtimeFailure == null && errorFailure == null) {
                runtimeFailure = finalizationFailure;
            }
        } catch (Error finalizationFailure) {
            if (runtimeFailure == null && errorFailure == null) {
                errorFailure = finalizationFailure;
            }
        } finally {
            capture = null;
        }
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
        if (errorFailure != null) {
            throw errorFailure;
        }
    }

    /**
     * Returns a bounded inclusive page of frame-confirmed contact ticks, safe for concurrent
     * readers. Pending evidence is retained only after its simulation tick confirms the frame.
     */
    public Box2dContactTickPage ticks(long fromTick, long toTick, int limit) {
        requireOpen();
        if (fromTick <= 0 || toTick < fromTick || limit <= 0
                || limit > limits.queryPageSize()) {
            throw new IllegalArgumentException("invalid Box2D contact tick query");
        }
        synchronized (historyLock) {
            settlePendingLocked();
            Optional<SimulationTickId> oldest = Optional.ofNullable(history.peekFirst())
                    .map(Box2dContactTick::simulationTickId);
            Optional<SimulationTickId> newest = Optional.ofNullable(history.peekLast())
                    .map(Box2dContactTick::simulationTickId);
            ArrayList<Box2dContactTick> page = new ArrayList<>(Math.min(limit, history.size()));
            long matchingCount = 0;
            for (Box2dContactTick tick : history) {
                long tickId = tick.simulationTickId().value();
                if (tickId >= fromTick && tickId <= toTick) {
                    matchingCount = saturatingIncrement(matchingCount);
                    if (page.size() < limit) {
                        page.add(tick);
                    }
                }
            }
            boolean hasMore = matchingCount > limit;
            long requestedTicks = toTick - fromTick + 1;
            boolean missingTick = matchingCount != requestedTicks;
            Box2dContactTickPage.RangeStatus status;
            if (fromTick <= evictedThroughTickId
                    || oldest.isPresent() && fromTick < oldest.orElseThrow().value()) {
                status = Box2dContactTickPage.RangeStatus.PARTIALLY_EVICTED;
            } else if (oldest.isEmpty()) {
                status = Box2dContactTickPage.RangeStatus.NOT_YET_CAPTURED;
            } else if (missingTick) {
                status = Box2dContactTickPage.RangeStatus.NOT_YET_CAPTURED;
            } else if (hasMore) {
                status = Box2dContactTickPage.RangeStatus.PAGINATED;
            } else {
                status = Box2dContactTickPage.RangeStatus.COMPLETE;
            }
            return new Box2dContactTickPage(page, hasMore, status, oldest, newest);
        }
    }

    /** Releases listener references and bounded history without disposing native objects. */
    @Override public void close() {
        requireOwner();
        closeInternal(true);
    }

    void closeFromInspection() {
        requireOwner();
        closeInternal(false);
    }

    void worldChanged() {
        requireOwnerOpen();
        resetCurrentEvidence(Box2dContactTick.DiagnosticCode.WORLD_REBOUND);
    }

    void fixtureChanged(String fixtureId) {
        requireOwnerOpen();
        Objects.requireNonNull(fixtureId, "fixtureId");
        latestTick = null;
        int retainedBefore = active.size();
        active.entrySet().removeIf(entry -> entry.getKey().fixtureAId().equals(fixtureId)
                || entry.getKey().fixtureBId().equals(fixtureId));
        activeObserved = Math.max(0, activeObserved - (retainedBefore - active.size()));
        lifecycleDiagnostics.clear();
        lifecycleDiagnostics.put(Box2dContactTick.DiagnosticCode.ENDPOINT_CHANGED, 1L);
    }

    private void resetCurrentEvidence(Box2dContactTick.DiagnosticCode code) {
        latestTick = null;
        active.clear();
        activeObserved = 0;
        persistentDiagnostics.clear();
        lifecycleDiagnostics.clear();
        lifecycleDiagnostics.put(code, 1L);
    }

    private void closeInternal(boolean unregister) {
        if (closed) {
            return;
        }
        if (capture != null) {
            throw new IllegalStateException("cannot close Box2D contacts during world-step capture");
        }
        if (runtime.status()
                != io.github.teemuki8.libgdx.agent.runtime.core.RuntimeStatus.CLOSED) {
            runtime.entities().requireProviderMutationAllowed();
        }
        closed = true;
        applicationListener = null;
        composedListener = null;
        active.clear();
        activeObserved = 0;
        latestTick = null;
        synchronized (historyLock) {
            history.clear();
            pendingTick = null;
        }
        persistentDiagnostics.clear();
        if (entityRegistration != null
                && runtime.status() != io.github.teemuki8.libgdx.agent.runtime.core.RuntimeStatus.CLOSED) {
            entityRegistration.close();
        }
        entityRegistration = null;
        if (unregister) {
            inspection.unregisterContacts(worldId, this);
        }
    }

    private void callback(Box2dContactRecord.Phase phase, Contact contact,
            Manifold oldManifold, ContactImpulse impulse) {
        requireOwner();
        if (!runtime.configuration().enabled()) {
            return;
        }
        if (closed) {
            inspection.callbackAfterContactsClosed(worldId);
            return;
        }
        Capture current = capture;
        if (current == null) {
            persistentDiagnostic(Box2dContactTick.DiagnosticCode.CALLBACK_OUTSIDE_TICK, 1);
            return;
        }
        Optional<Box2dInspection.ContactMapping> mapping = inspection.contactMapping(
                worldId, contact.getFixtureA(), contact.getChildIndexA(),
                contact.getFixtureB(), contact.getChildIndexB());
        if (mapping.isEmpty()) {
            current.unmapped = saturatingIncrement(current.unmapped);
            current.diagnostic(Box2dContactTick.DiagnosticCode.UNMAPPED_ENDPOINT, 1);
            persistentDiagnostic(Box2dContactTick.DiagnosticCode.UNMAPPED_ENDPOINT, 1);
            return;
        }
        Box2dInspection.ContactMapping resolved = mapping.orElseThrow();
        updateActive(phase, resolved, contact, oldManifold, impulse, current);
        if (!retained(phase)) {
            return;
        }
        current.observedRecords = saturatingIncrement(current.observedRecords);
        if (current.records.size() >= limits.callbackRecordsPerTick()) {
            current.diagnostic(Box2dContactTick.DiagnosticCode.RECORD_LIMIT_REACHED, 1);
            return;
        }
        current.records.add(copyRecord(phase, resolved, contact, oldManifold, impulse, current));
    }

    private Box2dContactRecord copyRecord(Box2dContactRecord.Phase phase,
            Box2dInspection.ContactMapping mapping, Contact contact, Manifold oldManifold,
            ContactImpulse impulse, Capture current) {
        long occurrence = saturatingIncrement(current.occurrence);
        current.occurrence = occurrence;
        List<Box2dVector> points = List.of();
        Optional<Box2dVector> normal = Optional.empty();
        List<Box2dContactRecord.Impulse> impulses = List.of();
        Optional<Box2dContactRecord.OldManifold> old = Optional.empty();
        ArrayList<Truncation> truncations = new ArrayList<>();
        Box2dContactRecord.Availability availability =
                Box2dContactRecord.Availability.ENDPOINTS_ONLY;
        if (phase == Box2dContactRecord.Phase.PRE_SOLVE) {
            Box2dContactCopies.CurrentManifold manifold = Box2dContactCopies.current(
                    contact, mapping.reversed(), limits.pointsPerContact());
            Box2dContactCopies.OldCopy oldCopy = Box2dContactCopies.oldManifold(
                    oldManifold, mapping.reversed(), limits.oldManifoldPointsPerContact());
            points = manifold.points();
            normal = manifold.normal();
            old = Optional.of(oldCopy.value());
            truncations.addAll(manifold.truncations());
            truncations.addAll(oldCopy.truncations());
            oldCopy.truncations().forEach(
                    truncation -> current.diagnostic(diagnosticFor(truncation), 1));
            availability = Box2dContactRecord.Availability.CURRENT_AND_OLD_MANIFOLD;
        } else if (phase == Box2dContactRecord.Phase.POST_SOLVE) {
            Box2dContactCopies.CurrentManifold manifold = Box2dContactCopies.current(
                    contact, mapping.reversed(), limits.pointsPerContact());
            Box2dContactCopies.ImpulseCopy impulseCopy = Box2dContactCopies.impulses(
                    impulse, mapping.reversed(), limits.impulsesPerContact());
            points = manifold.points();
            normal = manifold.normal();
            impulses = impulseCopy.values();
            truncations.addAll(manifold.truncations());
            truncations.addAll(impulseCopy.truncations());
            availability = Box2dContactRecord.Availability.CURRENT_MANIFOLD_AND_IMPULSES;
        }
        truncations.sort((left, right) -> left.dimension().compareTo(right.dimension()));
        return new Box2dContactRecord(phase, mapping.key(), mapping.endpointA(),
                mapping.endpointB(), contact.isTouching(), contact.isEnabled(), availability,
                points, normal, impulses, old, occurrence, truncations);
    }

    private void updateActive(Box2dContactRecord.Phase phase,
            Box2dInspection.ContactMapping mapping, Contact contact, Manifold oldManifold,
            ContactImpulse impulse, Capture current) {
        Box2dContactRecord.Key key = mapping.key();
        if (phase == Box2dContactRecord.Phase.END) {
            active.remove(key);
            if (activeObserved > 0) {
                activeObserved--;
            }
            return;
        }
        Box2dContactTick.ActiveContact value;
        if (phase == Box2dContactRecord.Phase.BEGIN) {
            activeObserved = saturatingIncrement(activeObserved);
            value = new Box2dContactTick.ActiveContact(key, mapping.endpointA(),
                    mapping.endpointB(), contact.isTouching(), contact.isEnabled(), List.of(),
                    Optional.empty(), List.of(), List.of());
        } else if (!active.containsKey(key)) {
            current.diagnostic(Box2dContactTick.DiagnosticCode.MISSING_CORRELATION, 1);
            persistentDiagnostic(Box2dContactTick.DiagnosticCode.MISSING_CORRELATION, 1);
            return;
        } else if (phase == Box2dContactRecord.Phase.PRE_SOLVE) {
            Box2dContactCopies.CurrentManifold manifold = Box2dContactCopies.current(
                    contact, mapping.reversed(), limits.pointsPerContact());
            manifold.truncations().forEach(
                    truncation -> current.diagnostic(diagnosticFor(truncation), 1));
            value = new Box2dContactTick.ActiveContact(key, mapping.endpointA(),
                    mapping.endpointB(), contact.isTouching(), contact.isEnabled(),
                    manifold.points(), manifold.normal(), List.of(), manifold.truncations());
        } else {
            Box2dContactCopies.CurrentManifold manifold = Box2dContactCopies.current(
                    contact, mapping.reversed(), limits.pointsPerContact());
            Box2dContactCopies.ImpulseCopy copiedImpulses = Box2dContactCopies.impulses(
                    impulse, mapping.reversed(), limits.impulsesPerContact());
            ArrayList<Truncation> truncations = new ArrayList<>(manifold.truncations());
            truncations.addAll(copiedImpulses.truncations());
            truncations.forEach(
                    truncation -> current.diagnostic(diagnosticFor(truncation), 1));
            truncations.sort((left, right) -> left.dimension().compareTo(right.dimension()));
            value = new Box2dContactTick.ActiveContact(key, mapping.endpointA(),
                    mapping.endpointB(), contact.isTouching(), contact.isEnabled(),
                    manifold.points(), manifold.normal(), copiedImpulses.values(), truncations);
        }
        if (active.containsKey(key)) {
            active.put(key, value);
        } else if (active.size() < limits.activeContactsPerTick()) {
            active.put(key, value);
        } else if (key.compareTo(active.lastKey()) < 0) {
            active.pollLastEntry();
            active.put(key, value);
            current.diagnostic(Box2dContactTick.DiagnosticCode.ACTIVE_LIMIT_REACHED, 1);
        } else {
            current.diagnostic(Box2dContactTick.DiagnosticCode.ACTIVE_LIMIT_REACHED, 1);
        }
    }

    private void finish(Capture completed) {
        completed.records.sort(Box2dContactRecord::compareTo);
        List<Box2dContactTick.ActiveContact> activeValues = List.copyOf(active.values());
        ArrayList<Truncation> truncations = new ArrayList<>();
        if (completed.observedRecords > completed.records.size()) {
            truncations.add(new Truncation("box2d.contact.records",
                    completed.observedRecords, completed.records.size(),
                    limits.callbackRecordsPerTick()));
        }
        if (activeObserved > activeValues.size()) {
            truncations.add(new Truncation("box2d.contact.active", activeObserved,
                    activeValues.size(), limits.activeContactsPerTick()));
        }
        truncations.sort((left, right) -> left.dimension().compareTo(right.dimension()));
        List<Box2dContactTick.Diagnostic> diagnostics = completed.diagnostics.entrySet().stream()
                .limit(limits.diagnosticsPerTick())
                .map(entry -> new Box2dContactTick.Diagnostic(entry.getKey(), entry.getValue()))
                .toList();
        if (completed.diagnostics.size() > diagnostics.size()) {
            truncations.add(new Truncation("box2d.contact.diagnostics",
                    completed.diagnostics.size(), diagnostics.size(), limits.diagnosticsPerTick()));
            truncations.sort((left, right) -> left.dimension().compareTo(right.dimension()));
        }
        boolean nestedTruncated = completed.records.stream()
                .anyMatch(value -> !value.truncations().isEmpty())
                || activeValues.stream().anyMatch(value -> !value.truncations().isEmpty());
        Box2dContactTick tick = new Box2dContactTick(
                completed.tick.simulationTickId(), completed.tick.executionEpochId(),
                completed.tick.epochTick(), completed.tick.runtimeFrameId(),
                completed.records, activeValues, completed.observedRecords,
                completed.records.size(), limits.callbackRecordsPerTick(), activeObserved,
                activeValues.size(), limits.activeContactsPerTick(), completed.unmapped,
                diagnostics, truncations, diagnostics.isEmpty() && truncations.isEmpty()
                        && completed.unmapped == 0 && !nestedTruncated);
        synchronized (historyLock) {
            pendingTick = tick;
        }
        latestTick = tick;
        tick.records().forEach(record -> runtime.emit(
                Box2dContactValues.event(worldId, tick, record)));
    }

    private void declareEntity(EntityInspector inspector) {
        inspector.property("worldId", () -> {
            prepareProviderCapture();
            var value = RuntimeValues.string(worldId);
            lifecycleDiagnostics.clear();
            return value;
        })
                .property("runtimeEntityId",
                        () -> RuntimeValues.string("box2d.contacts." + worldId))
                .property("policy", () -> Box2dContactValues.policy(policy))
                .property("limits", () -> Box2dContactValues.limits(limits))
                .property("latestTick", () -> Box2dContactValues.latestTick(latestTick))
                .property("records", () -> Box2dContactValues.records(
                        latestTick == null ? List.of() : latestTick.records()))
                .property("activeContacts", () -> {
                    prepareProviderCapture();
                    return Box2dContactValues.activeContacts(
                            latestTick == null ? List.of() : latestTick.activeContacts());
                })
                .property("callbackCounts", () -> Box2dContactValues.counts(
                        latestTick == null ? 0 : latestTick.callbackRecordsObserved(),
                        latestTick == null ? 0 : latestTick.callbackRecordsRetained(),
                        limits.callbackRecordsPerTick()))
                .property("activeCounts", () -> Box2dContactValues.counts(
                        latestTick == null ? 0 : latestTick.activeContactsObserved(),
                        latestTick == null ? 0 : latestTick.activeContactsRetained(),
                        limits.activeContactsPerTick()))
                .property("unmappedContacts", () -> latestTick == null
                        ? 0 : latestTick.unmappedContactsObserved())
                .property("diagnostics", () -> Box2dContactValues.diagnostics(
                        currentDiagnostics()))
                .property("truncations", () -> Box2dContactValues.truncations(
                        currentTruncations()))
                .property("complete", () -> latestTick != null && latestTick.complete());
    }

    private void prepareProviderCapture() {
        settlePending();
        ExecutionEpochId epoch = runtime.currentEpoch();
        if (epoch.equals(observedEpoch)) {
            return;
        }
        observedEpoch = epoch;
        latestTick = null;
        lastCapturedTick = null;
        active.clear();
        activeObserved = 0;
        persistentDiagnostics.clear();
        lifecycleDiagnostics.clear();
        lifecycleDiagnostics.put(Box2dContactTick.DiagnosticCode.EPOCH_RESET, 1L);
        synchronized (historyLock) {
            if (pendingTick != null) {
                evictedThroughTickId = Math.max(
                        evictedThroughTickId, pendingTick.simulationTickId().value());
                pendingTick = null;
            }
            if (!history.isEmpty()) {
                evictedThroughTickId = Math.max(evictedThroughTickId,
                        history.getLast().simulationTickId().value());
            }
            history.clear();
        }
    }

    private void settlePending() {
        synchronized (historyLock) {
            settlePendingLocked();
        }
    }

    private void settlePendingLocked() {
        if (pendingTick == null) {
            return;
        }
        var timeline = runtime.simulation().ticks(new SimulationTickQuery(
                pendingTick.executionEpochId(), pendingTick.epochTick(),
                pendingTick.epochTick(), 1));
        if (timeline.rangeStatus() == SimulationTickRangeStatus.NOT_YET_EXECUTED) {
            return;
        }
        Box2dContactTick candidate = pendingTick;
        boolean correlated = timeline.ticks().stream().anyMatch(value ->
                value.simulationTickId().equals(candidate.simulationTickId())
                        && value.resultingFrameId().filter(
                                candidate.runtimeFrameId()::equals).isPresent());
        Box2dContactTick settled = candidate;
        if (!correlated) {
            settled = missingCorrelation(settled);
        }
        if (history.size() == limits.retainedContactTicks()) {
            Box2dContactTick evicted = history.removeFirst();
            evictedThroughTickId = Math.max(
                    evictedThroughTickId, evicted.simulationTickId().value());
        }
        history.addLast(settled);
        if (latestTick != null
                && latestTick.simulationTickId().equals(settled.simulationTickId())) {
            latestTick = settled;
        }
        pendingTick = null;
    }

    private Box2dContactTick missingCorrelation(Box2dContactTick tick) {
        EnumMap<Box2dContactTick.DiagnosticCode, Long> values =
                new EnumMap<>(Box2dContactTick.DiagnosticCode.class);
        tick.diagnostics().forEach(value -> values.put(value.code(), value.observed()));
        values.merge(Box2dContactTick.DiagnosticCode.MISSING_CORRELATION,
                1L, Box2dContacts::saturatingAdd);
        List<Box2dContactTick.Diagnostic> diagnostics = values.entrySet().stream()
                .limit(limits.diagnosticsPerTick())
                .map(value -> new Box2dContactTick.Diagnostic(value.getKey(), value.getValue()))
                .toList();
        ArrayList<Truncation> truncations = new ArrayList<>(tick.truncations().stream()
                .filter(value -> !value.dimension().equals("box2d.contact.diagnostics"))
                .toList());
        if (values.size() > diagnostics.size()) {
            truncations.add(new Truncation("box2d.contact.diagnostics",
                    values.size(), diagnostics.size(), limits.diagnosticsPerTick()));
        }
        truncations.sort((left, right) -> left.dimension().compareTo(right.dimension()));
        return new Box2dContactTick(tick.simulationTickId(), tick.executionEpochId(),
                tick.epochTick(), tick.runtimeFrameId(), tick.records(), tick.activeContacts(),
                tick.callbackRecordsObserved(), tick.callbackRecordsRetained(),
                tick.callbackRecordLimit(), tick.activeContactsObserved(),
                tick.activeContactsRetained(), tick.activeContactLimit(),
                tick.unmappedContactsObserved(), diagnostics, truncations, false);
    }

    private void persistentDiagnostic(Box2dContactTick.DiagnosticCode code, long count) {
        persistentDiagnostics.merge(code, count, Box2dContacts::saturatingAdd);
    }

    private List<Box2dContactTick.Diagnostic> currentDiagnostics() {
        if (latestTick != null) {
            return latestTick.diagnostics();
        }
        return currentLifecycleDiagnostics().entrySet().stream()
                .limit(limits.diagnosticsPerTick())
                .map(entry -> new Box2dContactTick.Diagnostic(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<Truncation> currentTruncations() {
        if (latestTick != null) {
            return latestTick.truncations();
        }
        int observed = currentLifecycleDiagnostics().size();
        int retained = Math.min(observed, limits.diagnosticsPerTick());
        return observed > retained
                ? List.of(new Truncation("box2d.contact.diagnostics",
                        observed, retained, limits.diagnosticsPerTick()))
                : List.of();
    }

    private EnumMap<Box2dContactTick.DiagnosticCode, Long> currentLifecycleDiagnostics() {
        EnumMap<Box2dContactTick.DiagnosticCode, Long> values =
                new EnumMap<>(persistentDiagnostics);
        lifecycleDiagnostics.forEach((code, count) ->
                values.merge(code, count, Box2dContacts::saturatingAdd));
        return values;
    }

    private boolean retained(Box2dContactRecord.Phase phase) {
        return switch (phase) {
            case BEGIN -> policy.begin();
            case END -> policy.end();
            case PRE_SOLVE -> policy.preSolve();
            case POST_SOLVE -> policy.postSolve();
        };
    }

    private static Box2dContactTick.DiagnosticCode diagnosticFor(Truncation truncation) {
        return switch (truncation.dimension()) {
            case "box2d.contact.points" -> Box2dContactTick.DiagnosticCode.POINT_LIMIT_REACHED;
            case "box2d.contact.impulses" -> Box2dContactTick.DiagnosticCode.IMPULSE_LIMIT_REACHED;
            case "box2d.contact.oldManifoldPoints" ->
                    Box2dContactTick.DiagnosticCode.OLD_MANIFOLD_LIMIT_REACHED;
            default -> throw new IllegalArgumentException("unknown Box2D contact truncation");
        };
    }

    private void applicationFailure() {
        Capture current = capture;
        if (current != null) {
            current.diagnostic(Box2dContactTick.DiagnosticCode.APPLICATION_LISTENER_FAILED, 1);
        }
    }

    private void requireOwnerOpen() {
        requireOwner();
        requireOpen();
    }

    private void requireOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Box2D contacts require their application thread");
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Box2D contacts are closed");
        }
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? value : value + 1;
    }

    private static long saturatingAdd(long first, long second) {
        return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
    }

    private final class EvidenceListener implements ContactListener {
        @Override public void beginContact(Contact contact) {
            callback(Box2dContactRecord.Phase.BEGIN, contact, null, null);
        }

        @Override public void endContact(Contact contact) {
            callback(Box2dContactRecord.Phase.END, contact, null, null);
        }

        @Override public void preSolve(Contact contact, Manifold oldManifold) {
            callback(Box2dContactRecord.Phase.PRE_SOLVE, contact, oldManifold, null);
        }

        @Override public void postSolve(Contact contact, ContactImpulse impulse) {
            callback(Box2dContactRecord.Phase.POST_SOLVE, contact, null, impulse);
        }
    }

    private final class ComposedListener implements ContactListener {
        @Override public void beginContact(Contact contact) {
            listener.beginContact(contact);
            application(value -> value.beginContact(contact));
        }

        @Override public void endContact(Contact contact) {
            listener.endContact(contact);
            application(value -> value.endContact(contact));
        }

        @Override public void preSolve(Contact contact, Manifold oldManifold) {
            listener.preSolve(contact, oldManifold);
            application(value -> value.preSolve(contact, oldManifold));
        }

        @Override public void postSolve(Contact contact, ContactImpulse impulse) {
            listener.postSolve(contact, impulse);
            application(value -> value.postSolve(contact, impulse));
        }

        private void application(java.util.function.Consumer<ContactListener> callback) {
            ContactListener application = applicationListener;
            if (application == null) {
                return;
            }
            try {
                callback.accept(application);
            } catch (RuntimeException | Error failure) {
                applicationFailure();
                throw failure;
            }
        }
    }

    private static final class Capture {
        private final ActiveSimulationTick tick;
        private final ArrayList<Box2dContactRecord> records = new ArrayList<>();
        private final EnumMap<Box2dContactTick.DiagnosticCode, Long> diagnostics =
                new EnumMap<>(Box2dContactTick.DiagnosticCode.class);
        private long observedRecords;
        private long unmapped;
        private long occurrence;

        Capture(ActiveSimulationTick tick) {
            this.tick = tick;
        }

        void diagnostic(Box2dContactTick.DiagnosticCode code, long count) {
            diagnostics.merge(code, count, Box2dContacts::saturatingAdd);
        }
    }
}
