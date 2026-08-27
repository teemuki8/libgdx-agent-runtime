package io.github.teemuki8.libgdx.agent.runtime.box2d;

import com.badlogic.gdx.box2d.Box2d;
import com.badlogic.gdx.box2d.structs.b2ContactData;
import com.badlogic.gdx.box2d.structs.b2ContactEvents;
import com.badlogic.gdx.box2d.structs.b2ContactHitEvent;
import com.badlogic.gdx.box2d.structs.b2Manifold;
import com.badlogic.gdx.box2d.structs.b2ManifoldPoint;
import com.badlogic.gdx.box2d.structs.b2ShapeId;
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
import java.util.concurrent.atomic.AtomicInteger;

/** Application-owned bounded copy of post-step Box2D 3 contact events. */
public final class Box2dContacts implements AutoCloseable {
    private static final AtomicInteger OPEN_NATIVE_SCRATCH = new AtomicInteger();
    private final AgentRuntime runtime;
    private final Box2dInspection inspection;
    private final String worldId;
    private final Box2dContactLimits limits;
    private final Box2dContactPolicy policy;
    private final Thread ownerThread;
    private final Object historyLock = new Object();
    private final ArrayDeque<Box2dContactTick> history = new ArrayDeque<>();
    private final ArrayDeque<SimulationTickId> evictedTickIds = new ArrayDeque<>();
    private final TreeMap<Box2dContactRecord.Key, Box2dContactTick.ActiveContact> active =
            new TreeMap<>();
    private final EnumMap<Box2dContactTick.DiagnosticCode, Long> lifecycleDiagnostics =
            new EnumMap<>(Box2dContactTick.DiagnosticCode.class);
    private final EnumMap<Box2dContactTick.DiagnosticCode, Long> persistentDiagnostics =
            new EnumMap<>(Box2dContactTick.DiagnosticCode.class);
    private final b2ContactEvents contactEvents = new b2ContactEvents();
    private final b2ContactData.b2ContactDataPointer contactData;
    private EntityRegistration entityRegistration;
    private Capture capture;
    private SimulationTickId lastCapturedTick;
    private volatile Box2dContactTick latestTick;
    private Box2dContactTick pendingTick;
    private ExecutionEpochId observedEpoch;
    private long activeObserved;
    private long discardedEvictionMetadataThrough;
    private boolean closed;

    Box2dContacts(AgentRuntime runtime, Box2dInspection inspection, String worldId,
            Box2dContactLimits limits, Box2dContactPolicy policy, Thread ownerThread) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.inspection = Objects.requireNonNull(inspection, "inspection");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
        contactData = new b2ContactData.b2ContactDataPointer(
                limits.callbackRecordsPerTick(), false);
        OPEN_NATIVE_SCRATCH.incrementAndGet();
        observedEpoch = runtime.currentEpoch();
    }

    void registerEntity() {
        EntityId entityId = EntityId.of("box2d.contacts." + worldId);
        entityRegistration = runtime.entities().register(entityId, EntityType.of("box2d.contacts"),
                () -> worldId, this::declareEntity);
    }


    /**
     * Captures post-step event arrays from exactly one application-owned world step in the active
     * runtime tick. Disabled runtimes execute the step once and retain no contact evidence.
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
        capture = next;
        lastCapturedTick = activeTick.simulationTickId();
        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        try {
            worldStep.run();
            copyEvents(next);
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
            boolean knownEvicted = evictedTickIds.stream().anyMatch(value ->
                    value.value() >= fromTick && value.value() <= toTick);
            if (knownEvicted) {
                status = Box2dContactTickPage.RangeStatus.PARTIALLY_EVICTED;
            } else if (fromTick <= discardedEvictionMetadataThrough) {
                status = Box2dContactTickPage.RangeStatus.EVICTION_UNKNOWN;
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

    /** Releases bounded history and owned native scratch without destroying the world. */
    @Override public void close() {
        requireOwner();
        closeInternal(true);
    }

    void closeFromInspection() {
        requireOwner();
        closeInternal(false);
    }

    boolean nativeScratchFreed() {
        return contactData.isFreed();
    }

    static int openNativeScratchCount() {
        return OPEN_NATIVE_SCRATCH.get();
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
        active.clear();
        activeObserved = 0;
        latestTick = null;
        synchronized (historyLock) {
            history.clear();
            pendingTick = null;
            evictedTickIds.clear();
        }
        persistentDiagnostics.clear();
        if (!contactData.isFreed()) {
            contactData.free();
            OPEN_NATIVE_SCRATCH.decrementAndGet();
        }
        if (entityRegistration != null
                && runtime.status() != io.github.teemuki8.libgdx.agent.runtime.core.RuntimeStatus.CLOSED) {
            entityRegistration.close();
        }
        entityRegistration = null;
        if (unregister) {
            inspection.unregisterContacts(worldId, this);
        }
    }

    private void copyEvents(Capture current) {
        Box2d.b2World_GetContactEvents(inspection.worldId(worldId), contactEvents);
        for (int index = 0; index < contactEvents.beginCount(); index++) {
            var event = contactEvents.beginEvents().asStackElement(index);
            recordEndpoints(Box2dContactRecord.Phase.BEGIN,
                    event.shapeIdA(), event.shapeIdB(), current);
        }
        for (int index = 0; index < contactEvents.hitCount(); index++) {
            recordHit(contactEvents.hitEvents().asStackElement(index), current);
        }
        for (int index = 0; index < contactEvents.endCount(); index++) {
            var event = contactEvents.endEvents().asStackElement(index);
            recordEndpoints(Box2dContactRecord.Phase.END,
                    event.shapeIdA(), event.shapeIdB(), current);
        }
    }

    private void recordEndpoints(Box2dContactRecord.Phase phase,
            b2ShapeId shapeA, b2ShapeId shapeB, Capture current) {
        Optional<Box2dInspection.ContactMapping> mapping =
                inspection.contactMapping(worldId, shapeA, shapeB);
        if (mapping.isEmpty()) {
            unmapped(current);
            return;
        }
        Box2dInspection.ContactMapping resolved = mapping.orElseThrow();
        updateActiveEndpoints(phase, resolved, current);
        if (!retained(phase)) {
            return;
        }
        append(current, new Box2dContactRecord(
                phase, resolved.key(), resolved.endpointA(), resolved.endpointB(),
                phase == Box2dContactRecord.Phase.BEGIN, true,
                Box2dContactRecord.Availability.ENDPOINTS_ONLY,
                List.of(), Optional.empty(), List.of(), Optional.empty(),
                nextOccurrence(current), List.of()));
    }

    private void recordHit(b2ContactHitEvent event, Capture current) {
        Optional<Box2dInspection.ContactMapping> mapping =
                inspection.contactMapping(worldId, event.shapeIdA(), event.shapeIdB());
        if (mapping.isEmpty()) {
            unmapped(current);
            return;
        }
        Box2dInspection.ContactMapping resolved = mapping.orElseThrow();
        Box2dVector point = new Box2dVector(event.point().x(), event.point().y());
        float normalSign = resolved.reversed() ? -1.0f : 1.0f;
        Box2dVector normal = new Box2dVector(
                normalSign * event.normal().x(), normalSign * event.normal().y());
        double totalNormalImpulse = maximumTotalNormalImpulse(
                event.shapeIdA(), resolved, current);
        List<Box2dContactRecord.Impulse> impulses = totalNormalImpulse > 0
                ? List.of(new Box2dContactRecord.Impulse(totalNormalImpulse, 0))
                : List.of();
        List<Box2dVector> points = limits.pointsPerContact() > 0
                ? List.of(point) : List.of();
        updateActiveHit(resolved, points, normal, impulses, current);
        if (!retained(Box2dContactRecord.Phase.POST_SOLVE)) {
            return;
        }
        append(current, new Box2dContactRecord(
                Box2dContactRecord.Phase.POST_SOLVE,
                resolved.key(), resolved.endpointA(), resolved.endpointB(), true, true,
                Box2dContactRecord.Availability.CURRENT_MANIFOLD_AND_IMPULSES,
                points, Optional.of(normal), impulses, Optional.empty(),
                nextOccurrence(current), List.of()));
    }

    private double maximumTotalNormalImpulse(b2ShapeId queryShape,
            Box2dInspection.ContactMapping expected, Capture current) {
        int nativeCapacity = Box2d.b2Shape_GetContactCapacity(queryShape);
        int capacity = Math.min(nativeCapacity, limits.callbackRecordsPerTick());
        if (nativeCapacity > capacity) {
            current.diagnostic(Box2dContactTick.DiagnosticCode.IMPULSE_LIMIT_REACHED, 1);
        }
        if (capacity == 0) {
            return 0;
        }
        int count = Box2d.b2Shape_GetContactData(queryShape, contactData, capacity);
        float maximum = 0;
        for (int contactIndex = 0; contactIndex < count; contactIndex++) {
            b2ContactData data = contactData.asStackElement(contactIndex);
            Optional<Box2dInspection.ContactMapping> mapping =
                    inspection.contactMapping(worldId, data.shapeIdA(), data.shapeIdB());
            if (mapping.isEmpty() || !mapping.orElseThrow().key().equals(expected.key())) {
                continue;
            }
            b2Manifold manifold = data.manifold();
            int retainedPoints = Math.min(manifold.pointCount(), limits.impulsesPerContact());
            if (manifold.pointCount() > retainedPoints) {
                current.diagnostic(Box2dContactTick.DiagnosticCode.IMPULSE_LIMIT_REACHED, 1);
            }
            for (int pointIndex = 0; pointIndex < retainedPoints; pointIndex++) {
                b2ManifoldPoint point = manifold.points().asStackElement(pointIndex);
                maximum = Math.max(maximum, point.totalNormalImpulse());
            }
        }
        return maximum;
    }

    private void updateActiveEndpoints(Box2dContactRecord.Phase phase,
            Box2dInspection.ContactMapping mapping, Capture current) {
        if (phase == Box2dContactRecord.Phase.END) {
            if (active.remove(mapping.key()) != null && activeObserved > 0) {
                activeObserved--;
            }
            return;
        }
        Box2dContactTick.ActiveContact value = new Box2dContactTick.ActiveContact(
                mapping.key(), mapping.endpointA(), mapping.endpointB(),
                true, true, List.of(), Optional.empty(), List.of(), List.of());
        retainActive(mapping.key(), value, current);
    }

    private void updateActiveHit(Box2dInspection.ContactMapping mapping,
            List<Box2dVector> points, Box2dVector normal,
            List<Box2dContactRecord.Impulse> impulses, Capture current) {
        Box2dContactTick.ActiveContact value = new Box2dContactTick.ActiveContact(
                mapping.key(), mapping.endpointA(), mapping.endpointB(),
                true, true, points, Optional.of(normal), impulses, List.of());
        retainActive(mapping.key(), value, current);
    }

    private void retainActive(Box2dContactRecord.Key key,
            Box2dContactTick.ActiveContact value, Capture current) {
        if (active.containsKey(key)) {
            active.put(key, value);
            return;
        }
        activeObserved = saturatingIncrement(activeObserved);
        if (active.size() < limits.activeContactsPerTick()) {
            active.put(key, value);
        } else if (key.compareTo(active.lastKey()) < 0) {
            active.pollLastEntry();
            active.put(key, value);
            current.diagnostic(Box2dContactTick.DiagnosticCode.ACTIVE_LIMIT_REACHED, 1);
        } else {
            current.diagnostic(Box2dContactTick.DiagnosticCode.ACTIVE_LIMIT_REACHED, 1);
        }
    }

    private void append(Capture current, Box2dContactRecord record) {
        current.observedRecords = saturatingIncrement(current.observedRecords);
        if (current.records.size() < limits.callbackRecordsPerTick()) {
            current.records.add(record);
        } else {
            current.diagnostic(Box2dContactTick.DiagnosticCode.RECORD_LIMIT_REACHED, 1);
        }
    }

    private void unmapped(Capture current) {
        current.unmapped = saturatingIncrement(current.unmapped);
        current.diagnostic(Box2dContactTick.DiagnosticCode.UNMAPPED_ENDPOINT, 1);
        persistentDiagnostic(Box2dContactTick.DiagnosticCode.UNMAPPED_ENDPOINT, 1);
    }

    private static long nextOccurrence(Capture current) {
        current.occurrence = saturatingIncrement(current.occurrence);
        return current.occurrence;
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
                recordEvicted(pendingTick.simulationTickId());
                pendingTick = null;
            }
            history.forEach(value -> recordEvicted(value.simulationTickId()));
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
            recordEvicted(evicted.simulationTickId());
        }
        history.addLast(settled);
        if (latestTick != null
                && latestTick.simulationTickId().equals(settled.simulationTickId())) {
            latestTick = settled;
        }
        pendingTick = null;
    }

    private void recordEvicted(SimulationTickId tickId) {
        if (evictedTickIds.size() == limits.retainedContactTicks()) {
            SimulationTickId discarded = evictedTickIds.removeFirst();
            discardedEvictionMetadataThrough = Math.max(
                    discardedEvictionMetadataThrough, discarded.value());
        }
        evictedTickIds.addLast(tickId);
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
