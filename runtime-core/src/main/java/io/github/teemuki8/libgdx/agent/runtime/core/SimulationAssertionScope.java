package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** Explicit bounded execution-epoch and inclusive simulation-tick assertion scope. */
public record SimulationAssertionScope(ExecutionEpochId executionEpochId,
        long fromEpochTick, long toEpochTick, int evidenceLimit) {
    /** Maximum attempted ticks evaluated by one assertion. */
    public static final int MAX_TICKS = 1_000;
    /** Maximum supporting evidence items returned by one assertion. */
    public static final int MAX_EVIDENCE = 100;

    /** Validates tick span and evidence bounds. */
    public SimulationAssertionScope {
        Objects.requireNonNull(executionEpochId, "executionEpochId");
        if (fromEpochTick <= 0 || toEpochTick < fromEpochTick
                || toEpochTick - fromEpochTick >= MAX_TICKS) {
            throw new IllegalArgumentException("simulation assertion tick range is invalid");
        }
        if (evidenceLimit <= 0 || evidenceLimit > MAX_EVIDENCE) {
            throw new IllegalArgumentException("simulation assertion evidence limit is invalid");
        }
    }
}
