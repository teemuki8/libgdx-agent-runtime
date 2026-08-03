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
        Optional<String> diagnostic) {
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
        startedAtNanos.ifPresent(value -> requireTime(value, "startedAtNanos"));
        completedAtNanos.ifPresent(value -> requireTime(value, "completedAtNanos"));
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
}
