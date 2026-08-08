package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Immutable status for one retained application command request. */
public record CommandStatus(
        String requestId,
        CommandState state,
        long submittedAtNanos,
        long deadlineNanos,
        Optional<Long> startedAtNanos,
        Optional<Long> completedAtNanos,
        boolean outcomeKnown,
        Optional<String> diagnostic,
        Optional<ApplicationFailureEvidence> applicationFailure) {
    /** Validates and copies status fields. */
    public CommandStatus {
        IdentifierSupport.validate(requestId, "requestId");
        Objects.requireNonNull(state, "state");
        if (submittedAtNanos < 0 || deadlineNanos < submittedAtNanos) {
            throw new IllegalArgumentException("command times must be non-negative and ascending");
        }
        startedAtNanos = Objects.requireNonNull(startedAtNanos, "startedAtNanos");
        completedAtNanos = Objects.requireNonNull(completedAtNanos, "completedAtNanos");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        applicationFailure = applicationFailure == null
                ? Optional.empty() : applicationFailure;
        startedAtNanos.ifPresent(value -> requireTime(value, "startedAtNanos"));
        completedAtNanos.ifPresent(value -> requireTime(value, "completedAtNanos"));
        startedAtNanos.ifPresent(value -> requireAtLeast(
                value, submittedAtNanos, "startedAtNanos", "submittedAtNanos"));
        completedAtNanos.ifPresent(value -> requireAtLeast(
                value, submittedAtNanos, "completedAtNanos", "submittedAtNanos"));
        if (startedAtNanos.isPresent() && completedAtNanos.isPresent()) {
            requireAtLeast(completedAtNanos.orElseThrow(), startedAtNanos.orElseThrow(),
                    "completedAtNanos", "startedAtNanos");
        }
        validateLifecycle(state, startedAtNanos, completedAtNanos, outcomeKnown);
        diagnostic.ifPresent(value -> {
            if (value.length() > CommandDispatchLimits.MAX_DIAGNOSTIC_LENGTH) {
                throw new IllegalArgumentException("diagnostic exceeds the public size bound");
            }
        });
    }

    private static void requireTime(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void requireAtLeast(long value, long minimum, String name, String minimumName) {
        if (value < minimum) {
            throw new IllegalArgumentException(name + " must not precede " + minimumName);
        }
    }

    private static void validateLifecycle(CommandState state, Optional<Long> started,
            Optional<Long> completed, boolean outcomeKnown) {
        switch (state) {
            case QUEUED -> {
                if (started.isPresent() || completed.isPresent() || outcomeKnown) {
                    throw new IllegalArgumentException("queued command lifecycle is inconsistent");
                }
            }
            case EXECUTING -> {
                if (started.isEmpty() || completed.isPresent() || outcomeKnown) {
                    throw new IllegalArgumentException(
                            "executing command lifecycle is inconsistent");
                }
            }
            case TIMED_OUT -> {
                boolean known = outcomeKnown && completed.isPresent();
                boolean unknown = !outcomeKnown && started.isPresent() && completed.isEmpty();
                if (!known && !unknown) {
                    throw new IllegalArgumentException("timed-out command lifecycle is inconsistent");
                }
            }
            case SUCCEEDED -> {
                if (started.isEmpty() || completed.isEmpty() || !outcomeKnown) {
                    throw new IllegalArgumentException(
                            "succeeded command lifecycle is inconsistent");
                }
            }
            case REJECTED, FAILED, CANCELLED -> {
                if (completed.isEmpty() || !outcomeKnown) {
                    throw new IllegalArgumentException("terminal command lifecycle is inconsistent");
                }
            }
        }
    }
}
