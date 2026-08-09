package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Immutable evidence for one attempted application simulation tick. */
public record SimulationTick(SimulationTickId simulationTickId,
        ExecutionEpochId executionEpochId, long epochTick,
        OptionalLong configuredFixedStepNanos, long runtimeSuppliedDeltaNanos,
        OptionalLong executedDeltaNanos, long epochSimulationTimeNanos,
        Optional<FrameId> resultingFrameId, SimulationTickSource source,
        SimulationTickOutcome outcome, SimulationMutationOutcome mutationOutcome,
        Optional<String> diagnostic) {
    /** Validates and copies tick evidence. */
    public SimulationTick {
        Objects.requireNonNull(simulationTickId, "simulationTickId");
        Objects.requireNonNull(executionEpochId, "executionEpochId");
        if (epochTick <= 0 || runtimeSuppliedDeltaNanos < 0 || epochSimulationTimeNanos < 0) {
            throw new IllegalArgumentException("invalid simulation tick evidence");
        }
        configuredFixedStepNanos = Objects.requireNonNull(
                configuredFixedStepNanos, "configuredFixedStepNanos");
        if (configuredFixedStepNanos.isPresent()
                && configuredFixedStepNanos.orElseThrow() <= 0) {
            throw new IllegalArgumentException("configured fixed step must be positive");
        }
        executedDeltaNanos = Objects.requireNonNull(executedDeltaNanos, "executedDeltaNanos");
        if (executedDeltaNanos.isPresent() && executedDeltaNanos.orElseThrow() < 0) {
            throw new IllegalArgumentException("executed delta must be non-negative");
        }
        resultingFrameId = Objects.requireNonNull(resultingFrameId, "resultingFrameId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(mutationOutcome, "mutationOutcome");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        boolean requiresExecutedDelta = outcome == SimulationTickOutcome.COMPLETED
                || outcome == SimulationTickOutcome.DELTA_MISMATCH
                || outcome == SimulationTickOutcome.TIME_LIMIT_EXCEEDED;
        if (requiresExecutedDelta && executedDeltaNanos.isEmpty()
                || (outcome == SimulationTickOutcome.UNACKNOWLEDGED
                        || outcome == SimulationTickOutcome.REPORTED_DELTA_INVALID
                        || outcome == SimulationTickOutcome.CALLBACK_FAILED
                        || outcome == SimulationTickOutcome.CALLBACK_AND_CAPTURE_FAILED)
                        && executedDeltaNanos.isPresent()) {
            throw new IllegalArgumentException("simulation tick outcome and executed delta disagree");
        }
        if ((outcome == SimulationTickOutcome.CALLBACK_FAILED
                || outcome == SimulationTickOutcome.CALLBACK_AND_CAPTURE_FAILED)
                && mutationOutcome != SimulationMutationOutcome.UNKNOWN) {
            throw new IllegalArgumentException("callback failure must report unknown mutation outcome");
        }
        if (outcome == SimulationTickOutcome.COMPLETED && diagnostic.isPresent()) {
            throw new IllegalArgumentException("completed simulation tick cannot have a diagnostic");
        }
    }
}
