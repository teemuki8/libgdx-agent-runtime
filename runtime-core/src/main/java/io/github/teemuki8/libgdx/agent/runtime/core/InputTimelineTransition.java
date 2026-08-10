package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** One explicit registered-input transition before a timeline-local simulation tick. */
public record InputTimelineTransition(String transitionId, int timelineTick,
        String inputId, RuntimeValue.ObjectValue parameters) {
    /** Validates identity, a positive local tick, and closed scalar parameters. */
    public InputTimelineTransition {
        IdentifierSupport.validate(transitionId, "input timeline transition id");
        if (timelineTick <= 0) {
            throw new IllegalArgumentException("input timeline tick must be positive");
        }
        IdentifierSupport.validate(inputId, "input timeline input id");
        Objects.requireNonNull(parameters, "parameters");
        try {
            SimulationAssertionValueBounds.validate(parameters);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "input timeline parameters exceed bounded runtime-value limits", failure);
        }
        parameters.fields().forEach(field -> {
            if (!(field.value() instanceof RuntimeValue.BooleanValue
                    || field.value() instanceof RuntimeValue.IntegerValue
                    || field.value() instanceof RuntimeValue.DecimalValue
                    || field.value() instanceof RuntimeValue.StringValue
                    || field.value() instanceof RuntimeValue.EnumValue)) {
                throw new IllegalArgumentException(
                        "input timeline parameters must contain only closed scalar values");
            }
        });
    }
}
