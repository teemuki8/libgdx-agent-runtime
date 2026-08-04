package io.github.teemuki8.libgdx.agent.runtime.core;

/** Configurable hard bounds for optional simulation control. */
public record ControlLimits(int registeredConditions, int ticksPerOperation,
        int retainedOperations, long maximumDeltaNanos) {
    /** Validates supported control limits. */
    public ControlLimits {
        if (registeredConditions <= 0 || registeredConditions > 1_000
                || ticksPerOperation <= 0 || ticksPerOperation > 1_000_000
                || retainedOperations <= 0 || retainedOperations > 10_000
                || maximumDeltaNanos <= 0) {
            throw new IllegalArgumentException("control limit is outside the supported range");
        }
    }

    /** Returns conservative development defaults. */
    public static ControlLimits developmentDefaults() {
        return new ControlLimits(128, 10_000, 256, 1_000_000_000L);
    }
}
