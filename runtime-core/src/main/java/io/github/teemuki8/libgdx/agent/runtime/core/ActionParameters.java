package io.github.teemuki8.libgdx.agent.runtime.core;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validated immutable values delivered to a semantic action handler. */
public final class ActionParameters {
    private final Map<String, RuntimeValue> values;

    ActionParameters(RuntimeValue.ObjectValue value) {
        LinkedHashMap<String, RuntimeValue> copy = new LinkedHashMap<>();
        value.fields().forEach(field -> copy.put(field.name(), field.value()));
        values = Map.copyOf(copy);
    }

    public EntityId requiredEntityId(String name) {
        return EntityId.of(requiredString(name));
    }

    public String requiredString(String name) {
        RuntimeValue value = required(name);
        if (value instanceof RuntimeValue.StringValue text) {
            return text.value();
        }
        if (value instanceof RuntimeValue.EnumValue symbol) {
            return symbol.value();
        }
        throw new IllegalArgumentException("action parameter has the wrong type");
    }

    public long requiredInteger(String name) {
        RuntimeValue value = required(name);
        if (value instanceof RuntimeValue.IntegerValue integer) {
            return integer.value();
        }
        throw new IllegalArgumentException("action parameter has the wrong type");
    }

    public BigDecimal requiredDecimal(String name) {
        RuntimeValue value = required(name);
        if (value instanceof RuntimeValue.DecimalValue decimal) {
            return decimal.value();
        }
        throw new IllegalArgumentException("action parameter has the wrong type");
    }

    public boolean requiredBoolean(String name) {
        RuntimeValue value = required(name);
        if (value instanceof RuntimeValue.BooleanValue bool) {
            return bool.value();
        }
        throw new IllegalArgumentException("action parameter has the wrong type");
    }

    public java.util.Optional<RuntimeValue> find(String name) {
        IdentifierSupport.validate(name, "action parameter name");
        return java.util.Optional.ofNullable(values.get(name));
    }

    private RuntimeValue required(String name) {
        return find(name).orElseThrow(() ->
                new IllegalArgumentException("required action parameter is absent"));
    }
}
