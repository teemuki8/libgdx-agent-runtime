package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;

/** Independent hard bounds for repeated deterministic execution and evidence. */
public record DeterminismLimits(int retainedOperations, int maximumRepeats,
        int maximumTicksPerRepeat, int maximumEntitiesPerFrame, int maximumFactsPerFrame,
        int maximumEncodedEvidenceBytes, long maximumExecutionNanos) {
    /** Validates supported development bounds. */
    public DeterminismLimits {
        if (retainedOperations <= 0 || retainedOperations > 100_000
                || maximumRepeats < 2 || maximumRepeats > 100
                || maximumTicksPerRepeat <= 0 || maximumTicksPerRepeat > 100_000
                || maximumEntitiesPerFrame <= 0 || maximumEntitiesPerFrame > 100_000
                || maximumFactsPerFrame <= 0 || maximumFactsPerFrame > 1_000_000
                || maximumEncodedEvidenceBytes <= 0 || maximumEncodedEvidenceBytes > 16_777_216
                || maximumExecutionNanos <= 0
                || maximumExecutionNanos > Duration.ofMinutes(5).toNanos()) {
            throw new IllegalArgumentException("invalid determinism limits");
        }
    }

    /** Conservative local-development defaults. */
    public static DeterminismLimits developmentDefaults() {
        return new DeterminismLimits(32, 4, 600, 10_000, 100_000,
                1_048_576, Duration.ofSeconds(30).toNanos());
    }
}
