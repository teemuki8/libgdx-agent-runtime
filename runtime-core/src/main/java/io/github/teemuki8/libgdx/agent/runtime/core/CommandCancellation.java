package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** Result of a cancellation request; only queued commands can accept cancellation. */
public record CommandCancellation(boolean accepted, CommandLookup command) {
    public CommandCancellation {
        Objects.requireNonNull(command, "command");
        if (accepted && command.kind() != CommandLookup.Kind.FOUND) {
            throw new IllegalArgumentException("accepted cancellation requires retained status");
        }
    }
}
