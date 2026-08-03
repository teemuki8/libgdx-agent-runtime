package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Bounded lookup result that distinguishes unknown IDs from retained expired IDs. */
public record CommandLookup(Kind kind, Optional<CommandStatus> status) {
    public enum Kind { FOUND, EXPIRED, UNKNOWN }

    /** Validates lookup consistency. */
    public CommandLookup {
        Objects.requireNonNull(kind, "kind");
        status = Objects.requireNonNull(status, "status");
        if ((kind == Kind.FOUND) != status.isPresent()) {
            throw new IllegalArgumentException("only found lookups contain status");
        }
    }

    static CommandLookup found(CommandStatus status) {
        return new CommandLookup(Kind.FOUND, Optional.of(status));
    }

    static CommandLookup missing(Kind kind) {
        return new CommandLookup(kind, Optional.empty());
    }
}
