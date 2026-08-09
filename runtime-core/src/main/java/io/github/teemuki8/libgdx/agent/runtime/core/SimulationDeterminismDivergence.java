package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** First exact simulation-tick difference with correlation evidence from both runs. */
public record SimulationDeterminismDivergence(long epochTick,
        SimulationTickId leftSimulationTickId, SimulationTickId rightSimulationTickId,
        ExecutionEpochId leftExecutionEpochId, ExecutionEpochId rightExecutionEpochId,
        FrameId leftFrameId, FrameId rightFrameId, DeterminismDifference difference) {
    /** Validates complete positive-tick divergence evidence. */
    public SimulationDeterminismDivergence {
        if (epochTick <= 0) {
            throw new IllegalArgumentException("divergence epoch tick must be positive");
        }
        Objects.requireNonNull(leftSimulationTickId, "leftSimulationTickId");
        Objects.requireNonNull(rightSimulationTickId, "rightSimulationTickId");
        Objects.requireNonNull(leftExecutionEpochId, "leftExecutionEpochId");
        Objects.requireNonNull(rightExecutionEpochId, "rightExecutionEpochId");
        Objects.requireNonNull(leftFrameId, "leftFrameId");
        Objects.requireNonNull(rightFrameId, "rightFrameId");
        Objects.requireNonNull(difference, "difference");
    }
}
