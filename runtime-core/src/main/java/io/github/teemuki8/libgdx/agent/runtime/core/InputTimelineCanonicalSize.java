package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Exact saturating canonical byte accounting for input timeline requests and evidence. */
final class InputTimelineCanonicalSize {
    /** Bounded deterministic message for a parent that ended before execution began. */
    static final String PRE_EXECUTION_MESSAGE =
            "parent command ended before timeline execution";
    /** Bounded deterministic message for a fully completed timeline. */
    static final String COMPLETED_MESSAGE = "completed";
    /** Bounded deterministic message for an expired execution deadline. */
    static final String TIMED_OUT_MESSAGE = "input timeline exceeded its execution deadline";
    /** Bounded deterministic message for a frozen lifecycle fact that changed. */
    static final String LIFECYCLE_CHANGED_MESSAGE =
            "input timeline lifecycle changed during execution";
    /** Bounded deterministic message for a failed transition handler. */
    static final String INPUT_FAILED_MESSAGE =
            "input timeline stopped after a transition failure";
    /** Bounded deterministic message for a failed tick or capture. */
    static final String TICK_FAILED_MESSAGE = "input timeline stopped after a tick failure";
    /** Bounded deterministic message for evidence that exceeded its reservation. */
    static final String EVIDENCE_LIMIT_MESSAGE =
            "input timeline evidence exceeded its reservation";
    /** Bounded deterministic message for a failed exclusive-mode release. */
    static final String CLEANUP_FAILED_MESSAGE = "input timeline cleanup failed";

    private static final String MAXIMUM_TERMINAL_MESSAGE = longestTerminalMessage();

    private InputTimelineCanonicalSize() {}

    private static String longestTerminalMessage() {
        String longest = PRE_EXECUTION_MESSAGE;
        for (String message : List.of(COMPLETED_MESSAGE, TIMED_OUT_MESSAGE,
                LIFECYCLE_CHANGED_MESSAGE, INPUT_FAILED_MESSAGE, TICK_FAILED_MESSAGE,
                EVIDENCE_LIMIT_MESSAGE, CLEANUP_FAILED_MESSAGE)) {
            if (message.length() > longest.length()) {
                longest = message;
            }
        }
        return longest;
    }

    static long request(InputTimelineSpec spec) {
        long size = Integer.BYTES;
        size = add(size, DeterminismCanonicalSize.listPrefix());
        for (InputTimelineTransition transition : spec.transitions()) {
            size = add(size, DeterminismCanonicalSize.string(transition.transitionId()));
            size = add(size, Integer.BYTES);
            size = add(size, DeterminismCanonicalSize.string(transition.inputId()));
            size = add(size, DeterminismCanonicalSize.value(transition.parameters()));
        }
        return size;
    }

    static long successfulResultReservation(InputTimelineSpec spec) {
        long size = resultPrefix("completed");
        for (InputTimelineTransition transition : spec.transitions()) {
            size = add(size, successfulTransition(transition));
        }
        return add(size, boundsAndFailure());
    }

    /**
     * Worst-case terminal reservation: frames may be present after partial completion, exactly one
     * transition carries a full failed injection with a bounded presence flag, injection, and
     * outer diagnostic, every other slot covers either a full attempted injection with bounded
     * diagnostic or an unattempted transition with a bounded reason, and the parent retains a
     * structured failure. Fail-stop execution never produces more than one failed transition, and
     * executed children never carry structured failure evidence.
     */
    static long terminalResultReservation(InputTimelineSpec spec) {
        long size = resultPrefix(MAXIMUM_TERMINAL_MESSAGE);
        List<InputTimelineTransition> transitions = spec.transitions();
        for (int index = 0; index < transitions.size(); index++) {
            InputTimelineTransition transition = transitions.get(index);
            if (index == 0) {
                size = add(size, failedTransition(transition));
            } else {
                size = add(size, Math.max(
                        notExecutedTransition(transition),
                        executedTransition(transition)));
            }
        }
        size = add(size, bounds());
        return add(size, maximumOptionalFailure());
    }

    private static long failedTransition(InputTimelineTransition transition) {
        long size = transitionPrefix(
                transition.transitionId(), transition.timelineTick(), transition.inputId());
        size = add(size, 1);
        size = add(size, 1);
        size = add(size, failedInjection(transition));
        return add(size, maximumOptionalString(
                ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY));
    }

    private static long executedTransition(InputTimelineTransition transition) {
        long size = transitionPrefix(
                transition.transitionId(), transition.timelineTick(), transition.inputId());
        size = add(size, 1);
        size = add(size, 1);
        size = add(size, executedInjection(transition));
        return add(size, maximumOptionalString(
                ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY));
    }

    private static long notExecutedTransition(InputTimelineTransition transition) {
        long size = transitionPrefix(
                transition.transitionId(), transition.timelineTick(), transition.inputId());
        size = add(size, 1);
        size = add(size, 1);
        return add(size, maximumOptionalString(MAXIMUM_TERMINAL_MESSAGE.length()));
    }

    static long resultReservation(InputTimelineSpec spec) {
        return Math.max(successfulResultReservation(spec), terminalResultReservation(spec));
    }

    static long result(InputTimelineResult result) {
        long size = 1;
        size = add(size, DeterminismCanonicalSize.string(result.message()));
        size = add(size, Long.BYTES * 3L);
        size = add(size, optionalFrame(result.firstFrameId()));
        size = add(size, optionalFrame(result.finalFrameId()));
        size = add(size, DeterminismCanonicalSize.listPrefix());
        for (InputTimelineTransitionEvidence transition : result.transitions()) {
            size = add(size, transition(transition));
        }
        size = add(size, bounds());
        return add(size, optionalFailure(result.applicationFailure()));
    }

    static long add(long left, long right) {
        return DeterminismCanonicalSize.add(left, right);
    }

    private static long resultPrefix(String message) {
        long size = 1;
        size = add(size, DeterminismCanonicalSize.string(message));
        size = add(size, Long.BYTES * 3L);
        size = add(size, optionalLong(true));
        size = add(size, optionalLong(true));
        size = add(size, DeterminismCanonicalSize.listPrefix());
        return size;
    }

    private static long successfulTransition(InputTimelineTransition transition) {
        long size = transitionPrefix(
                transition.transitionId(), transition.timelineTick(), transition.inputId());
        size = add(size, 1);
        size = add(size, 1);
        size = add(size, successfulInjection(transition));
        return add(size, 1);
    }

    private static long successfulInjection(InputTimelineTransition transition) {
        long size = DeterminismCanonicalSize.string(transition.inputId());
        size = add(size, DeterminismCanonicalSize.string(transition.transitionId()));
        size = add(size, successfulCommand(transition.transitionId()));
        size = add(size, 1 + Long.BYTES);
        size = add(size, optionalLong(true));
        size = add(size, Long.BYTES);
        size = add(size, optionalLong(true));
        size = add(size, optionalLong(true));
        size = add(size, 1);
        size = add(size, DeterminismCanonicalSize.value(transition.parameters()));
        size = add(size, 1);
        size = add(size, 1);
        return add(size, 1);
    }

    private static long successfulCommand(String requestId) {
        long size = 2;
        size = add(size, DeterminismCanonicalSize.string(requestId));
        size = add(size, 1 + Long.BYTES * 2L);
        size = add(size, optionalLong(true));
        size = add(size, optionalLong(true));
        size = add(size, 1);
        size = add(size, 1);
        return add(size, 1);
    }

    /**
     * Worst-case failed child injection: a failed logical command status with bounded diagnostic
     * and structured failure, a present actual tick and frames, and recorded parameters.
     */
    private static long failedInjection(InputTimelineTransition transition) {
        long size = DeterminismCanonicalSize.string(transition.inputId());
        size = add(size, DeterminismCanonicalSize.string(transition.transitionId()));
        size = add(size, failedCommand(transition.transitionId()));
        size = add(size, 1 + Long.BYTES);
        size = add(size, optionalLong(true));
        size = add(size, Long.BYTES);
        size = add(size, optionalLong(true));
        size = add(size, optionalLong(true));
        size = add(size, 1);
        size = add(size, DeterminismCanonicalSize.value(transition.parameters()));
        size = add(size, 1);
        size = add(size, maximumOptionalString(
                ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY));
        return add(size, maximumOptionalFailure());
    }

    /**
     * Worst-case attempted-but-executed child injection: a succeeded logical command status with a
     * bounded diagnostic (a failed tick can mark executed inputs), a present actual tick and
     * frames, and recorded parameters; executed children never carry structured failure evidence.
     */
    private static long executedInjection(InputTimelineTransition transition) {
        long size = DeterminismCanonicalSize.string(transition.inputId());
        size = add(size, DeterminismCanonicalSize.string(transition.transitionId()));
        size = add(size, successfulCommand(transition.transitionId()));
        size = add(size, 1 + Long.BYTES);
        size = add(size, optionalLong(true));
        size = add(size, Long.BYTES);
        size = add(size, optionalLong(true));
        size = add(size, optionalLong(true));
        size = add(size, 1);
        size = add(size, DeterminismCanonicalSize.value(transition.parameters()));
        size = add(size, 1);
        size = add(size, maximumOptionalString(
                ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY));
        return add(size, 1);
    }

    private static long failedCommand(String requestId) {
        long size = 2;
        size = add(size, DeterminismCanonicalSize.string(requestId));
        size = add(size, 1 + Long.BYTES * 2L);
        size = add(size, optionalLong(true));
        size = add(size, optionalLong(true));
        size = add(size, 1);
        size = add(size, maximumOptionalString(
                ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY));
        return add(size, maximumOptionalFailure());
    }

    private static long transition(InputTimelineTransitionEvidence transition) {
        long size = transitionPrefix(transition.transitionId(), transition.timelineTick(),
                transition.inputId());
        size = add(size, 1);
        size = add(size, 1);
        if (transition.injection().isPresent()) {
            size = add(size, injection(transition.injection().orElseThrow()));
        }
        return add(size, optionalString(transition.diagnostic()));
    }

    private static long transitionPrefix(String transitionId, int tick, String inputId) {
        long size = DeterminismCanonicalSize.string(transitionId);
        size = add(size, Integer.BYTES);
        return add(size, DeterminismCanonicalSize.string(inputId));
    }

    private static long injection(InputInjection injection) {
        long size = DeterminismCanonicalSize.string(injection.inputId());
        size = add(size, DeterminismCanonicalSize.string(injection.requestId()));
        size = add(size, command(injection.command()));
        size = add(size, 1 + Long.BYTES);
        size = add(size, optionalLong(injection.actualTick()));
        size = add(size, Long.BYTES);
        size = add(size, optionalFrame(injection.submittedFrameId()));
        size = add(size, optionalFrame(injection.resultingFrameId()));
        size = add(size, 1);
        if (injection.recordedParameters().isPresent()) {
            size = add(size, DeterminismCanonicalSize.value(
                    injection.recordedParameters().orElseThrow()));
        }
        size = add(size, 1);
        size = add(size, optionalString(injection.diagnostic()));
        return add(size, optionalFailure(injection.applicationFailure()));
    }

    private static long command(CommandLookup command) {
        long size = 2;
        if (command.status().isPresent()) {
            CommandStatus status = command.status().orElseThrow();
            size = add(size, DeterminismCanonicalSize.string(status.requestId()));
            size = add(size, 1 + Long.BYTES * 2L);
            size = add(size, optionalLong(status.startedAtNanos().isPresent()));
            size = add(size, optionalLong(status.completedAtNanos().isPresent()));
            size = add(size, 1);
            size = add(size, optionalString(status.diagnostic()));
            size = add(size, optionalFailure(status.applicationFailure()));
        }
        return size;
    }

    private static long boundsAndFailure() {
        return add(bounds(), 1);
    }

    private static long bounds() {
        return Integer.BYTES * 9L + Long.BYTES * 2L;
    }

    private static long optionalFrame(Optional<FrameId> value) {
        return optionalLong(value.isPresent());
    }

    private static long optionalLong(OptionalLong value) {
        return optionalLong(value.isPresent());
    }

    private static long optionalLong(boolean present) {
        return 1L + (present ? Long.BYTES : 0L);
    }

    private static long optionalString(Optional<String> value) {
        long size = 1;
        return value.isPresent()
                ? add(size, DeterminismCanonicalSize.string(value.orElseThrow())) : size;
    }

    private static long optionalFailure(Optional<ApplicationFailureEvidence> value) {
        long size = 1;
        if (value.isEmpty()) {
            return size;
        }
        ApplicationFailureEvidence failure = value.orElseThrow();
        size = add(size, DeterminismCanonicalSize.string(failure.category()));
        size = add(size, DeterminismCanonicalSize.string(failure.exceptionClass()));
        size = add(size, DeterminismCanonicalSize.string(failure.correlationId()));
        return add(size, optionalString(failure.sanitizedDetail()));
    }

    private static long maximumOptionalFailure() {
        long size = 1;
        size = add(size, maximumString(ApplicationFailureEvidence.MAX_CATEGORY_LENGTH));
        size = add(size, maximumString(
                ApplicationFailureEvidence.MAX_EXCEPTION_CLASS_LENGTH));
        size = add(size, maximumString(
                ApplicationFailureEvidence.MAX_CORRELATION_ID_LENGTH));
        size = add(size, 1);
        return add(size, maximumString(
                ApplicationFailureEvidence.MAX_SANITIZED_DETAIL_LENGTH));
    }

    private static long maximumString(int maximumUtf16Length) {
        return add(Integer.BYTES, 3L * maximumUtf16Length);
    }

    private static long maximumOptionalString(int maximumUtf16Length) {
        return add(1, maximumString(maximumUtf16Length));
    }
}
