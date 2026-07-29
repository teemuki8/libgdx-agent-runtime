package io.github.teemuki8.libgdx.agent.runtime.core;

/** Ordered event identifier within a session. */
public record EventId(long value) {
    /** Validates a positive sequence. */
    public EventId {
        if (value <= 0) {
            throw new IllegalArgumentException("eventId must be positive");
        }
    }
}
