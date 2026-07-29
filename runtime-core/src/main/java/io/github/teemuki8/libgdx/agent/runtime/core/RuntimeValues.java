package io.github.teemuki8.libgdx.agent.runtime.core;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** Factories for the closed runtime value model. */
public final class RuntimeValues {
    private static final RuntimeValue.NullValue NULL = new RuntimeValue.NullValue();

    private RuntimeValues() {}

    /** Returns the shared explicit null value. */
    public static RuntimeValue.NullValue nullValue() {
        return NULL;
    }

    /** Creates a boolean value. */
    public static RuntimeValue.BooleanValue bool(boolean value) {
        return new RuntimeValue.BooleanValue(value);
    }

    /** Creates an integer value. */
    public static RuntimeValue.IntegerValue integer(long value) {
        return new RuntimeValue.IntegerValue(value);
    }

    /** Creates a canonical decimal from text. */
    public static RuntimeValue.DecimalValue decimal(String value) {
        return new RuntimeValue.DecimalValue(new BigDecimal(value));
    }

    /** Creates a canonical finite decimal from a double. */
    public static RuntimeValue.DecimalValue decimal(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("decimal must be finite");
        }
        return new RuntimeValue.DecimalValue(BigDecimal.valueOf(value));
    }

    /** Creates a bounded-at-capture string value. */
    public static RuntimeValue.StringValue string(String value) {
        return new RuntimeValue.StringValue(value);
    }

    /** Creates an enum-like value. */
    public static RuntimeValue.EnumValue enumValue(String value) {
        return new RuntimeValue.EnumValue(value);
    }

    /** Creates a finite vector. */
    public static RuntimeValue.Vector2Value vector2(double x, double y) {
        return new RuntimeValue.Vector2Value(decimal(x), decimal(y));
    }

    /** Creates an immutable list. */
    public static RuntimeValue.ListValue list(RuntimeValue... values) {
        return new RuntimeValue.ListValue(Arrays.asList(values));
    }

    /** Creates an immutable list. */
    public static RuntimeValue.ListValue list(List<RuntimeValue> values) {
        return new RuntimeValue.ListValue(values);
    }

    /** Creates an immutable object. */
    public static RuntimeValue.ObjectValue object(RuntimeValue.Field... fields) {
        return new RuntimeValue.ObjectValue(Arrays.asList(fields));
    }

    /** Creates one object field. */
    public static RuntimeValue.Field field(String name, RuntimeValue value) {
        return new RuntimeValue.Field(name, value);
    }
}
