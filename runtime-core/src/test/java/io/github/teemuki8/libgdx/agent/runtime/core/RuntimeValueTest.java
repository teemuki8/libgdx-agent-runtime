package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RuntimeValueTest {
    @Test
    void supportsEveryValueTypeWithStructuralEquality() {
        RuntimeValue value = RuntimeValues.object(
                RuntimeValues.field("bool", RuntimeValues.bool(true)),
                RuntimeValues.field("decimal", RuntimeValues.decimal("14.200")),
                RuntimeValues.field("enum", RuntimeValues.enumValue("MOVING")),
                RuntimeValues.field("integer", RuntimeValues.integer(4)),
                RuntimeValues.field("list", RuntimeValues.list(
                        RuntimeValues.nullValue(), RuntimeValues.string("enemy"))),
                RuntimeValues.field("vector", RuntimeValues.vector2(20, 5)));

        assertEquals(value, RuntimeValues.object(
                RuntimeValues.field("vector", RuntimeValues.vector2(20.0, 5.0)),
                RuntimeValues.field("list", RuntimeValues.list(
                        RuntimeValues.nullValue(), RuntimeValues.string("enemy"))),
                RuntimeValues.field("integer", RuntimeValues.integer(4)),
                RuntimeValues.field("enum", RuntimeValues.enumValue("MOVING")),
                RuntimeValues.field("decimal", RuntimeValues.decimal("14.2")),
                RuntimeValues.field("bool", RuntimeValues.bool(true))));
    }

    @Test
    void collectionsAreDefensivelyCopiedAndOrdered() {
        var source = new ArrayList<RuntimeValue>();
        source.add(RuntimeValues.integer(1));
        var list = RuntimeValues.list(source);
        source.add(RuntimeValues.integer(2));

        assertEquals(1, list.values().size());
        assertThrows(UnsupportedOperationException.class,
                () -> list.values().add(RuntimeValues.integer(3)));
        var object = RuntimeValues.object(
                RuntimeValues.field("z", RuntimeValues.integer(1)),
                RuntimeValues.field("a", RuntimeValues.integer(2)));
        assertEquals(List.of("a", "z"), object.fields().stream()
                .map(RuntimeValue.Field::name).toList());
        assertNotSame(source, list.values());
    }

    @Test
    void rejectsNonFiniteNumbersAndDuplicateFields() {
        assertThrows(IllegalArgumentException.class, () -> RuntimeValues.decimal(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeValues.decimal(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> RuntimeValues.object(
                RuntimeValues.field("same", RuntimeValues.integer(1)),
                RuntimeValues.field("same", RuntimeValues.integer(2))));
    }

    @Test
    void enforcesStringCollectionAndDepthLimits() {
        RuntimeLimits limits = new RuntimeLimits(1, 1, 1, 1, 1, 1, 1, 4, 2, 2, 1);
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeValueValidator.validate(RuntimeValues.string("12345"), limits));
        assertThrows(IllegalArgumentException.class, () -> RuntimeValueValidator.validate(
                RuntimeValues.list(RuntimeValues.integer(1), RuntimeValues.integer(2),
                        RuntimeValues.integer(3)), limits));
        assertThrows(IllegalArgumentException.class, () -> RuntimeValueValidator.validate(
                RuntimeValues.list(RuntimeValues.list(RuntimeValues.integer(1))), limits));
    }

    @Test
    void validatesIdentifiersWithoutInterning() {
        assertThrows(NullPointerException.class, () -> EntityId.of(null));
        assertThrows(IllegalArgumentException.class, () -> EntityId.of(" "));
        assertThrows(IllegalArgumentException.class, () -> EntityId.of("x".repeat(257)));
        assertEquals(new FrameId(2), new FrameId(2));
        assertThrows(IllegalArgumentException.class, () -> new FrameId(-1));
    }
}
