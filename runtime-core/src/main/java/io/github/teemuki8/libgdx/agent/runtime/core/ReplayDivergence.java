package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** First complete observable difference between recorded and replayed evidence. */
public record ReplayDivergence(ReplayPhase phase, OptionalLong epochTick,
        Optional<SimulationTickId> referenceSimulationTickId,
        Optional<SimulationTickId> replaySimulationTickId,
        ExecutionEpochId referenceExecutionEpochId, ExecutionEpochId replayExecutionEpochId,
        FrameId referenceFrameId, FrameId replayFrameId, DeterminismDifference difference) {
    /** Validates complete evidence for either baseline or positive-tick divergence. */
    public ReplayDivergence {
        Objects.requireNonNull(phase, "phase");
        epochTick = Objects.requireNonNull(epochTick, "epochTick");
        referenceSimulationTickId = Objects.requireNonNull(
                referenceSimulationTickId, "referenceSimulationTickId");
        replaySimulationTickId = Objects.requireNonNull(
                replaySimulationTickId, "replaySimulationTickId");
        Objects.requireNonNull(referenceExecutionEpochId, "referenceExecutionEpochId");
        Objects.requireNonNull(replayExecutionEpochId, "replayExecutionEpochId");
        Objects.requireNonNull(referenceFrameId, "referenceFrameId");
        Objects.requireNonNull(replayFrameId, "replayFrameId");
        Objects.requireNonNull(difference, "difference");

        boolean tickEvidence = epochTick.isPresent()
                && referenceSimulationTickId.isPresent() && replaySimulationTickId.isPresent();
        if (phase == ReplayPhase.BASELINE) {
            if (epochTick.isPresent() || referenceSimulationTickId.isPresent()
                    || replaySimulationTickId.isPresent()) {
                throw new IllegalArgumentException(
                        "baseline divergence must not contain simulation tick evidence");
            }
        } else if (!tickEvidence || epochTick.orElseThrow() <= 0) {
            throw new IllegalArgumentException(
                    "simulation tick divergence requires complete positive-tick evidence");
        }
    }
}
