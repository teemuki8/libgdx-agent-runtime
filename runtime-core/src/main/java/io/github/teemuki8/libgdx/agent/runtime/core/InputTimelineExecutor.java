package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Package-private aggregate executor for successful exact-tick input timelines. */
final class InputTimelineExecutor {
    private static final String COMPLETED_MESSAGE = "completed";
    private static final String NOT_EXECUTED_MESSAGE =
            InputTimelineCanonicalSize.PRE_EXECUTION_MESSAGE;

    private final AgentRuntime runtime;
    private final InputRegistry inputs;
    private final InputTimelineLimits limits;
    private final LinkedHashMap<String, Evidence> operations = new LinkedHashMap<>();
    private String activeParent;

    InputTimelineExecutor(
            AgentRuntime runtime, InputRegistry inputs, InputTimelineLimits limits) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    InputTimelineLimits limits() {
        return limits;
    }

    InputTimelineOperation execute(
            InputTimelineSpec spec, String requestId, Duration timeout) {
        Objects.requireNonNull(spec, "spec");
        IdentifierSupport.validate(requestId, "input timeline request id");
        long timeoutNanos = timeoutNanos(timeout);
        Signature signature = new Signature(spec, timeoutNanos);
        CommandDispatch dispatch = runtime.commands().orElseThrow(() ->
                new IllegalStateException(
                        "input timeline execution requires application command dispatch"));
        requireTimeoutWithinLimits(timeoutNanos, dispatch);

        Evidence existing;
        synchronized (this) {
            existing = operations.get(requestId);
            if (existing != null) {
                if (!existing.signature.equals(signature)) {
                    throw new IllegalArgumentException(
                            "request id is bound to a different input timeline");
                }
            } else if (activeParent != null) {
                throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                        "another input timeline is already reserved");
            }
        }
        if (existing != null) {
            return snapshot(existing, dispatch.status(requestId));
        }

        Preflight preflight = preflight(spec, requestId, dispatch);
        List<String> parentEvictions = planParentEvictions(dispatch);
        InputRegistry.TimelineReservation reservation = inputs.planTimelineReservation(
                spec, requestId, dispatch, preflight.startingControlledTick,
                preflight.startingExecutionEpochId, parentEvictions);
        Evidence evidence = new Evidence(requestId, signature, preflight);
        AdmissionState previous = admissionState();
        inputs.commitTimelineReservation(reservation);
        commitAdmission(parentEvictions, evidence);

        CommandLookup lookup;
        try {
            lookup = dispatch.submit(requestId, timeout, () -> run(evidence));
        } catch (RuntimeException | Error failure) {
            CommandLookup retained = dispatch.status(requestId);
            if (retained.status().filter(CommandStatus::outcomeKnown).isPresent()
                    || started(evidence)) {
                reconcileBeforeExecution(evidence, retained);
            } else {
                inputs.rollbackTimelineReservation(reservation);
                rollbackAdmission(previous);
            }
            throw failure;
        }
        return snapshot(evidence, lookup);
    }

    synchronized void close() {
        operations.clear();
        activeParent = null;
    }

    private Preflight preflight(
            InputTimelineSpec spec, String requestId, CommandDispatch dispatch) {
        if (dispatch.status(requestId).kind() != CommandLookup.Kind.UNKNOWN) {
            throw new IllegalArgumentException(
                    "request correlation evidence is no longer retained");
        }
        if (!runtime.controls().pauseStateKnown() || !runtime.controls().paused()) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                    "input timeline execution requires paused simulation");
        }
        if (!runtime.controls().acknowledgedTicksAvailable()) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                    "input timeline execution requires acknowledged simulation ticks");
        }
        SimulationState state = runtime.simulation().state();
        long fixedStepNanos = state.configuredFixedStepNanos().orElseThrow(() ->
                new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                        "input timeline execution requires a configured fixed step"));
        int maximumTicks = Math.min(limits.maximumTicks(), Math.min(
                runtime.controls().limits().ticksPerOperation(),
                inputs.limits().futureTicks()));
        int maximumTransitions = inputs.effectiveTimelineTransitions();
        if (spec.totalTicks() > maximumTicks) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "input timeline tick count exceeds the effective limit");
        }
        if (spec.transitions().size() > maximumTransitions) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "input timeline transition count exceeds the effective limit");
        }
        if (fixedStepNanos > runtime.controls().limits().maximumDeltaNanos()) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "input timeline fixed step exceeds the control delta limit");
        }
        long requestBytes = InputTimelineCanonicalSize.request(spec);
        long resultReservation = InputTimelineCanonicalSize.resultReservation(spec);
        if (requestBytes > limits.maximumEncodedEvidenceBytes()
                || resultReservation > limits.maximumEncodedEvidenceBytes()) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "input timeline canonical evidence exceeds the configured limit");
        }
        long startingControlledTick = runtime.controls().currentTick();
        try {
            Math.addExact(startingControlledTick, (long) spec.totalTicks());
        } catch (ArithmeticException failure) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "input timeline controlled tick range overflowed");
        }
        return new Preflight(runtime.currentEpoch(), startingControlledTick, fixedStepNanos,
                maximumTicks, maximumTransitions, resultReservation);
    }

    private void run(Evidence evidence) {
        CommandStatus parentStatus = runtime.commands().orElseThrow()
                .status(evidence.requestId).status().orElseThrow();
        synchronized (this) {
            evidence.started = true;
        }
        ExecutionEpochId startingEpoch = runtime.currentEpoch();
        long startingControlledTick = runtime.controls().currentTick();
        long fixedStepNanos = requireExecutionState(startingEpoch,
                evidence.preflight.fixedStepNanos);
        long startingEpochTick = runtime.simulation().state().attemptedEpochTicks();
        inputs.stageTimeline(evidence.signature.spec, evidence.requestId,
                startingControlledTick, startingEpoch, parentStatus);

        ArrayList<InputTimelineTransitionEvidence> transitions = new ArrayList<>();
        Optional<FrameId> firstFrameId = Optional.empty();
        Optional<FrameId> finalFrameId = Optional.empty();
        int completedTicks = 0;
        try {
            for (int localTick = 1;
                    localTick <= evidence.signature.spec.totalTicks(); localTick++) {
                SimulationControlRegistry.ExactTickEvidence completed =
                        runtime.controls().tickExact(fixedStepNanos,
                                () -> requireExecutionState(startingEpoch, fixedStepNanos));
                completed.requireCompleted(fixedStepNanos);
                if (!completed.tick().executionEpochId().equals(startingEpoch)
                        || completed.tick().epochTick()
                                != Math.addExact(startingEpochTick, (long) localTick)
                        || !completed.tick().resultingFrameId().orElseThrow()
                                .equals(completed.frame().frameId())) {
                    throw new IllegalStateException(
                            "exact tick returned inconsistent timeline evidence");
                }
                completedTicks++;
                if (firstFrameId.isEmpty()) {
                    firstFrameId = Optional.of(completed.frame().frameId());
                }
                finalFrameId = Optional.of(completed.frame().frameId());
            }
            for (InputTimelineTransition transition : evidence.signature.spec.transitions()) {
                InputInjection injection = inputs.timelineInjection(
                        transition.transitionId(), evidence.requestId);
                if (injection.state() != InputInjectionState.EXECUTED) {
                    throw new IllegalStateException(
                            "successful input timeline retained a non-executed transition");
                }
                transitions.add(new InputTimelineTransitionEvidence(
                        transition.transitionId(), transition.timelineTick(), transition.inputId(),
                        InputTimelineTransitionState.EXECUTED, Optional.of(injection),
                        Optional.empty()));
            }
        } finally {
            inputs.endTimeline(evidence.requestId);
        }

        InputTimelineBounds provisionalBounds = new InputTimelineBounds(
                evidence.signature.spec.totalTicks(), completedTicks,
                evidence.signature.spec.transitions().size(), transitions.size(), 0, 0,
                0, evidence.preflight.maximumTicks, evidence.preflight.maximumTransitions,
                limits.maximumEncodedEvidenceBytes(), parentStatus.deadlineNanos());
        InputTimelineResult provisional = new InputTimelineResult(
                InputTimelineStopReason.COMPLETED, COMPLETED_MESSAGE, startingEpoch,
                startingControlledTick, fixedStepNanos, firstFrameId, finalFrameId,
                transitions, provisionalBounds, Optional.empty());
        long encodedBytes = InputTimelineCanonicalSize.result(provisional);
        if (encodedBytes > evidence.preflight.resultReservation
                || encodedBytes > limits.maximumEncodedEvidenceBytes()) {
            throw new IllegalStateException(
                    "input timeline result exceeded its preflight reservation");
        }
        InputTimelineBounds bounds = new InputTimelineBounds(
                evidence.signature.spec.totalTicks(), completedTicks,
                evidence.signature.spec.transitions().size(), transitions.size(), 0, 0,
                encodedBytes, evidence.preflight.maximumTicks,
                evidence.preflight.maximumTransitions, limits.maximumEncodedEvidenceBytes(),
                parentStatus.deadlineNanos());
        InputTimelineResult result = new InputTimelineResult(
                InputTimelineStopReason.COMPLETED, COMPLETED_MESSAGE, startingEpoch,
                startingControlledTick, fixedStepNanos, firstFrameId, finalFrameId,
                transitions, bounds, Optional.empty());
        synchronized (this) {
            evidence.result = result;
            activeParent = null;
        }
    }

    private long requireExecutionState(
            ExecutionEpochId expectedEpoch, long expectedFixedStepNanos) {
        if (!runtime.currentEpoch().equals(expectedEpoch)
                || !runtime.controls().pauseStateKnown()
                || !runtime.controls().paused()
                || !runtime.controls().acknowledgedTicksAvailable()) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                    "input timeline lifecycle changed before exact tick execution");
        }
        long fixedStepNanos = runtime.simulation().state().configuredFixedStepNanos()
                .orElseThrow(() -> new AgentRuntimeException(
                        RuntimeErrorCode.INVALID_LIFECYCLE,
                        "input timeline fixed step is no longer configured"));
        if (fixedStepNanos != expectedFixedStepNanos) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                    "input timeline fixed step changed before execution");
        }
        return fixedStepNanos;
    }

    private InputTimelineOperation snapshot(Evidence evidence, CommandLookup command) {
        reconcileBeforeExecution(evidence, command);
        synchronized (this) {
            return new InputTimelineOperation(
                    evidence.requestId, command, visibleResult(command, evidence.result));
        }
    }

    static Optional<InputTimelineResult> visibleResult(
            CommandLookup command, InputTimelineResult retainedResult) {
        if (retainedResult == null
                || command.status().filter(status -> !status.outcomeKnown()).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(retainedResult);
    }

    private void reconcileBeforeExecution(Evidence evidence, CommandLookup command) {
        if (command.status().isEmpty()) {
            return;
        }
        CommandStatus status = command.status().orElseThrow();
        if (!status.outcomeKnown()) {
            return;
        }
        boolean stopped = false;
        synchronized (this) {
            if (evidence.result != null || evidence.started) {
                return;
            }
            InputTimelineStopReason reason = status.state() == CommandState.TIMED_OUT
                    ? InputTimelineStopReason.TIMED_OUT
                    : InputTimelineStopReason.LIFECYCLE_CHANGED;
            List<InputTimelineTransitionEvidence> transitions = evidence.signature.spec.transitions()
                    .stream().map(transition -> new InputTimelineTransitionEvidence(
                            transition.transitionId(), transition.timelineTick(),
                            transition.inputId(), InputTimelineTransitionState.NOT_EXECUTED,
                            Optional.empty(), Optional.of(NOT_EXECUTED_MESSAGE))).toList();
            InputTimelineBounds provisionalBounds = new InputTimelineBounds(
                    evidence.signature.spec.totalTicks(), 0, transitions.size(), 0, 0,
                    transitions.size(), 0, evidence.preflight.maximumTicks,
                    evidence.preflight.maximumTransitions,
                    limits.maximumEncodedEvidenceBytes(), status.deadlineNanos());
            InputTimelineResult provisional = new InputTimelineResult(reason,
                    NOT_EXECUTED_MESSAGE, evidence.preflight.startingExecutionEpochId,
                    evidence.preflight.startingControlledTick, evidence.preflight.fixedStepNanos,
                    Optional.empty(), Optional.empty(), transitions, provisionalBounds,
                    status.applicationFailure());
            long encodedBytes = InputTimelineCanonicalSize.result(provisional);
            if (encodedBytes > evidence.preflight.resultReservation
                    || encodedBytes > limits.maximumEncodedEvidenceBytes()) {
                throw new IllegalStateException(
                        "input timeline result exceeded its preflight reservation");
            }
            InputTimelineBounds bounds = new InputTimelineBounds(
                    evidence.signature.spec.totalTicks(), 0, transitions.size(), 0, 0,
                    transitions.size(), encodedBytes, evidence.preflight.maximumTicks,
                    evidence.preflight.maximumTransitions,
                    limits.maximumEncodedEvidenceBytes(), status.deadlineNanos());
            evidence.result = new InputTimelineResult(reason, NOT_EXECUTED_MESSAGE,
                    evidence.preflight.startingExecutionEpochId,
                    evidence.preflight.startingControlledTick, evidence.preflight.fixedStepNanos,
                    Optional.empty(), Optional.empty(), transitions, bounds,
                    status.applicationFailure());
            activeParent = null;
            stopped = true;
        }
        if (stopped) {
            inputs.stopTimelineBeforeExecution(evidence.requestId, NOT_EXECUTED_MESSAGE);
        }
    }

    private synchronized List<String> planParentEvictions(CommandDispatch dispatch) {
        int needed = operations.size() + 1 - limits.retainedOperations();
        if (needed <= 0) {
            return List.of();
        }
        List<String> evictions = operations.entrySet().stream()
                .filter(entry -> visibleResult(dispatch.status(entry.getKey()),
                        entry.getValue().result).isPresent())
                .map(Map.Entry::getKey)
                .limit(needed)
                .toList();
        if (evictions.size() != needed) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "input timeline result retention limit reached");
        }
        return evictions;
    }

    private synchronized AdmissionState admissionState() {
        return new AdmissionState(new LinkedHashMap<>(operations), activeParent);
    }

    private synchronized void commitAdmission(
            List<String> parentEvictions, Evidence evidence) {
        for (String parentEviction : parentEvictions) {
            operations.remove(parentEviction);
        }
        operations.put(evidence.requestId, evidence);
        activeParent = evidence.requestId;
    }

    private synchronized boolean started(Evidence evidence) {
        return evidence.started;
    }

    private synchronized void rollbackAdmission(AdmissionState previous) {
        operations.clear();
        operations.putAll(previous.operations);
        activeParent = previous.activeParent;
    }

    private static long timeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("timeout exceeds the supported range", failure);
        }
    }

    private void requireTimeoutWithinLimits(long timeoutNanos, CommandDispatch dispatch) {
        if (timeoutNanos > limits.maximumExecutionNanos()
                || timeoutNanos > dispatch.limits().maximumTimeoutNanos()) {
            throw new IllegalArgumentException("timeout exceeds the configured limit");
        }
    }

    private record Signature(InputTimelineSpec spec, long timeoutNanos) {}

    private record Preflight(ExecutionEpochId startingExecutionEpochId,
            long startingControlledTick, long fixedStepNanos, int maximumTicks,
            int maximumTransitions, long resultReservation) {}

    private record AdmissionState(
            LinkedHashMap<String, Evidence> operations, String activeParent) {}

    private static final class Evidence {
        private final String requestId;
        private final Signature signature;
        private final Preflight preflight;
        private InputTimelineResult result;
        private boolean started;

        private Evidence(String requestId, Signature signature, Preflight preflight) {
            this.requestId = requestId;
            this.signature = signature;
            this.preflight = preflight;
        }
    }
}
