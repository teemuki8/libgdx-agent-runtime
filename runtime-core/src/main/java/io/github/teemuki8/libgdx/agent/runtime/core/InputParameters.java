package io.github.teemuki8.libgdx.agent.runtime.core;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Validated immutable values delivered to a registered input handler. */
public final class InputParameters {
    private final Map<String, RuntimeValue> values;

    InputParameters(RuntimeValue.ObjectValue value) {
        LinkedHashMap<String, RuntimeValue> copy = new LinkedHashMap<>();
        value.fields().forEach(field -> copy.put(field.name(), field.value()));
        values = Map.copyOf(copy);
    }

    /** Returns one required entity identifier. */
    public EntityId requiredEntityId(String name) {
        return EntityId.of(requiredString(name));
    }

    /** Returns one required string or enum symbol. */
    public String requiredString(String name) {
        RuntimeValue value = required(name);
        if (value instanceof RuntimeValue.StringValue text) {
            return text.value();
        }
        if (value instanceof RuntimeValue.EnumValue symbol) {
            return symbol.value();
        }
        throw new IllegalArgumentException("input parameter has the wrong type");
    }

    /** Returns one required integer. */
    public long requiredInteger(String name) {
        RuntimeValue value = required(name);
        if (value instanceof RuntimeValue.IntegerValue integer) {
            return integer.value();
        }
        throw new IllegalArgumentException("input parameter has the wrong type");
    }

    /** Returns one required decimal. */
    public BigDecimal requiredDecimal(String name) {
        RuntimeValue value = required(name);
        if (value instanceof RuntimeValue.DecimalValue decimal) {
            return decimal.value();
        }
        throw new IllegalArgumentException("input parameter has the wrong type");
    }

    /** Returns one required boolean. */
    public boolean requiredBoolean(String name) {
        RuntimeValue value = required(name);
        if (value instanceof RuntimeValue.BooleanValue bool) {
            return bool.value();
        }
        throw new IllegalArgumentException("input parameter has the wrong type");
    }

    /** Finds one optional value by its registered parameter name. */
    public Optional<RuntimeValue> find(String name) {
        IdentifierSupport.validate(name, "input parameter name");
        return Optional.ofNullable(values.get(name));
    }

    private RuntimeValue required(String name) {
        return find(name).orElseThrow(() ->
                new IllegalArgumentException("required input parameter is absent"));
    }
}
