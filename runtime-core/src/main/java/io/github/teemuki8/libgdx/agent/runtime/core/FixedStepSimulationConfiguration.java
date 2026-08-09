package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** Immutable validated configuration for one application-owned fixed-step accumulator. */
public record FixedStepSimulationConfiguration(long fixedStepNanos,
        long maximumRenderDeltaNanos, long maximumAccumulatedTimeNanos,
        int maximumCatchUpTicks, int retainedUpdateReports,
        FixedStepDropPolicy dropPolicy, boolean acknowledgementRequired) {
    private static final int MAXIMUM_CATCH_UP_TICKS = 1_000_000;
    private static final int MAXIMUM_RETAINED_REPORTS = 1_000_000;

    /** Validates positive, bounded, and overflow-safe accumulator configuration. */
    public FixedStepSimulationConfiguration {
        Objects.requireNonNull(dropPolicy, "dropPolicy");
        if (fixedStepNanos <= 0
                || maximumRenderDeltaNanos < fixedStepNanos
                || maximumAccumulatedTimeNanos < fixedStepNanos
                || maximumCatchUpTicks <= 0
                || maximumCatchUpTicks > MAXIMUM_CATCH_UP_TICKS
                || retainedUpdateReports <= 0
                || retainedUpdateReports > MAXIMUM_RETAINED_REPORTS) {
            throw new IllegalArgumentException("fixed-step configuration is outside range");
        }
        try {
            Math.addExact(maximumRenderDeltaNanos, maximumAccumulatedTimeNanos);
            Math.multiplyExact(fixedStepNanos, maximumCatchUpTicks);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("fixed-step configuration is overflow-prone", failure);
        }
    }

    /** Returns conservative game-development defaults for one exact integer step. */
    public static FixedStepSimulationConfiguration developmentDefaults(long fixedStepNanos) {
        long maximumTime;
        try {
            maximumTime = Math.multiplyExact(fixedStepNanos, 15);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("fixed step is too large for defaults", failure);
        }
        return new FixedStepSimulationConfiguration(fixedStepNanos, maximumTime,
                maximumTime, 8, 1_000, FixedStepDropPolicy.DROP_WHOLE_TICKS_KEEP_REMAINDER, true);
    }
}
