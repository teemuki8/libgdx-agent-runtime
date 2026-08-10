package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;

/** Independent hard bounds for input timeline retention, execution, and evidence. */
public record InputTimelineLimits(int retainedOperations, int maximumTransitions,
        int maximumTicks, int maximumEncodedEvidenceBytes, long maximumExecutionNanos) {
    /** Absolute supported retained-operation ceiling. */
    public static final int MAXIMUM_RETAINED_OPERATIONS = 100_000;
    /** Absolute supported transition ceiling. */
    public static final int MAXIMUM_TRANSITIONS = 100_000;
    /** Absolute supported exact-tick ceiling. */
    public static final int MAXIMUM_TICKS = 100_000;
    /** Absolute supported canonical evidence-size ceiling. */
    public static final int MAXIMUM_ENCODED_EVIDENCE_BYTES = 16_777_216;
    /** Absolute supported execution-duration ceiling. */
    public static final long MAXIMUM_EXECUTION_NANOS = Duration.ofMinutes(5).toNanos();

    /** Validates positive configured limits within the supported ceilings. */
    public InputTimelineLimits {
        if (retainedOperations <= 0
                || retainedOperations > MAXIMUM_RETAINED_OPERATIONS
                || maximumTransitions <= 0 || maximumTransitions > MAXIMUM_TRANSITIONS
                || maximumTicks <= 0 || maximumTicks > MAXIMUM_TICKS
                || maximumEncodedEvidenceBytes <= 0
                || maximumEncodedEvidenceBytes > MAXIMUM_ENCODED_EVIDENCE_BYTES
                || maximumExecutionNanos <= 0
                || maximumExecutionNanos > MAXIMUM_EXECUTION_NANOS) {
            throw new IllegalArgumentException("invalid input timeline limits");
        }
    }

    /** Returns conservative local-development defaults. */
    public static InputTimelineLimits developmentDefaults() {
        return new InputTimelineLimits(32, 4_096, 600, 1_048_576,
                Duration.ofSeconds(30).toNanos());
    }
}
