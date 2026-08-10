package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Package-private aggregate executor for fail-stop exact-tick input timelines. */
final class InputTimelineExecutor {
    private static final String NOT_EXECUTED_MESSAGE =
            InputTimelineCanonicalSize.PRE_EXECUTION_MESSAGE;
    private static final String EPOCH_CHANGED_MESSAGE =
            "input timeline lifecycle changed before target tick";

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
        long deadlineNanos = parentStatus.deadlineNanos();
        InputTimelineStopReason stopReason = InputTimelineStopReason.COMPLETED;
        String message = InputTimelineCanonicalSize.COMPLETED_MESSAGE;
        Optional<ApplicationFailureEvidence> applicationFailure = Optional.empty();
        Throwable rethrow = null;
        int completedTicks = 0;
        Optional<FrameId> firstFrameId = Optional.empty();
        Optional<FrameId> finalFrameId = Optional.empty();
        try {
            requireExecutionState(startingEpoch, evidence.preflight.fixedStepNanos);
            requireDeadline(deadlineNanos);
            inputs.stageTimeline(evidence.signature.spec, evidence.requestId,
                    startingControlledTick, startingEpoch, parentStatus);
            long startingEpochTick = runtime.simulation().state().attemptedEpochTicks();
            for (int localTick = 1;
                    localTick <= evidence.signature.spec.totalTicks(); localTick++) {
                requireDeadline(deadlineNanos);
                SimulationControlRegistry.ExactTickEvidence completed =
                        runtime.controls().tickExact(evidence.preflight.fixedStepNanos,
                                () -> {
                                    requireDeadline(deadlineNanos);
                                    requireExecutionState(
                                            startingEpoch, evidence.preflight.fixedStepNanos);
                                });
                completed.requireCompleted(evidence.preflight.fixedStepNanos);
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
                requireDeadline(deadlineNanos);
            }
        } catch (Throwable thrown) {
            rethrow = thrown;
            Optional<ApplicationFailureEvidence> childFailure =
                    inputs.timelineChildFailure(evidence.requestId);
            if (childFailure.isPresent()) {
                stopReason = InputTimelineStopReason.INPUT_FAILED;
                message = InputTimelineCanonicalSize.INPUT_FAILED_MESSAGE;
                applicationFailure = childFailure;
            } else if (thrown instanceof InputTimelineDeadlineExceeded) {
                stopReason = InputTimelineStopReason.TIMED_OUT;
                message = InputTimelineCanonicalSize.TIMED_OUT_MESSAGE;
            } else if (thrown instanceof AgentRuntimeException agentFailure
                    && agentFailure.code() == RuntimeErrorCode.INVALID_LIFECYCLE) {
                stopReason = InputTimelineStopReason.LIFECYCLE_CHANGED;
                message = InputTimelineCanonicalSize.LIFECYCLE_CHANGED_MESSAGE;
            } else {
                stopReason = InputTimelineStopReason.TICK_FAILED;
                message = InputTimelineCanonicalSize.TICK_FAILED_MESSAGE;
                applicationFailure = describeFailure(thrown, evidence.requestId);
            }
        }
        boolean cleanupFailed = false;
        Optional<ApplicationFailureEvidence> cleanupFailure = Optional.empty();
        try {
            inputs.releaseTimeline(evidence.requestId, message);
        } catch (Throwable thrown) {
            cleanupFailed = true;
            cleanupFailure = describeFailure(thrown, evidence.requestId);
        }
        if (stopReason == InputTimelineStopReason.COMPLETED) {
            if (runtime.monotonicTimeNanos() >= deadlineNanos) {
                stopReason = InputTimelineStopReason.TIMED_OUT;
                message = InputTimelineCanonicalSize.TIMED_OUT_MESSAGE;
            } else if (cleanupFailed) {
                stopReason = InputTimelineStopReason.CLEANUP_FAILED;
                message = InputTimelineCanonicalSize.CLEANUP_FAILED_MESSAGE;
                applicationFailure = cleanupFailure;
            }
        } else if (cleanupFailed) {
            stopReason = InputTimelineStopReason.CLEANUP_FAILED;
            message = InputTimelineCanonicalSize.CLEANUP_FAILED_MESSAGE;
            if (applicationFailure.isEmpty()) {
                applicationFailure = cleanupFailure;
            }
        }
        ArrayList<InputTimelineTransitionEvidence> transitions = new ArrayList<>();
        int executedTransitions = 0;
        int failedTransitions = 0;
        for (InputTimelineTransition transition : evidence.signature.spec.transitions()) {
            Optional<InputInjection> injection = inputs.attemptedTimelineInjection(
                    transition.transitionId(), evidence.requestId);
            if (injection.isEmpty()) {
                transitions.add(new InputTimelineTransitionEvidence(
                        transition.transitionId(), transition.timelineTick(),
                        transition.inputId(), InputTimelineTransitionState.NOT_EXECUTED,
                        Optional.empty(), Optional.of(message)));
                continue;
            }
            InputTimelineTransitionState state =
                    injection.orElseThrow().state() == InputInjectionState.EXECUTED
                            ? InputTimelineTransitionState.EXECUTED
                            : InputTimelineTransitionState.FAILED;
            if (state == InputTimelineTransitionState.EXECUTED) {
                executedTransitions++;
            } else {
                failedTransitions++;
            }
            transitions.add(new InputTimelineTransitionEvidence(
                    transition.transitionId(), transition.timelineTick(), transition.inputId(),
                    state, injection, injection.orElseThrow().diagnostic()));
        }
        InputTimelineResult result = publish(evidence, stopReason, message, applicationFailure,
                startingEpoch, startingControlledTick, evidence.preflight.fixedStepNanos,
                firstFrameId, finalFrameId,
                completedTicks, transitions, executedTransitions, failedTransitions,
                deadlineNanos);
        synchronized (this) {
            evidence.result = result;
            activeParent = null;
        }
        if (stopReason != InputTimelineStopReason.COMPLETED && rethrow != null) {
            rethrow(rethrow);
        }
    }

    private InputTimelineResult publish(Evidence evidence, InputTimelineStopReason stopReason,
            String message, Optional<ApplicationFailureEvidence> applicationFailure,
            ExecutionEpochId startingEpoch, long startingControlledTick, long fixedStepNanos,
            Optional<FrameId> firstFrameId, Optional<FrameId> finalFrameId, int completedTicks,
            List<InputTimelineTransitionEvidence> transitions, int executedTransitions,
            int failedTransitions, long deadlineNanos) {
        int requestedTransitions = evidence.signature.spec.transitions().size();
        int notExecutedTransitions = requestedTransitions
                - executedTransitions - failedTransitions;
        InputTimelineBounds provisionalBounds = new InputTimelineBounds(
                evidence.signature.spec.totalTicks(), completedTicks, requestedTransitions,
                executedTransitions, failedTransitions, notExecutedTransitions,
                0, evidence.preflight.maximumTicks, evidence.preflight.maximumTransitions,
                limits.maximumEncodedEvidenceBytes(), deadlineNanos);
        InputTimelineResult provisional = new InputTimelineResult(stopReason, message,
                startingEpoch, startingControlledTick, fixedStepNanos, firstFrameId, finalFrameId,
                transitions, provisionalBounds, applicationFailure);
        long encodedBytes = InputTimelineCanonicalSize.result(provisional);
        if (stopReason != InputTimelineStopReason.EVIDENCE_LIMIT
                && (encodedBytes > evidence.preflight.resultReservation
                        || encodedBytes > limits.maximumEncodedEvidenceBytes())) {
            List<InputTimelineTransitionEvidence> boundedTransitions = transitions.stream()
                    .map(value -> value.state() == InputTimelineTransitionState.NOT_EXECUTED
                            ? new InputTimelineTransitionEvidence(
                                    value.transitionId(), value.timelineTick(), value.inputId(),
                                    InputTimelineTransitionState.NOT_EXECUTED,
                                    Optional.empty(), Optional.of(
                                            InputTimelineCanonicalSize.EVIDENCE_LIMIT_MESSAGE))
                            : value)
                    .toList();
            return publish(evidence, InputTimelineStopReason.EVIDENCE_LIMIT,
                    InputTimelineCanonicalSize.EVIDENCE_LIMIT_MESSAGE, Optional.empty(),
                    startingEpoch, startingControlledTick, fixedStepNanos, firstFrameId,
                    finalFrameId, completedTicks, boundedTransitions, executedTransitions,
                    failedTransitions, deadlineNanos);
        }
        InputTimelineBounds bounds = new InputTimelineBounds(
                evidence.signature.spec.totalTicks(), completedTicks, requestedTransitions,
                executedTransitions, failedTransitions, notExecutedTransitions,
                encodedBytes, evidence.preflight.maximumTicks,
                evidence.preflight.maximumTransitions, limits.maximumEncodedEvidenceBytes(),
                deadlineNanos);
        return new InputTimelineResult(stopReason, message, startingEpoch,
                startingControlledTick, fixedStepNanos, firstFrameId, finalFrameId,
                transitions, bounds, applicationFailure);
    }

    private void requireDeadline(long deadlineNanos) {
        if (runtime.monotonicTimeNanos() >= deadlineNanos) {
            throw new InputTimelineDeadlineExceeded();
        }
    }

    private Optional<ApplicationFailureEvidence> describeFailure(
            Throwable failure, String requestId) {
        Optional<String> correlationId = runtime.commands().orElseThrow()
                .correlationId(requestId);
        return correlationId.isPresent()
                ? Optional.of(runtime.diagnostics().describe(
                        "input-timeline.tick", failure, correlationId.orElseThrow()))
                : Optional.of(runtime.diagnostics().describe("input-timeline.tick", failure));
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(failure);
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
            inputs.releaseTimeline(evidence.requestId, NOT_EXECUTED_MESSAGE);
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

    /** Internal fail-closed marker for a monotonic deadline that expired mid-execution. */
    static final class InputTimelineDeadlineExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InputTimelineDeadlineExceeded() {
            super("input timeline exceeded its execution deadline");
        }
    }

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
