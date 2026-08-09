package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** Transient context for the simulation tick whose runtime frame is currently open. */
public record ActiveSimulationTick(SimulationTickId simulationTickId,
        ExecutionEpochId executionEpochId, long epochTick, long suppliedDeltaNanos,
        SimulationTickSource source, FrameId runtimeFrameId) {
    /** Validates the transient tick context. */
    public ActiveSimulationTick {
        Objects.requireNonNull(simulationTickId, "simulationTickId");
        Objects.requireNonNull(executionEpochId, "executionEpochId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(runtimeFrameId, "runtimeFrameId");
        if (epochTick <= 0) {
            throw new IllegalArgumentException("epochTick must be positive");
        }
        if (suppliedDeltaNanos < 0) {
            throw new IllegalArgumentException("suppliedDeltaNanos must be non-negative");
        }
    }
}
