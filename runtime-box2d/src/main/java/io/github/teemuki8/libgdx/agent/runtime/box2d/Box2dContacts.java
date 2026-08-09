package io.github.teemuki8.libgdx.agent.runtime.box2d;

import com.badlogic.gdx.physics.box2d.Contact;
import com.badlogic.gdx.physics.box2d.ContactImpulse;
import com.badlogic.gdx.physics.box2d.ContactListener;
import com.badlogic.gdx.physics.box2d.Manifold;
import io.github.teemuki8.libgdx.agent.runtime.core.ActiveSimulationTick;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTickId;
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
    private ContactListener applicationListener;
    private ContactListener composedListener;
    private Capture capture;
    private SimulationTickId lastCapturedTick;
    private long activeObserved;
    private long outsideCallbacks;
    private boolean closed;

    Box2dContacts(AgentRuntime runtime, Box2dInspection inspection, String worldId,
            Box2dContactLimits limits, Box2dContactPolicy policy, Thread ownerThread) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.inspection = Objects.requireNonNull(inspection, "inspection");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
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
     */
    public void captureStep(Runnable worldStep) {
        requireOwnerOpen();
        Objects.requireNonNull(worldStep, "worldStep");
        ActiveSimulationTick activeTick = runtime.simulation().activeTick()
                .orElseThrow(() -> new IllegalStateException(
                        "Box2D contact capture requires an active simulation tick"));
        if (capture != null || activeTick.simulationTickId().equals(lastCapturedTick)) {
            throw new IllegalStateException(
                    "Box2D contact capture allows one world step per simulation tick");
        }
        Capture next = new Capture(activeTick);
        if (outsideCallbacks > 0) {
            next.diagnostic(Box2dContactTick.DiagnosticCode.CALLBACK_OUTSIDE_TICK,
                    outsideCallbacks);
            outsideCallbacks = 0;
        }
        capture = next;
        lastCapturedTick = activeTick.simulationTickId();
        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        try {
            worldStep.run();
        } catch (RuntimeException failure) {
            next.diagnostic(Box2dContactTick.DiagnosticCode.STEP_FAILED, 1);
            runtimeFailure = failure;
        } catch (Error failure) {
            next.diagnostic(Box2dContactTick.DiagnosticCode.STEP_FAILED, 1);
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

    /** Returns a bounded inclusive page of completed contact ticks, safe for concurrent readers. */
    public Box2dContactTickPage ticks(long fromTick, long toTick, int limit) {
        requireOpen();
        if (fromTick <= 0 || toTick < fromTick || limit <= 0
                || limit > limits.queryPageSize()) {
            throw new IllegalArgumentException("invalid Box2D contact tick query");
        }
        synchronized (historyLock) {
            Optional<SimulationTickId> oldest = Optional.ofNullable(history.peekFirst())
                    .map(Box2dContactTick::simulationTickId);
            Optional<SimulationTickId> newest = Optional.ofNullable(history.peekLast())
                    .map(Box2dContactTick::simulationTickId);
            List<Box2dContactTick> matching = history.stream()
                    .filter(tick -> tick.simulationTickId().value() >= fromTick
                            && tick.simulationTickId().value() <= toTick)
                    .toList();
            boolean hasMore = matching.size() > limit;
            List<Box2dContactTick> page = matching.stream().limit(limit).toList();
            Box2dContactTickPage.RangeStatus status;
            if (oldest.isEmpty()) {
                status = Box2dContactTickPage.RangeStatus.NOT_YET_CAPTURED;
            } else if (fromTick < oldest.orElseThrow().value()) {
                status = Box2dContactTickPage.RangeStatus.PARTIALLY_EVICTED;
            } else if (hasMore) {
                status = Box2dContactTickPage.RangeStatus.PAGINATED;
            } else if (toTick > newest.orElseThrow().value()) {
                status = Box2dContactTickPage.RangeStatus.NOT_YET_CAPTURED;
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

    private void closeInternal(boolean unregister) {
        if (closed) {
            return;
        }
        if (capture != null) {
            throw new IllegalStateException("cannot close Box2D contacts during world-step capture");
        }
        closed = true;
        applicationListener = null;
        composedListener = null;
        active.clear();
        activeObserved = 0;
        synchronized (historyLock) {
            history.clear();
        }
        if (unregister) {
            inspection.unregisterContacts(worldId, this);
        }
    }

    private void callback(Box2dContactRecord.Phase phase, Contact contact,
            Manifold oldManifold, ContactImpulse impulse) {
        if (closed) {
            return;
        }
        Capture current = capture;
        if (current == null) {
            outsideCallbacks = saturatingIncrement(outsideCallbacks);
            return;
        }
        Optional<Box2dInspection.ContactMapping> mapping = inspection.contactMapping(
                worldId, contact.getFixtureA(), contact.getChildIndexA(),
                contact.getFixtureB(), contact.getChildIndexB());
        if (mapping.isEmpty()) {
            current.unmapped = saturatingIncrement(current.unmapped);
            current.diagnostic(Box2dContactTick.DiagnosticCode.UNMAPPED_ENDPOINT, 1);
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
        return new Box2dContactRecord(phase, mapping.key(), contact.isTouching(),
                contact.isEnabled(), availability, points, normal, impulses, old,
                occurrence, truncations);
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
            value = new Box2dContactTick.ActiveContact(key, contact.isTouching(),
                    contact.isEnabled(), List.of(), Optional.empty(), List.of(), List.of());
        } else if (!active.containsKey(key)) {
            current.diagnostic(Box2dContactTick.DiagnosticCode.MISSING_CORRELATION, 1);
            return;
        } else if (phase == Box2dContactRecord.Phase.PRE_SOLVE) {
            Box2dContactCopies.CurrentManifold manifold = Box2dContactCopies.current(
                    contact, mapping.reversed(), limits.pointsPerContact());
            manifold.truncations().forEach(
                    truncation -> current.diagnostic(diagnosticFor(truncation), 1));
            value = new Box2dContactTick.ActiveContact(key, contact.isTouching(),
                    contact.isEnabled(), manifold.points(), manifold.normal(), List.of(),
                    manifold.truncations());
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
            value = new Box2dContactTick.ActiveContact(key, contact.isTouching(),
                    contact.isEnabled(), manifold.points(), manifold.normal(),
                    copiedImpulses.values(), truncations);
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
        Box2dContactTick tick = new Box2dContactTick(
                completed.tick.simulationTickId(), completed.tick.executionEpochId(),
                completed.tick.epochTick(), completed.tick.runtimeFrameId(),
                completed.records, activeValues, completed.observedRecords,
                completed.records.size(), limits.callbackRecordsPerTick(), activeObserved,
                activeValues.size(), limits.activeContactsPerTick(), completed.unmapped,
                diagnostics, truncations, diagnostics.isEmpty() && truncations.isEmpty()
                        && completed.unmapped == 0);
        synchronized (historyLock) {
            if (history.size() == limits.retainedContactTicks()) {
                history.removeFirst();
            }
            history.addLast(tick);
        }
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
