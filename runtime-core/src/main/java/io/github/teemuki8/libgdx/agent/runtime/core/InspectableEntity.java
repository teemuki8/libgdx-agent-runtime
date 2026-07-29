package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One dynamic-source entity description evaluated only during frame capture. */
public final class InspectableEntity {
    private final EntityId id;
    private final EntityType type;
    private final Supplier<String> displayName;
    private final List<EntityInspector.PropertyProvider> properties;

    InspectableEntity(EntityId id, EntityType type, Supplier<String> displayName,
            List<EntityInspector.PropertyProvider> properties) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.properties = List.copyOf(Objects.requireNonNull(properties, "properties"));
    }

    /** Builds one dynamic-source description. */
    public static InspectableEntity of(EntityId id, EntityType type, Supplier<String> displayName,
            Consumer<EntityInspector> declaration) {
        EntityInspector inspector = new EntityInspector();
        Objects.requireNonNull(declaration, "declaration").accept(inspector);
        return new InspectableEntity(id, type, displayName, inspector.build());
    }

    EntityId id() {
        return id;
    }

    EntityType type() {
        return type;
    }

    Supplier<String> displayName() {
        return displayName;
    }

    List<EntityInspector.PropertyProvider> properties() {
        return properties;
    }
}
