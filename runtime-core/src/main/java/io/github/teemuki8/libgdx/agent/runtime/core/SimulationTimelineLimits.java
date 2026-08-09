package io.github.teemuki8.libgdx.agent.runtime.core;

/** Hard bounds for retained and reported simulation timeline evidence. */
public record SimulationTimelineLimits(int retainedTicks, int queryPageSize,
        long maximumDeltaNanos, long maximumEpochSimulationTimeNanos,
        int diagnosticLength) {
    /** Validates supported timeline limits. */
    public SimulationTimelineLimits {
        if (retainedTicks <= 0 || retainedTicks > 1_000_000
                || queryPageSize <= 0 || queryPageSize > 10_000
                || maximumDeltaNanos <= 0
                || maximumEpochSimulationTimeNanos <= 0
                || diagnosticLength < 64 || diagnosticLength > 4_096) {
            throw new IllegalArgumentException("simulation timeline limit is outside the supported range");
        }
    }

    /** Returns conservative development defaults. */
    public static SimulationTimelineLimits developmentDefaults() {
        return new SimulationTimelineLimits(10_000, 1_000,
                1_000_000_000L, 86_400_000_000_000L, 512);
    }
}
