package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Stable metadata and closed parameter schema for one semantic action. */
public record ActionDescriptor(
        String id, Optional<String> description, List<ActionParameter> parameters) {
    public static final int MAX_DESCRIPTION_LENGTH = 512;

    public ActionDescriptor {
        IdentifierSupport.validate(id, "action id");
        description = Objects.requireNonNull(description, "description");
        description.ifPresent(value -> {
            if (value.isBlank() || value.length() > MAX_DESCRIPTION_LENGTH) {
                throw new IllegalArgumentException("action description is invalid");
            }
        });
        ArrayList<ActionParameter> copy = new ArrayList<>(parameters);
        copy.sort(Comparator.comparing(ActionParameter::name));
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).name().equals(copy.get(index).name())) {
                throw new IllegalArgumentException("duplicate action parameter");
            }
        }
        parameters = List.copyOf(copy);
    }
}
