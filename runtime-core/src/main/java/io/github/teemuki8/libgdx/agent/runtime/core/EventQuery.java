package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Exact and prefix filters for structured events. */
public record EventQuery(
        FrameRange range,
        Optional<String> type,
        boolean typePrefix,
        Optional<EntityId> subject,
        Optional<EntityId> source,
        Optional<String> sourceSubsystem,
        Optional<String> correlationId,
        int limit) {
    /** Validates filters and limit. */
    public EventQuery {
        Objects.requireNonNull(range, "range");
        type = Objects.requireNonNull(type, "type");
        type.ifPresent(value -> IdentifierSupport.validate(value, "event type filter"));
        subject = Objects.requireNonNull(subject, "subject");
        source = Objects.requireNonNull(source, "source");
        sourceSubsystem = Objects.requireNonNull(sourceSubsystem, "sourceSubsystem");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        sourceSubsystem.ifPresent(value -> IdentifierSupport.validate(value, "source subsystem"));
        correlationId.ifPresent(value -> IdentifierSupport.validate(value, "correlation id"));
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    /** Compatibility constructor without metadata filters. */
    public EventQuery(FrameRange range, Optional<String> type, boolean typePrefix,
            Optional<EntityId> subject, Optional<EntityId> source, int limit) {
        this(range, type, typePrefix, subject, source, Optional.empty(), Optional.empty(), limit);
    }
}
