package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Explicit semantic action registration specification. */
public final class ActionSpec {
    private final ActionDescriptor descriptor;
    private final Consumer<ActionParameters> handler;

    private ActionSpec(ActionDescriptor descriptor, Consumer<ActionParameters> handler) {
        this.descriptor = descriptor;
        this.handler = handler;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    ActionDescriptor descriptor() {
        return descriptor;
    }

    Consumer<ActionParameters> handler() {
        return handler;
    }

    public static final class Builder {
        private final String id;
        private String description;
        private final List<ActionParameter> parameters = new ArrayList<>();
        private Consumer<ActionParameters> handler;

        private Builder(String id) {
            this.id = IdentifierSupport.validate(id, "action id");
        }

        public Builder description(String value) {
            description = Objects.requireNonNull(value, "description");
            return this;
        }

        public Builder requiredEntityId(String name) {
            return parameter(name, ActionParameterType.ENTITY_ID, true);
        }

        public Builder requiredString(String name) {
            return parameter(name, ActionParameterType.STRING, true);
        }

        public Builder requiredInteger(String name) {
            return parameter(name, ActionParameterType.INTEGER, true);
        }

        public Builder requiredDecimal(String name) {
            return parameter(name, ActionParameterType.DECIMAL, true);
        }

        public Builder requiredBoolean(String name) {
            return parameter(name, ActionParameterType.BOOLEAN, true);
        }

        public Builder optionalString(String name) {
            return parameter(name, ActionParameterType.STRING, false);
        }

        public Builder parameter(String name, ActionParameterType type, boolean required) {
            parameters.add(new ActionParameter(name, type, required, Optional.empty()));
            return this;
        }

        public Builder handler(Consumer<ActionParameters> value) {
            handler = Objects.requireNonNull(value, "handler");
            return this;
        }

        public ActionSpec build() {
            return new ActionSpec(new ActionDescriptor(id, Optional.ofNullable(description),
                    parameters), Objects.requireNonNull(handler, "handler"));
        }
    }
}
