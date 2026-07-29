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
        int limit) {
    /** Validates filters and limit. */
    public EventQuery {
        Objects.requireNonNull(range, "range");
        type = Objects.requireNonNull(type, "type");
        type.ifPresent(value -> IdentifierSupport.validate(value, "event type filter"));
        subject = Objects.requireNonNull(subject, "subject");
        source = Objects.requireNonNull(source, "source");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}
