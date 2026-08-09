package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** Hard bounds for caller-supplied and returned simulation assertion values. */
final class SimulationAssertionValueBounds {
    private static final int MAX_DEPTH = 16;
    private static final int MAX_NODES = 1_024;
    private static final int MAX_COLLECTION_LENGTH = 256;
    private static final int MAX_STRING_LENGTH = 4_096;

    private SimulationAssertionValueBounds() {}

    static void validate(RuntimeValue value) {
        Objects.requireNonNull(value, "value");
        validate(value, 1, new int[] {0});
    }

    private static void validate(RuntimeValue value, int depth, int[] nodes) {
        if (depth > MAX_DEPTH || ++nodes[0] > MAX_NODES) {
            throw new IllegalArgumentException("simulation assertion value exceeds its hard bound");
        }
        switch (value) {
            case RuntimeValue.StringValue string -> validateString(string.value());
            case RuntimeValue.EnumValue enumeration -> validateString(enumeration.value());
            case RuntimeValue.ListValue list -> {
                validateCollection(list.values().size());
                list.values().forEach(child -> validate(child, depth + 1, nodes));
            }
            case RuntimeValue.ObjectValue object -> {
                validateCollection(object.fields().size());
                object.fields().forEach(field -> {
                    validateString(field.name());
                    validate(field.value(), depth + 1, nodes);
                });
            }
            default -> {
                // Canonical scalar and vector values are bounded by their constructors.
            }
        }
    }

    private static void validateCollection(int size) {
        if (size > MAX_COLLECTION_LENGTH) {
            throw new IllegalArgumentException(
                    "simulation assertion collection exceeds its hard bound");
        }
    }

    private static void validateString(String value) {
        if (value.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("simulation assertion string exceeds its hard bound");
        }
    }
}
