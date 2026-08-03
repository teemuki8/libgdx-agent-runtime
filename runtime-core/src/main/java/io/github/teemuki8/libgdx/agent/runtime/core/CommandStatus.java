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
    }
}
