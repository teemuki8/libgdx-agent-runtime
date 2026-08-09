package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** One bounded supporting observable correlated to an exact simulation tick and runtime frame. */
public record SimulationAssertionEvidence(Optional<SimulationTickId> simulationTickId,
        ExecutionEpochId executionEpochId, long epochTick, Optional<FrameId> frameId, String kind,
        Optional<EntityId> entityId, Optional<String> property, Optional<RuntimeValue> observed) {
    /** Validates immutable evidence fields. */
    public SimulationAssertionEvidence {
        simulationTickId = Objects.requireNonNull(simulationTickId, "simulationTickId");
        Objects.requireNonNull(executionEpochId, "executionEpochId");
        if (epochTick <= 0) {
            throw new IllegalArgumentException("simulation assertion epoch tick must be positive");
        }
        frameId = Objects.requireNonNull(frameId, "frameId");
        if (frameId.isPresent() && simulationTickId.isEmpty()) {
            throw new IllegalArgumentException("runtime frame evidence requires a simulation tick");
        }
        IdentifierSupport.validate(kind, "evidence kind");
        entityId = Objects.requireNonNull(entityId, "entityId");
        property = Objects.requireNonNull(property, "property");
        property.ifPresent(value -> IdentifierSupport.validate(value, "property"));
        observed = Objects.requireNonNull(observed, "observed");
    }
}
