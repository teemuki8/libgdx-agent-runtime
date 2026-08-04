package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** Stable bounded metadata for one application-registered semantic condition. */
public record ControlConditionDescriptor(String id, String description) {
    /** Validates descriptor fields. */
    public ControlConditionDescriptor {
        IdentifierSupport.validate(id, "condition id");
        Objects.requireNonNull(description, "description");
        if (description.isBlank() || description.length() > 512) {
            throw new IllegalArgumentException("condition description is invalid");
        }
    }
}
