package io.github.teemuki8.libgdx.agent.runtime.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Closed immutable data model exposed by snapshots and semantic evidence.
 *
 * <p>No implementation can contain an arbitrary Java object.
 */
public sealed interface RuntimeValue permits RuntimeValue.NullValue, RuntimeValue.BooleanValue,
        RuntimeValue.IntegerValue, RuntimeValue.DecimalValue, RuntimeValue.StringValue,
        RuntimeValue.EnumValue, RuntimeValue.Vector2Value, RuntimeValue.ListValue,
        RuntimeValue.ObjectValue {
    /** Explicit null value. */
    record NullValue() implements RuntimeValue {}

    /** Boolean value. */
    record BooleanValue(boolean value) implements RuntimeValue {}

    /** Signed integral value. */
    record IntegerValue(long value) implements RuntimeValue {}

    /** Canonical finite decimal value. */
    record DecimalValue(BigDecimal value) implements RuntimeValue {
        /** Canonicalizes scale while preserving zero. */
        public DecimalValue {
            Objects.requireNonNull(value, "value");
            if (value.precision() > 128 || Math.abs((long) value.scale()) > 1_024) {
                throw new IllegalArgumentException("decimal exceeds precision or scale limit");
            }
            value = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        }
    }

    /** Bounded text value. */
    record StringValue(String value) implements RuntimeValue {
        /** Rejects null. Capture limits validate length. */
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    /** Stable enum-like symbolic value. */
    record EnumValue(String value) implements RuntimeValue {
        /** Rejects blank symbols. Capture limits validate length. */
        public EnumValue {
            IdentifierSupport.validate(value, "enum value");
        }
    }

    /** Finite two-dimensional decimal vector. */
    record Vector2Value(DecimalValue x, DecimalValue y) implements RuntimeValue {
        /** Rejects missing components. */
        public Vector2Value {
            Objects.requireNonNull(x, "x");
            Objects.requireNonNull(y, "y");
        }
    }

    /** Deeply immutable ordered list. */
    record ListValue(List<RuntimeValue> values) implements RuntimeValue {
        /** Copies elements and rejects null. */
        public ListValue {
            Objects.requireNonNull(values, "values");
            values.forEach(value -> Objects.requireNonNull(value, "list value"));
            values = List.copyOf(values);
        }
    }

    /** Deeply immutable structured object with ordered unique fields. */
    record ObjectValue(List<Field> fields) implements RuntimeValue {
        /** Sorts fields by key and rejects duplicates. */
        public ObjectValue {
            Objects.requireNonNull(fields, "fields");
            var copy = new ArrayList<>(fields);
            copy.forEach(field -> Objects.requireNonNull(field, "object field"));
            copy.sort(Comparator.comparing(Field::name));
            for (int index = 1; index < copy.size(); index++) {
                if (copy.get(index - 1).name().equals(copy.get(index).name())) {
                    throw new IllegalArgumentException(
                            "duplicate object field: " + copy.get(index).name());
                }
            }
            fields = List.copyOf(copy);
        }
    }

    /** One ordered object or entity property. */
    record Field(String name, RuntimeValue value) {
        /** Validates the field. */
        public Field {
            IdentifierSupport.validate(name, "field name");
            Objects.requireNonNull(value, "value");
        }
    }
}
