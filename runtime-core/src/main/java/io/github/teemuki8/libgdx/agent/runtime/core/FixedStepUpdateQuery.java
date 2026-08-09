package io.github.teemuki8.libgdx.agent.runtime.core;

/** Inclusive bounded query over fixed-step update sequence identities. */
public record FixedStepUpdateQuery(long fromSequence, long toSequence, int limit) {
    /** Validates sequence range and page size. */
    public FixedStepUpdateQuery {
        if (fromSequence <= 0 || toSequence < fromSequence || limit <= 0) {
            throw new IllegalArgumentException("invalid fixed-step update query");
        }
    }
}
