package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** One top-level boolean property required for complete simulation assertion evidence. */
public record SimulationEvidenceRequirement(EntityId entityId, String property) {
    /** Validates the closed requirement. */
    public SimulationEvidenceRequirement {
        Objects.requireNonNull(entityId, "entityId");
        IdentifierSupport.validate(property, "property");
    }
}
