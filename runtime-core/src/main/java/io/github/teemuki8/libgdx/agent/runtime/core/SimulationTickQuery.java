package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** Inclusive bounded query over epoch-relative simulation tick identities. */
public record SimulationTickQuery(ExecutionEpochId executionEpochId,
        long fromEpochTick, long toEpochTick, int limit) {
    /** Validates the requested range. */
    public SimulationTickQuery {
        Objects.requireNonNull(executionEpochId, "executionEpochId");
        if (fromEpochTick <= 0 || toEpochTick < fromEpochTick || limit <= 0) {
            throw new IllegalArgumentException("invalid simulation tick query");
        }
    }
}
