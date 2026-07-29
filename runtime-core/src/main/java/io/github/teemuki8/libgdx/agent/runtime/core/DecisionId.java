package io.github.teemuki8.libgdx.agent.runtime.core;

/** Ordered decision identifier within a session. */
public record DecisionId(long value) {
    /** Validates a positive sequence. */
    public DecisionId {
        if (value <= 0) {
            throw new IllegalArgumentException("decisionId must be positive");
        }
    }
}
