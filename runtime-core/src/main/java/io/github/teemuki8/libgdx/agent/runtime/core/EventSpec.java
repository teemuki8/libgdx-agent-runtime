package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Mutable capture-thread-local builder consumed synchronously by {@link AgentRuntime#emit}. */
public final class EventSpec {
    private final EventType type;
    private EntityId subject;
    private EntityId source;
    private FactMetadata metadata = FactMetadata.empty();
    private final List<RuntimeValue.Field> attributes = new ArrayList<>();

    private EventSpec(EventType type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /** Starts an event description. */
    public static EventSpec type(String type) {
        return new EventSpec(EventType.of(type));
    }

    /** Sets the optional subject. */
    public EventSpec subject(EntityId value) {
        subject = Objects.requireNonNull(value, "subject");
        return this;
    }

    /** Sets the optional source. */
    public EventSpec source(EntityId value) {
        source = Objects.requireNonNull(value, "source");
        return this;
    }

    /** Sets the explicit application-provided source subsystem. */
    public EventSpec sourceSubsystem(String value) {
        metadata = metadata.withSourceSubsystem(value);
        return this;
    }

    /** Sets an unverified, application-provided source-location label. */
    public EventSpec sourceLocation(String value) {
        metadata = metadata.withSourceLocation(value);
        return this;
    }

    /** Sets an explicit correlation identifier without implying inferred causality. */
    public EventSpec correlationId(String value) {
        metadata = metadata.withCorrelationId(value);
        return this;
    }

    /** Adds one structured attribute. */
    public EventSpec attribute(String name, RuntimeValue value) {
        if (attributes.stream().anyMatch(attribute -> attribute.name().equals(name))) {
            throw new IllegalArgumentException("duplicate event attribute: " + name);
        }
        attributes.add(RuntimeValues.field(name, value));
        return this;
    }

    EventType eventType() {
        return type;
    }

    Optional<EntityId> optionalSubject() {
        return Optional.ofNullable(subject);
    }

    Optional<EntityId> optionalSource() {
        return Optional.ofNullable(source);
    }

    List<RuntimeValue.Field> attributeList() {
        return List.copyOf(attributes);
    }

    FactMetadata metadata() {
        return metadata;
    }
}
