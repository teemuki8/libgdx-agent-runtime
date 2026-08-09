package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Immutable current state of the application-owned fixed-step accumulator. */
public record FixedStepSimulationState(boolean configured,
        Optional<FixedStepSimulationConfiguration> configuration,
        long accumulatorRemainderNanos, double interpolationAlpha, boolean paused,
        OptionalLong latestUpdateSequence, int retainedUpdateReports) {
    /** Validates state evidence. */
    public FixedStepSimulationState {
        configuration = Objects.requireNonNull(configuration, "configuration");
        latestUpdateSequence = Objects.requireNonNull(latestUpdateSequence, "latestUpdateSequence");
        if (configured != configuration.isPresent() || accumulatorRemainderNanos < 0
                || !Double.isFinite(interpolationAlpha) || interpolationAlpha < 0.0
                || interpolationAlpha >= 1.0 || retainedUpdateReports < 0) {
            throw new IllegalArgumentException("invalid fixed-step simulation state");
        }
        if (!configured && (accumulatorRemainderNanos != 0 || interpolationAlpha != 0.0
                || retainedUpdateReports != 0)) {
            throw new IllegalArgumentException("unconfigured fixed-step state contains evidence");
        }
    }
}
