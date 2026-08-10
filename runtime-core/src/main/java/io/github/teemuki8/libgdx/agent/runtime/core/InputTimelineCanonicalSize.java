package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Optional;
import java.util.OptionalLong;

/** Exact saturating canonical byte accounting for input timeline requests and evidence. */
final class InputTimelineCanonicalSize {
    static final String PRE_EXECUTION_MESSAGE =
            "parent command ended before timeline execution";

    private InputTimelineCanonicalSize() {}

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

    static long terminalResultReservation(InputTimelineSpec spec) {
        long size = 1;
        size = add(size, DeterminismCanonicalSize.string(PRE_EXECUTION_MESSAGE));
        size = add(size, Long.BYTES * 3L);
        size = add(size, optionalLong(false));
        size = add(size, optionalLong(false));
        size = add(size, DeterminismCanonicalSize.listPrefix());
        for (InputTimelineTransition transition : spec.transitions()) {
            size = add(size, transitionPrefix(
                    transition.transitionId(), transition.timelineTick(), transition.inputId()));
            size = add(size, 1);
            size = add(size, 1);
            size = add(size, optionalString(Optional.of(PRE_EXECUTION_MESSAGE)));
        }
        size = add(size, bounds());
        return add(size, maximumOptionalFailure());
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
}
