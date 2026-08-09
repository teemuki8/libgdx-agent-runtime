package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Immutable current simulation timeline state. */
public record SimulationState(boolean configured, OptionalLong configuredFixedStepNanos,
        ExecutionEpochId executionEpochId, long attemptedEpochTicks,
        long completedEpochTicks, long epochSimulationTimeNanos,
        Optional<SimulationTickId> latestTickId, OptionalLong lastRuntimeSuppliedDeltaNanos,
        OptionalLong lastExecutedDeltaNanos, boolean paused, SimulationTimelineLimits limits) {
    /** Validates state evidence. */
    public SimulationState {
        configuredFixedStepNanos = Objects.requireNonNull(
                configuredFixedStepNanos, "configuredFixedStepNanos");
        Objects.requireNonNull(executionEpochId, "executionEpochId");
        if (attemptedEpochTicks < 0 || completedEpochTicks < 0
                || completedEpochTicks > attemptedEpochTicks || epochSimulationTimeNanos < 0) {
            throw new IllegalArgumentException("invalid simulation state");
        }
        latestTickId = Objects.requireNonNull(latestTickId, "latestTickId");
        lastRuntimeSuppliedDeltaNanos = Objects.requireNonNull(
                lastRuntimeSuppliedDeltaNanos, "lastRuntimeSuppliedDeltaNanos");
        lastExecutedDeltaNanos = Objects.requireNonNull(
                lastExecutedDeltaNanos, "lastExecutedDeltaNanos");
        if (configuredFixedStepNanos.isPresent()
                && configuredFixedStepNanos.orElseThrow() <= 0
                || lastRuntimeSuppliedDeltaNanos.isPresent()
                        && lastRuntimeSuppliedDeltaNanos.orElseThrow() < 0
                || lastExecutedDeltaNanos.isPresent()
                        && lastExecutedDeltaNanos.orElseThrow() < 0) {
            throw new IllegalArgumentException("simulation state contains an invalid delta");
        }
        Objects.requireNonNull(limits, "limits");
        if (configured != configuredFixedStepNanos.isPresent()) {
            throw new IllegalArgumentException("simulation configuration state is inconsistent");
        }
    }
}
