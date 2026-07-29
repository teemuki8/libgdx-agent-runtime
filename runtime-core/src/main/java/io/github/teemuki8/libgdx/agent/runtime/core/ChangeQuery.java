package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Exact filters for property-change queries. */
public record ChangeQuery(
        FrameRange range,
        Optional<EntityId> entityId,
        Optional<EntityType> entityType,
        Optional<String> property,
        int limit) {
    /** Validates filters and limit. */
    public ChangeQuery {
        Objects.requireNonNull(range, "range");
        entityId = Objects.requireNonNull(entityId, "entityId");
        entityType = Objects.requireNonNull(entityType, "entityType");
        property = Objects.requireNonNull(property, "property");
        property.ifPresent(value -> IdentifierSupport.validate(value, "property"));
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}
