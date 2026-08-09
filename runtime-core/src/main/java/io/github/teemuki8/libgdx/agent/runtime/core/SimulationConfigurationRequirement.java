package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** One exact immutable fact required before every simulation determinism run. */
public record SimulationConfigurationRequirement(
        EntityId entityId, String property, RuntimeValue expected) {
    /** Validates the stable fact identity and hard-bounded expected value. */
    public SimulationConfigurationRequirement {
        Objects.requireNonNull(entityId, "entityId");
        IdentifierSupport.validate(property, "configuration property");
        Objects.requireNonNull(expected, "expected");
        SimulationAssertionValueBounds.validate(expected);
    }
}
