package io.github.teemuki8.libgdx.agent.runtime.box2d;

import com.badlogic.gdx.physics.box2d.CircleShape;
import com.badlogic.gdx.physics.box2d.ChainShape;
import com.badlogic.gdx.physics.box2d.EdgeShape;
import com.badlogic.gdx.physics.box2d.PolygonShape;
import com.badlogic.gdx.physics.box2d.Shape;
import com.badlogic.gdx.math.Vector2;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Box2dShapeValues {
    private Box2dShapeValues() {}

    static RuntimeValue copy(Shape shape, int vertexLimit, Box2dFixtureSpec spec) {
        if (shape instanceof CircleShape circle) {
            return RuntimeValues.object(
                    RuntimeValues.field("type", RuntimeValues.enumValue("CIRCLE")),
                    RuntimeValues.field("radius", Box2dInspection.decimal(circle.getRadius())),
                    RuntimeValues.field("localCenter", new RuntimeValue.Vector2Value(
                            Box2dInspection.decimal(circle.getPosition().x),
                            Box2dInspection.decimal(circle.getPosition().y))));
        }
        if (shape instanceof PolygonShape polygon) {
            return vertices("POLYGON", polygon.getVertexCount(), vertexLimit,
                    index -> {
                        Vector2 value = new Vector2();
                        polygon.getVertex(index, value);
                        return vector(value);
                    }, false);
        }
        if (shape instanceof ChainShape chain) {
            return vertices("CHAIN", chain.getVertexCount(), vertexLimit,
                    index -> {
                        Vector2 value = new Vector2();
                        chain.getVertex(index, value);
                        return vector(value);
                    }, spec.chainLoop().orElseThrow());
        }
        if (shape instanceof EdgeShape edge) {
            Vector2 endpoint1 = new Vector2();
            Vector2 endpoint2 = new Vector2();
            Vector2 adjacent0 = new Vector2();
            Vector2 adjacent3 = new Vector2();
            edge.getVertex1(endpoint1);
            edge.getVertex2(endpoint2);
            if (edge.hasVertex0()) {
                edge.getVertex0(adjacent0);
            }
            if (edge.hasVertex3()) {
                edge.getVertex3(adjacent3);
            }
            return RuntimeValues.object(
                    RuntimeValues.field("type", RuntimeValues.enumValue("EDGE")),
                    RuntimeValues.field("endpoint1", vector(endpoint1)),
                    RuntimeValues.field("endpoint2", vector(endpoint2)),
                    RuntimeValues.field("hasAdjacent0", RuntimeValues.bool(edge.hasVertex0())),
                    RuntimeValues.field("adjacent0", edge.hasVertex0()
                            ? vector(adjacent0) : RuntimeValues.nullValue()),
                    RuntimeValues.field("hasAdjacent3", RuntimeValues.bool(edge.hasVertex3())),
                    RuntimeValues.field("adjacent3", edge.hasVertex3()
                            ? vector(adjacent3) : RuntimeValues.nullValue()));
        }
        return RuntimeValues.object(
                RuntimeValues.field("type", RuntimeValues.enumValue(shape.getType().name()
                        .toUpperCase(Locale.ROOT))),
                RuntimeValues.field("observedVertices", RuntimeValues.integer(0)),
                RuntimeValues.field("retainedVertices", RuntimeValues.integer(0)),
                RuntimeValues.field("vertexLimit", RuntimeValues.integer(vertexLimit)),
                RuntimeValues.field("truncated", RuntimeValues.bool(false)));
    }

    static RuntimeValue diagnostics(Shape shape, int vertexLimit, int diagnosticLimit) {
        int vertices = switch (shape) {
            case PolygonShape polygon -> polygon.getVertexCount();
            case ChainShape chain -> chain.getVertexCount();
            default -> 0;
        };
        if (vertices > vertexLimit && diagnosticLimit > 0) {
            return RuntimeValues.list(
                    RuntimeValues.enumValue("SHAPE_VERTICES_TRUNCATED"));
        }
        return RuntimeValues.list();
    }

    private static RuntimeValue vertices(String type, int observed, int limit,
            java.util.function.IntFunction<RuntimeValue> provider, boolean loop) {
        int retained = Math.min(observed, limit);
        List<RuntimeValue> values = new ArrayList<>(retained);
        for (int index = 0; index < retained; index++) {
            values.add(provider.apply(index));
        }
        List<RuntimeValue.Field> fields = new ArrayList<>(List.of(
                RuntimeValues.field("type", RuntimeValues.enumValue(type)),
                RuntimeValues.field("vertices", RuntimeValues.list(values)),
                RuntimeValues.field("observedVertices", RuntimeValues.integer(observed)),
                RuntimeValues.field("retainedVertices", RuntimeValues.integer(retained)),
                RuntimeValues.field("vertexLimit", RuntimeValues.integer(limit)),
                RuntimeValues.field("truncated", RuntimeValues.bool(observed > retained))));
        if (type.equals("CHAIN")) {
            fields.add(RuntimeValues.field("loop", RuntimeValues.bool(loop)));
        }
        return new RuntimeValue.ObjectValue(fields);
    }

    private static RuntimeValue vector(Vector2 value) {
        return new RuntimeValue.Vector2Value(
                Box2dInspection.decimal(value.x), Box2dInspection.decimal(value.y));
    }
}
