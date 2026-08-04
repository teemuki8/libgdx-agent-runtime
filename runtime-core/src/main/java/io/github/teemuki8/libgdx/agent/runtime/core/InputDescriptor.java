package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Stable metadata, closed parameter schema, and recording policy for one input type. */
public record InputDescriptor(String id, Optional<String> description,
        List<ActionParameter> parameters, InputRedactionPolicy redactionPolicy) {
    /** Maximum bounded description length. */
    public static final int MAX_DESCRIPTION_LENGTH = 512;

    /** Validates and deterministically orders the closed schema. */
    public InputDescriptor {
        IdentifierSupport.validate(id, "input id");
        description = Objects.requireNonNull(description, "description");
        description.ifPresent(value -> {
            if (value.isBlank() || value.length() > MAX_DESCRIPTION_LENGTH) {
                throw new IllegalArgumentException("input description is invalid");
            }
        });
        ArrayList<ActionParameter> copy = new ArrayList<>(
                Objects.requireNonNull(parameters, "parameters"));
        copy.sort(Comparator.comparing(ActionParameter::name));
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).name().equals(copy.get(index).name())) {
                throw new IllegalArgumentException("duplicate input parameter");
            }
        }
        parameters = List.copyOf(copy);
        Objects.requireNonNull(redactionPolicy, "redactionPolicy");
    }
}
