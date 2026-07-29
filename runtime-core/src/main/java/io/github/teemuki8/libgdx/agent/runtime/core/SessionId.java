package io.github.teemuki8.libgdx.agent.runtime.core;

/** Stable identifier for one runtime lifecycle. */
public record SessionId(String value) {
    /** Validates the identifier. */
    public SessionId {
        value = IdentifierSupport.validate(value, "sessionId");
    }

    /** Creates a validated identifier. */
    public static SessionId of(String value) {
        return new SessionId(value);
    }
}
