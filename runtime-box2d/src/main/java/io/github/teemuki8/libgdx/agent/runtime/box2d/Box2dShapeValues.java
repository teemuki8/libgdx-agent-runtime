package io.github.teemuki8.libgdx.agent.runtime.box2d;

import com.badlogic.gdx.box2d.Box2d;
import com.badlogic.gdx.box2d.structs.b2Capsule;
import com.badlogic.gdx.box2d.structs.b2ChainSegment;
import com.badlogic.gdx.box2d.structs.b2Circle;
import com.badlogic.gdx.box2d.structs.b2Polygon;
import com.badlogic.gdx.box2d.structs.b2Segment;
import com.badlogic.gdx.box2d.structs.b2ShapeId;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import java.util.ArrayList;
import java.util.List;

final class Box2dShapeValues {
    private Box2dShapeValues() {}

    static RuntimeValue copy(b2ShapeId shape, int vertexLimit, Scratch scratch) {
        return switch (Box2d.b2Shape_GetType(shape)) {
            case b2_circleShape -> {
                Box2d.b2Shape_GetCircle(shape, scratch.circle);
                yield circle(scratch.circle);
            }
            case b2_capsuleShape -> {
                Box2d.b2Shape_GetCapsule(shape, scratch.capsule);
                yield capsule(scratch.capsule);
            }
            case b2_segmentShape -> {
                Box2d.b2Shape_GetSegment(shape, scratch.segment);
                yield segment(scratch.segment, "SEGMENT");
            }
            case b2_chainSegmentShape -> {
                Box2d.b2Shape_GetChainSegment(shape, scratch.chainSegment);
                yield segment(scratch.chainSegment.segment(), "CHAIN_SEGMENT");
            }
            case b2_polygonShape -> {
                Box2d.b2Shape_GetPolygon(shape, scratch.polygon);
                yield polygon(scratch.polygon, vertexLimit);
            }
            case b2_shapeTypeCount -> throw new IllegalStateException("invalid Box2D shape type");
        };
    }

    static RuntimeValue diagnostics(b2ShapeId shape, int vertexLimit, Scratch scratch) {
        if (Box2d.b2Shape_GetType(shape)
                != com.badlogic.gdx.box2d.enums.b2ShapeType.b2_polygonShape) {
            return RuntimeValues.list();
        }
        Box2d.b2Shape_GetPolygon(shape, scratch.polygon);
        return scratch.polygon.count() > vertexLimit
                ? RuntimeValues.list(RuntimeValues.enumValue("SHAPE_VERTICES_TRUNCATED"))
                : RuntimeValues.list();
    }

    private static RuntimeValue circle(b2Circle value) {
        return RuntimeValues.object(
                field("type", RuntimeValues.enumValue("CIRCLE")),
                field("center", Box2dInspection.vector(value.center())),
                field("radius", Box2dInspection.decimal(value.radius())));
    }

    private static RuntimeValue capsule(b2Capsule value) {
        return RuntimeValues.object(
                field("type", RuntimeValues.enumValue("CAPSULE")),
                field("center1", Box2dInspection.vector(value.center1())),
                field("center2", Box2dInspection.vector(value.center2())),
                field("radius", Box2dInspection.decimal(value.radius())));
    }

    private static RuntimeValue segment(b2Segment value, String type) {
        return RuntimeValues.object(
                field("type", RuntimeValues.enumValue(type)),
                field("point1", Box2dInspection.vector(value.point1())),
                field("point2", Box2dInspection.vector(value.point2())));
    }

    private static RuntimeValue polygon(b2Polygon value, int vertexLimit) {
        int observed = value.count();
        int retained = Math.min(observed, vertexLimit);
        List<RuntimeValue> vertices = new ArrayList<>(retained);
        for (int index = 0; index < retained; index++) {
            vertices.add(Box2dInspection.vector(value.vertices().asStackElement(index)));
        }
        return RuntimeValues.object(
                field("type", RuntimeValues.enumValue("POLYGON")),
                field("centroid", Box2dInspection.vector(value.centroid())),
                field("radius", Box2dInspection.decimal(value.radius())),
                field("observedVertices", RuntimeValues.integer(observed)),
                field("retainedVertices", RuntimeValues.integer(retained)),
                field("vertexLimit", RuntimeValues.integer(vertexLimit)),
                field("truncated", RuntimeValues.bool(observed > retained)),
                field("vertices", RuntimeValues.list(vertices)));
    }

    static final class Scratch {
        private final b2Circle circle = new b2Circle();
        private final b2Capsule capsule = new b2Capsule();
        private final b2Segment segment = new b2Segment();
        private final b2ChainSegment chainSegment = new b2ChainSegment();
        private final b2Polygon polygon = new b2Polygon();
    }

    private static RuntimeValue.Field field(String name, RuntimeValue value) {
        return RuntimeValues.field(name, value);
    }
}
