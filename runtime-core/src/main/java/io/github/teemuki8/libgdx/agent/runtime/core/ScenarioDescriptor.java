package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Stable application-provided metadata for a resettable scenario. */
public record ScenarioDescriptor(String id, Optional<String> description) {
    public static final int MAX_DESCRIPTION_LENGTH = 512;

    public ScenarioDescriptor {
        IdentifierSupport.validate(id, "scenario id");
        description = Objects.requireNonNull(description, "description");
        description.ifPresent(value -> {
            if (value.isBlank() || value.length() > MAX_DESCRIPTION_LENGTH) {
                throw new IllegalArgumentException(
                        "scenario description must be non-blank and at most "
                                + MAX_DESCRIPTION_LENGTH + " characters");
            }
        });
    }

    public ScenarioDescriptor(String id, String description) {
        this(id, Optional.ofNullable(description));
    }
}
