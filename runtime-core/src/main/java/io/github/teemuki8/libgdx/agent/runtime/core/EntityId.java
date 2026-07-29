package io.github.teemuki8.libgdx.agent.runtime.core;

/** Stable game-supplied entity identifier. */
public record EntityId(String value) implements Comparable<EntityId> {
    /** Validates the identifier. */
    public EntityId {
        value = IdentifierSupport.validate(value, "entityId");
    }

    /** Creates a validated identifier. */
    public static EntityId of(String value) {
        return new EntityId(value);
    }

    @Override
    public int compareTo(EntityId other) {
        return value.compareTo(other.value);
    }
}
