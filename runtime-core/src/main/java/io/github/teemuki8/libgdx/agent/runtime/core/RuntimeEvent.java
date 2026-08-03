package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable structured fact emitted explicitly by game code. */
public record RuntimeEvent(
        EventId id,
        FrameId frameId,
        EventType type,
        Optional<EntityId> subject,
        Optional<EntityId> source,
        FactMetadata metadata,
        List<RuntimeValue.Field> attributes,
        List<Truncation> truncations) {
    /** Validates and orders attributes. */
    public RuntimeEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(frameId, "frameId");
        Objects.requireNonNull(type, "type");
        subject = Objects.requireNonNull(subject, "subject");
        source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(metadata, "metadata");
        var copy = new ArrayList<>(Objects.requireNonNull(attributes, "attributes"));
        copy.sort(Comparator.comparing(RuntimeValue.Field::name));
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).name().equals(copy.get(index).name())) {
                throw new IllegalArgumentException(
                        "duplicate event attribute: " + copy.get(index).name());
            }
        }
        attributes = List.copyOf(copy);
        truncations = List.copyOf(Objects.requireNonNull(truncations, "truncations"));
    }

    /** Compatibility constructor for an event without fact metadata. */
    public RuntimeEvent(EventId id, FrameId frameId, EventType type,
            Optional<EntityId> subject, Optional<EntityId> source,
            List<RuntimeValue.Field> attributes, List<Truncation> truncations) {
        this(id, frameId, type, subject, source, FactMetadata.empty(), attributes, truncations);
    }
}
