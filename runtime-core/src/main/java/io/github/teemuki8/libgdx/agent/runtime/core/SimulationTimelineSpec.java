package io.github.teemuki8.libgdx.agent.runtime.core;

/** Explicit timing configuration for application-reported simulation ticks. */
public record SimulationTimelineSpec(long fixedStepNanos) {
    /** Validates a positive fixed step. */
    public SimulationTimelineSpec {
        if (fixedStepNanos <= 0) {
            throw new IllegalArgumentException("fixed simulation step must be positive");
        }
    }

    /** Creates a fixed-step simulation configuration. */
    public static SimulationTimelineSpec fixedStep(long fixedStepNanos) {
        return new SimulationTimelineSpec(fixedStepNanos);
    }
}
