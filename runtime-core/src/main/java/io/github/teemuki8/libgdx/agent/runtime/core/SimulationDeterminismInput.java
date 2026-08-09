package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** One registered runtime input repeated before an exact epoch-relative simulation tick. */
public record SimulationDeterminismInput(
        long epochTick, String inputId, RuntimeValue.ObjectValue parameters) {
    /** Validates the positive tick, stable input ID, and bounded immutable parameters. */
    public SimulationDeterminismInput {
        if (epochTick <= 0) {
            throw new IllegalArgumentException("determinism input tick must be positive");
        }
        IdentifierSupport.validate(inputId, "determinism input id");
        Objects.requireNonNull(parameters, "parameters");
        SimulationAssertionValueBounds.validate(parameters);
    }
}
