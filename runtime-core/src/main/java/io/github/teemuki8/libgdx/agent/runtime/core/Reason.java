package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Stable machine-readable reason with optional bounded description. */
public record Reason(String code, Optional<String> description) {
    /** Validates reason fields. */
    public Reason {
        IdentifierSupport.validate(code, "reason code");
        description = Objects.requireNonNull(description, "description");
    }

    /** Creates a reason without free-form description. */
    public static Reason of(String code) {
        return new Reason(code, Optional.empty());
    }

    /** Creates a reason with human-facing supplementary description. */
    public static Reason of(String code, String description) {
        return new Reason(code, Optional.of(Objects.requireNonNull(description, "description")));
    }
}
