package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** One automatic structural difference between adjacent captured frames. */
public record PropertyChange(
        FrameId frameId,
        EntityId entityId,
        EntityType entityType,
        ChangeKind kind,
        Optional<String> property,
        Optional<RuntimeValue> before,
        Optional<RuntimeValue> after,
        ChangeCause cause) {
    /** Validates the difference shape. */
    public PropertyChange {
        Objects.requireNonNull(frameId, "frameId");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(kind, "kind");
        property = Objects.requireNonNull(property, "property");
        before = Objects.requireNonNull(before, "before");
        after = Objects.requireNonNull(after, "after");
        Objects.requireNonNull(cause, "cause");
        property.ifPresent(value -> IdentifierSupport.validate(value, "property"));
        switch (kind) {
            case ENTITY_ADDED -> requireShape(property, before, after, false, false, false);
            case ENTITY_REMOVED -> requireShape(property, before, after, false, false, false);
            case PROPERTY_ADDED -> requireShape(property, before, after, true, false, true);
            case PROPERTY_REMOVED -> requireShape(property, before, after, true, true, false);
            case PROPERTY_CHANGED -> requireShape(property, before, after, true, true, true);
        }
    }

    private static void requireShape(Optional<String> property, Optional<RuntimeValue> before,
            Optional<RuntimeValue> after, boolean hasProperty, boolean hasBefore, boolean hasAfter) {
        if (property.isPresent() != hasProperty || before.isPresent() != hasBefore
                || after.isPresent() != hasAfter) {
            throw new IllegalArgumentException("property change fields do not match kind");
        }
    }
}
