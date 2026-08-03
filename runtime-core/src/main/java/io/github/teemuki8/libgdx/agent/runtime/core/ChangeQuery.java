package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Exact filters for property-change queries. */
public record ChangeQuery(
        FrameRange range,
        Optional<EntityId> entityId,
        Optional<EntityType> entityType,
        Optional<String> property,
        Optional<String> sourceSubsystem,
        Optional<String> correlationId,
        int limit) {
    /** Validates filters and limit. */
    public ChangeQuery {
        Objects.requireNonNull(range, "range");
        entityId = Objects.requireNonNull(entityId, "entityId");
        entityType = Objects.requireNonNull(entityType, "entityType");
        property = Objects.requireNonNull(property, "property");
        property.ifPresent(value -> IdentifierSupport.validate(value, "property"));
        sourceSubsystem = Objects.requireNonNull(sourceSubsystem, "sourceSubsystem");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        sourceSubsystem.ifPresent(value -> IdentifierSupport.validate(value, "source subsystem"));
        correlationId.ifPresent(value -> IdentifierSupport.validate(value, "correlation id"));
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    /** Compatibility constructor without metadata filters. */
    public ChangeQuery(FrameRange range, Optional<EntityId> entityId,
            Optional<EntityType> entityType, Optional<String> property, int limit) {
        this(range, entityId, entityType, property, Optional.empty(), Optional.empty(), limit);
    }
}
