package io.github.teemuki8.libgdx.agent.runtime.core;

/** Validates values against configured trust-boundary limits. */
public final class RuntimeValueValidator {
    private RuntimeValueValidator() {}

    /** Validates the complete value tree. */
    public static void validate(RuntimeValue value, RuntimeLimits limits) {
        validate(value, limits, 1);
    }

    private static void validate(RuntimeValue value, RuntimeLimits limits, int depth) {
        if (depth > limits.nestingDepth()) {
            throw new IllegalArgumentException("runtime value exceeds nesting depth");
        }
        switch (value) {
            case RuntimeValue.StringValue string -> validateString(string.value(), limits);
            case RuntimeValue.EnumValue enumeration -> validateString(enumeration.value(), limits);
            case RuntimeValue.ListValue list -> {
                if (list.values().size() > limits.collectionLength()) {
                    throw new IllegalArgumentException("runtime list exceeds collection limit");
                }
                list.values().forEach(child -> validate(child, limits, depth + 1));
            }
            case RuntimeValue.ObjectValue object -> {
                if (object.fields().size() > limits.collectionLength()) {
                    throw new IllegalArgumentException("runtime object exceeds collection limit");
                }
                object.fields().forEach(field -> {
                    validateString(field.name(), limits);
                    validate(field.value(), limits, depth + 1);
                });
            }
            default -> {
                // Scalar values are valid by construction.
            }
        }
    }

    private static void validateString(String value, RuntimeLimits limits) {
        if (value.length() > limits.stringLength()) {
            throw new IllegalArgumentException("runtime string exceeds string limit");
        }
    }
}
