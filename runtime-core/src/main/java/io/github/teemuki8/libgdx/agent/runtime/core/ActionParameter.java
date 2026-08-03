package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** One explicitly declared field in a closed action parameter schema. */
public record ActionParameter(
        String name, ActionParameterType type, boolean required, Optional<String> description) {
    public ActionParameter {
        IdentifierSupport.validate(name, "action parameter name");
        Objects.requireNonNull(type, "type");
        description = Objects.requireNonNull(description, "description");
        description.ifPresent(value -> {
            if (value.isBlank() || value.length() > ActionDescriptor.MAX_DESCRIPTION_LENGTH) {
                throw new IllegalArgumentException("action parameter description is invalid");
            }
        });
    }
}
