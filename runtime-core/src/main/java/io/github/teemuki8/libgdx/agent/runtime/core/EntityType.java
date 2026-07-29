package io.github.teemuki8.libgdx.agent.runtime.core;

/** Stable game-supplied entity type. */
public record EntityType(String value) {
    /** Validates the identifier. */
    public EntityType {
        value = IdentifierSupport.validate(value, "entityType");
    }

    /** Creates a validated type. */
    public static EntityType of(String value) {
        return new EntityType(value);
    }
}
