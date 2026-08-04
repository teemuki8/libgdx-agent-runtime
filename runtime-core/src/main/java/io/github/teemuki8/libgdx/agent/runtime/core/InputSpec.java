package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Explicit application-owned input type registration specification. */
public final class InputSpec {
    private final InputDescriptor descriptor;
    private final Consumer<InputParameters> handler;

    private InputSpec(InputDescriptor descriptor, Consumer<InputParameters> handler) {
        this.descriptor = descriptor;
        this.handler = handler;
    }

    /** Starts a closed input type specification. */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    InputDescriptor descriptor() {
        return descriptor;
    }

    Consumer<InputParameters> handler() {
        return handler;
    }

    /** Builder for one explicit input type. */
    public static final class Builder {
        private final String id;
        private String description;
        private final List<ActionParameter> parameters = new ArrayList<>();
        private InputRedactionPolicy redactionPolicy = InputRedactionPolicy.INCLUDE_PARAMETERS;
        private Consumer<InputParameters> handler;

        private Builder(String id) {
            this.id = IdentifierSupport.validate(id, "input id");
        }

        /** Sets bounded descriptive metadata. */
        public Builder description(String value) {
            description = Objects.requireNonNull(value, "description");
            return this;
        }

        /** Adds a required entity identifier. */
        public Builder requiredEntityId(String name) {
            return parameter(name, ActionParameterType.ENTITY_ID, true);
        }

        /** Adds a required string. */
        public Builder requiredString(String name) {
            return parameter(name, ActionParameterType.STRING, true);
        }

        /** Adds a required integer. */
        public Builder requiredInteger(String name) {
            return parameter(name, ActionParameterType.INTEGER, true);
        }

        /** Adds a required decimal. */
        public Builder requiredDecimal(String name) {
            return parameter(name, ActionParameterType.DECIMAL, true);
        }

        /** Adds a required boolean. */
        public Builder requiredBoolean(String name) {
            return parameter(name, ActionParameterType.BOOLEAN, true);
        }

        /** Adds an optional string. */
        public Builder optionalString(String name) {
            return parameter(name, ActionParameterType.STRING, false);
        }

        /** Adds one closed scalar parameter. */
        public Builder parameter(String name, ActionParameterType type, boolean required) {
            parameters.add(new ActionParameter(name, type, required, Optional.empty()));
            return this;
        }

        /** Selects how parameters appear in retained recording evidence. */
        public Builder redaction(InputRedactionPolicy value) {
            redactionPolicy = Objects.requireNonNull(value, "redactionPolicy");
            return this;
        }

        /** Sets the application-owned capture-thread handler. */
        public Builder handler(Consumer<InputParameters> value) {
            handler = Objects.requireNonNull(value, "handler");
            return this;
        }

        /** Builds an immutable input specification. */
        public InputSpec build() {
            return new InputSpec(new InputDescriptor(id, Optional.ofNullable(description),
                    parameters, redactionPolicy), Objects.requireNonNull(handler, "handler"));
        }
    }
}
