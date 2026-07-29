package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable ordered state for one deliberately registered entity. */
public record EntitySnapshot(
        EntityId id,
        EntityType type,
        Optional<String> displayName,
        List<RuntimeValue.Field> properties,
        List<Truncation> truncations) {
    /** Validates and defensively copies the complete entity state. */
    public EntitySnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        displayName = Objects.requireNonNull(displayName, "displayName");
        var copy = new ArrayList<>(Objects.requireNonNull(properties, "properties"));
        copy.forEach(property -> Objects.requireNonNull(property, "property"));
        copy.sort(Comparator.comparing(RuntimeValue.Field::name));
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).name().equals(copy.get(index).name())) {
                throw new IllegalArgumentException(
                        "duplicate entity property: " + copy.get(index).name());
            }
        }
        properties = List.copyOf(copy);
        truncations = List.copyOf(Objects.requireNonNull(truncations, "truncations"));
    }

    /** Finds one property by exact name. */
    public Optional<RuntimeValue> property(String name) {
        Objects.requireNonNull(name, "name");
        return properties.stream().filter(property -> property.name().equals(name))
                .map(RuntimeValue.Field::value).findFirst();
    }

    /** Reports whether any entity dimension was truncated. */
    public boolean truncated() {
        return !truncations.isEmpty();
    }
}
