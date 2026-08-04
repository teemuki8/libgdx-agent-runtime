package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;

/** Immutable discoverable simulation-control state, conditions, and effective limits. */
public record SimulationControlDescriptor(boolean available, boolean paused,
        List<ControlConditionDescriptor> conditions, ControlLimits limits) {
    /** Defensively copies descriptor data. */
    public SimulationControlDescriptor {
        conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions"));
        Objects.requireNonNull(limits, "limits");
        if (!available && paused) {
            throw new IllegalArgumentException("unavailable simulation control cannot be paused");
        }
    }
}
