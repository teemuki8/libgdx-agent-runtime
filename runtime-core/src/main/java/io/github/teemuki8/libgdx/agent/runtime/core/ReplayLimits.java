package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;

/** Independent hard bounds for replay capture, retained evidence, and execution. */
public record ReplayLimits(int retainedOperations, int maximumInputs, int maximumTicks,
        int maximumEntitiesPerFrame, int maximumFactsPerFrame,
        int maximumEncodedEvidenceBytes, long maximumExecutionNanos) {
    /** Validates supported development bounds. */
    public ReplayLimits {
        if (retainedOperations <= 0 || retainedOperations > 100_000
                || maximumInputs <= 0 || maximumInputs > 100_000
                || maximumTicks <= 0 || maximumTicks > 100_000
                || maximumEntitiesPerFrame <= 0 || maximumEntitiesPerFrame > 100_000
                || maximumFactsPerFrame <= 0 || maximumFactsPerFrame > 1_000_000
                || maximumEncodedEvidenceBytes <= 0
                || maximumEncodedEvidenceBytes > 16_777_216
                || maximumExecutionNanos <= 0
                || maximumExecutionNanos > Duration.ofMinutes(5).toNanos()) {
            throw new IllegalArgumentException("invalid replay limits");
        }
    }

    /** Conservative local-development defaults. */
    public static ReplayLimits developmentDefaults() {
        return new ReplayLimits(32, 4_096, 600, 10_000, 100_000,
                1_048_576, Duration.ofSeconds(30).toNanos());
    }
}
