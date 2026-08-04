package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Optional bounded coordinator for application-owned simulation control. */
public final class SimulationControlRegistry {
    private final AgentRuntime runtime;
    private final ControlLimits limits;
    private final LinkedHashMap<String, Evidence> operations = new LinkedHashMap<>();
    private SimulationControllerSpec controller;
    private LinkedHashMap<String, SimulationControllerSpec.Condition> conditions =
            new LinkedHashMap<>();
    private boolean paused;
    private long currentTick;

    SimulationControlRegistry(AgentRuntime runtime, ControlLimits limits) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Registers the one explicit application-owned simulation controller. */
    public synchronized void register(SimulationControllerSpec spec) {
        runtime.requireControlRegistration();
        Objects.requireNonNull(spec, "spec");
        if (controller != null) {
            throw new IllegalStateException("a simulation controller is already registered");
        }
        if (spec.conditions().size() > limits.registeredConditions()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "condition registration limit reached");
        }
        LinkedHashMap<String, SimulationControllerSpec.Condition> index = new LinkedHashMap<>();
        for (SimulationControllerSpec.Condition condition : spec.conditions()) {
            if (index.putIfAbsent(condition.id(), condition) != null) {
                throw new IllegalArgumentException("condition id is already registered");
            }
        }
        controller = spec;
        conditions = index;
    }

    /** Reports whether application code registered a controller. */
    public synchronized boolean available() {
        return controller != null;
    }

    /** Reports the last successfully applied pause state. */
    public synchronized boolean paused() {
        return paused;
    }

    /** Returns registered semantic conditions in stable registration order. */
    public synchronized List<ControlConditionDescriptor> conditions() {
        return conditions.values().stream().map(condition ->
                new ControlConditionDescriptor(condition.id(), condition.description())).toList();
    }

    /** Returns configured hard control bounds. */
    public ControlLimits limits() {
        return limits;
    }

    /** Returns the number of successfully completed application-defined controlled ticks. */
    public synchronized long currentTick() {
        return currentTick;
    }

    /** Returns immutable discoverable control state and condition metadata. */
    public synchronized SimulationControlDescriptor descriptor() {
        return new SimulationControlDescriptor(
                controller != null, paused, conditions(), limits);
    }

    /** Submits or polls one idempotently correlated pause or resume operation. */
    public ControlOperation control(boolean pause, String requestId, Duration timeout) {
        SimulationControllerSpec spec = requireController();
        CommandDispatch dispatch = requireDispatch();
        requireTimeout(timeout, dispatch.limits().maximumTimeoutNanos());
        ControlOperation.Kind kind = pause ? ControlOperation.Kind.PAUSE : ControlOperation.Kind.RESUME;
        Signature signature = new Signature(kind, 0, 0, Optional.empty(), Optional.empty(), 0);
        Evidence evidence = evidence(requestId, signature);
        if (evidence.submitted) {
            return snapshot(evidence, dispatch.status(requestId));
        }
        evidence.submitted = true;
        CommandLookup lookup = dispatch.submit(requestId, timeout, () -> {
            try {
                (pause ? spec.pause() : spec.resume()).run();
                synchronized (this) {
                    paused = pause;
                    evidence.paused = pause;
                    evidence.stopReason = ControlStopReason.COMPLETED;
                }
            } catch (RuntimeException | Error failure) {
                synchronized (this) {
                    evidence.stopReason = ControlStopReason.CALLBACK_FAILED;
                }
                throw failure;
            }
        });
        return snapshot(evidence, lookup);
    }

    /** Submits or polls an exact bounded tick advance while paused. */
    public ControlOperation advance(String requestId, int ticks, long deltaNanos, Duration timeout) {
        validateTicks(ticks, deltaNanos);
        Signature signature = new Signature(ControlOperation.Kind.ADVANCE, ticks, deltaNanos,
                Optional.empty(), Optional.empty(), 0);
        return tickOperation(requestId, signature, timeout);
    }

    /** Advances until a registered condition is true or the hard tick bound is reached. */
    public ControlOperation waitForCondition(String requestId, String conditionId, int maximumTicks,
            long deltaNanos, Duration timeout) {
        validateTicks(maximumTicks, deltaNanos);
        IdentifierSupport.validate(conditionId, "condition id");
        synchronized (this) {
            if (!conditions.containsKey(conditionId)) {
                throw new IllegalArgumentException("unknown control condition");
            }
        }
        Signature signature = new Signature(ControlOperation.Kind.WAIT, maximumTicks, deltaNanos,
                Optional.of(conditionId), Optional.empty(), 0);
        return tickOperation(requestId, signature, timeout);
    }

    /** Advances until a closed assertion passes or the hard tick bound is reached. */
    public ControlOperation waitForAssertion(String requestId, RuntimeAssertion assertion,
            int maximumTicks, long deltaNanos, int evidenceLimit, Duration timeout) {
        validateTicks(maximumTicks, deltaNanos);
        Objects.requireNonNull(assertion, "assertion");
        if (evidenceLimit <= 0 || evidenceLimit > AssertionScope.MAX_EVIDENCE) {
            throw new IllegalArgumentException("assertion evidence limit is outside the supported range");
        }
        Signature signature = new Signature(ControlOperation.Kind.WAIT, maximumTicks, deltaNanos,
                Optional.empty(), Optional.of(assertion), evidenceLimit);
        return tickOperation(requestId, signature, timeout);
    }

    private ControlOperation tickOperation(
            String requestId, Signature signature, Duration timeout) {
        SimulationControllerSpec spec = requireController();
        CommandDispatch dispatch = requireDispatch();
        requireTimeout(timeout, dispatch.limits().maximumTimeoutNanos());
        Evidence evidence = evidence(requestId, signature);
        if (evidence.submitted) {
            return snapshot(evidence, dispatch.status(requestId));
        }
        evidence.submitted = true;
        long deadline = deadline(timeout);
        CommandLookup lookup = dispatch.submit(requestId, timeout, () -> {
            requirePausedDuringExecution(evidence);
            for (int index = 0; index < signature.ticks; index++) {
                if (runtime.monotonicTimeNanos() >= deadline) {
                    synchronized (this) {
                        evidence.stopReason = ControlStopReason.TIMED_OUT;
                    }
                    return;
                }
                FrameId expected = runtime.latestFrame().map(frame ->
                        new FrameId(Math.addExact(frame.frameId().value(), 1))).orElse(new FrameId(0));
                long tick;
                synchronized (this) {
                    tick = Math.addExact(currentTick, 1);
                }
                try {
                    runtime.frame(signature.deltaNanos, () -> {
                        runtime.inputs().executeTick(tick, runtime.currentEpoch());
                        spec.tick().accept(signature.deltaNanos);
                    });
                    runtime.inputs().completeTick(tick, expected);
                } catch (RuntimeException | Error failure) {
                    if (runtime.frame(expected).isPresent()) {
                        runtime.inputs().completeTick(tick, expected);
                    } else {
                        runtime.inputs().failTick(tick);
                    }
                    synchronized (this) {
                        evidence.stopReason = ControlStopReason.CALLBACK_FAILED;
                    }
                    throw failure;
                }
                synchronized (this) {
                    currentTick = tick;
                    evidence.completedTicks++;
                    if (evidence.firstFrameId.isEmpty()) {
                        evidence.firstFrameId = Optional.of(expected);
                    }
                    evidence.finalFrameId = Optional.of(expected);
                }
                runtime.recordings().recordTick(
                        tick, signature.deltaNanos, runtime.currentEpoch(), expected);
                if (satisfied(evidence, signature)) {
                    return;
                }
            }
            synchronized (this) {
                evidence.stopReason = signature.kind == ControlOperation.Kind.WAIT
                        ? ControlStopReason.TICK_LIMIT : ControlStopReason.COMPLETED;
            }
        });
        return snapshot(evidence, lookup);
    }

    boolean pauseForDeterminism() {
        SimulationControllerSpec spec = requireController();
        synchronized (this) {
            if (paused) {
                return true;
            }
        }
        spec.pause().run();
        synchronized (this) {
            paused = true;
        }
        return false;
    }

    void restorePauseAfterDeterminism(boolean previouslyPaused) {
        if (previouslyPaused) {
            return;
        }
        SimulationControllerSpec spec = requireController();
        spec.resume().run();
        synchronized (this) {
            paused = false;
        }
    }

    FrameSnapshot tickForDeterminism(long deltaNanos) {
        SimulationControllerSpec spec = requireController();
        long tick;
        synchronized (this) {
            if (!paused) {
                throw new AgentRuntimeException(
                        RuntimeErrorCode.INVALID_LIFECYCLE,
                        "determinism execution requires paused simulation");
            }
            tick = Math.addExact(currentTick, 1);
        }
        FrameId expected = runtime.latestFrame().map(frame ->
                new FrameId(Math.addExact(frame.frameId().value(), 1))).orElse(new FrameId(0));
        try {
            runtime.frame(deltaNanos, () -> {
                runtime.inputs().executeTick(tick, runtime.currentEpoch());
                spec.tick().accept(deltaNanos);
            });
            runtime.inputs().completeTick(tick, expected);
        } catch (RuntimeException | Error failure) {
            if (runtime.frame(expected).isPresent()) {
                runtime.inputs().completeTick(tick, expected);
            } else {
                runtime.inputs().failTick(tick);
            }
            throw failure;
        }
        synchronized (this) {
            currentTick = tick;
        }
        runtime.recordings().recordTick(
                tick, deltaNanos, runtime.currentEpoch(), expected);
        return runtime.frame(expected).orElseThrow();
    }

    private boolean satisfied(Evidence evidence, Signature signature) {
        if (signature.conditionId.isPresent()) {
            SimulationControllerSpec.Condition condition;
            synchronized (this) {
                condition = conditions.get(signature.conditionId.orElseThrow());
            }
            if (condition.predicate().getAsBoolean()) {
                synchronized (this) {
                    evidence.stopReason = ControlStopReason.CONDITION_SATISFIED;
                }
                return true;
            }
        }
        if (signature.assertion.isPresent()) {
            FrameSnapshot latest = runtime.latestFrame().orElseThrow();
            FrameId from = evidence.submittedFrameId.orElse(latest.frameId());
            AssertionResult result = runtime.assertions().evaluate(
                    signature.assertion.orElseThrow(),
                    new AssertionScope(latest.executionEpochId(),
                            new FrameRange(from, latest.frameId()), signature.evidenceLimit));
            synchronized (this) {
                evidence.assertionResult = Optional.of(result);
                if (result.status() == AssertionStatus.PASS) {
                    evidence.stopReason = ControlStopReason.ASSERTION_SATISFIED;
                    return true;
                }
            }
        }
        return false;
    }

    private synchronized Evidence evidence(String requestId, Signature signature) {
        IdentifierSupport.validate(requestId, "control request id");
        Evidence existing = operations.get(requestId);
        if (existing != null) {
            if (!existing.signature.equals(signature)) {
                throw new IllegalArgumentException("request id is bound to a different control operation");
            }
            return existing;
        }
        CommandDispatch dispatch = requireDispatch();
        if (dispatch.status(requestId).kind() != CommandLookup.Kind.UNKNOWN) {
            throw new IllegalArgumentException("request correlation evidence is no longer retained");
        }
        Evidence created = new Evidence(requestId, signature, paused,
                runtime.latestFrame().map(FrameSnapshot::frameId));
        operations.put(requestId, created);
        while (operations.size() > limits.retainedOperations()) {
            operations.remove(operations.keySet().iterator().next());
        }
        return created;
    }

    private synchronized ControlOperation snapshot(Evidence evidence, CommandLookup command) {
        ControlStopReason stopReason = evidence.stopReason;
        if (stopReason == ControlStopReason.PENDING && command.status().isPresent()) {
            stopReason = switch (command.status().orElseThrow().state()) {
                case TIMED_OUT -> ControlStopReason.TIMED_OUT;
                case FAILED -> ControlStopReason.CALLBACK_FAILED;
                case CANCELLED, REJECTED -> ControlStopReason.INVALID_STATE;
                default -> ControlStopReason.PENDING;
            };
        }
        return new ControlOperation(evidence.requestId, evidence.signature.kind, command,
                evidence.signature.ticks, evidence.completedTicks, evidence.firstFrameId,
                evidence.finalFrameId, stopReason, evidence.paused,
                evidence.signature.conditionId, evidence.assertionResult);
    }


    private synchronized void requirePausedDuringExecution(Evidence evidence) {
        if (!paused) {
            evidence.stopReason = ControlStopReason.INVALID_STATE;
            throw new IllegalStateException("simulation resumed before tick advancement");
        }
    }

    private synchronized SimulationControllerSpec requireController() {
        if (controller == null) {
            throw new IllegalStateException("simulation control is not registered");
        }
        return controller;
    }

    private CommandDispatch requireDispatch() {
        return runtime.commands().orElseThrow(() ->
                new IllegalStateException("simulation control requires application command dispatch"));
    }

    private void validateTicks(int ticks, long deltaNanos) {
        if (ticks <= 0 || ticks > limits.ticksPerOperation()) {
            throw new IllegalArgumentException("tick count is outside the configured limit");
        }
        if (deltaNanos < 0 || deltaNanos > limits.maximumDeltaNanos()) {
            throw new IllegalArgumentException("deltaNanos is outside the configured limit");
        }
    }

    private long deadline(Duration timeout) {
        try {
            return Math.addExact(runtime.monotonicTimeNanos(), timeout.toNanos());
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("timeout exceeds the supported range", failure);
        }
    }

    private static void requireTimeout(Duration timeout, long maximumNanos) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
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
    }

    private record Signature(ControlOperation.Kind kind, int ticks, long deltaNanos,
            Optional<String> conditionId, Optional<RuntimeAssertion> assertion, int evidenceLimit) {}

    private static final class Evidence {
        private final String requestId;
        private final Signature signature;
        private final Optional<FrameId> submittedFrameId;
        private int completedTicks;
        private Optional<FrameId> firstFrameId = Optional.empty();
        private Optional<FrameId> finalFrameId = Optional.empty();
        private ControlStopReason stopReason = ControlStopReason.PENDING;
        private boolean paused;
        private Optional<AssertionResult> assertionResult = Optional.empty();
        private boolean submitted;

        private Evidence(String requestId, Signature signature, boolean paused,
                Optional<FrameId> submittedFrameId) {
            this.requestId = requestId;
            this.signature = signature;
            this.paused = paused;
            this.submittedFrameId = submittedFrameId;
        }
    }
}
