package io.github.teemuki8.libgdx.agent.runtime.core;

/** Stable game-supplied event type. */
public record EventType(String value) {
    /** Validates the identifier. */
    public EventType {
        value = IdentifierSupport.validate(value, "eventType");
    }

    /** Creates a validated type. */
    public static EventType of(String value) {
        return new EventType(value);
    }
}
