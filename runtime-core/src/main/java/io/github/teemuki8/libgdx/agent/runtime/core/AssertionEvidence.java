package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** One bounded supporting observable for a declarative assertion result. */
public record AssertionEvidence(FrameId frameId, String kind, Optional<EntityId> entityId,
        Optional<String> property, Optional<RuntimeValue> observed) {
    /** Validates immutable evidence fields. */
    public AssertionEvidence {
        Objects.requireNonNull(frameId, "frameId");
        IdentifierSupport.validate(kind, "evidence kind");
        entityId = Objects.requireNonNull(entityId, "entityId");
        property = Objects.requireNonNull(property, "property");
        property.ifPresent(value -> IdentifierSupport.validate(value, "property"));
        observed = Objects.requireNonNull(observed, "observed");
    }
}
