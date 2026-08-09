package io.github.teemuki8.libgdx.agent.runtime.box2d;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.Box2D;
import com.badlogic.gdx.physics.box2d.ChainShape;
import com.badlogic.gdx.physics.box2d.EdgeShape;
import com.badlogic.gdx.physics.box2d.Fixture;
import com.badlogic.gdx.physics.box2d.PolygonShape;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.GdxNativesLoader;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import java.util.OptionalDouble;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Box2dShapeInspectionTest {
    @BeforeAll
    static void initializeNativeBox2d() {
        GdxNativesLoader.load();
        Box2D.init();
    }

    @Test
    void polygonEdgeAndChainGeometryIsCopiedAndVertexTruncationIsExplicit() {
        World world = new World(new Vector2(), true);
        try {
            Body body = world.createBody(new BodyDef());
            PolygonShape polygon = new PolygonShape();
            polygon.setAsBox(1, 2);
            Fixture polygonFixture = body.createFixture(polygon, 0);
            polygon.dispose();
            EdgeShape edge = new EdgeShape();
            edge.set(-1, 0, 1, 0);
            edge.setVertex0(new Vector2(-2, 0));
            edge.setHasVertex0(true);
            Fixture edgeFixture = body.createFixture(edge, 0);
            edge.dispose();
            ChainShape chain = new ChainShape();
            chain.createLoop(new float[] {0, 0, 2, 0, 2, 2, 0, 2});
            Fixture chainFixture = body.createFixture(chain, 0);
            chain.dispose();

            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("box2d-shapes"))
                    .build();
            Box2dInspection inspection = new Box2dInspection(runtime,
                    new Box2dAdapterLimits(1, 1, 4, 1, 2, 64, 4));
            inspection.registerWorld("main", world, new Box2dWorldSpec(
                    true, true, true, 6, 2, OptionalDouble.empty(),
                    new Box2dUnitTransform(1)));
            inspection.registerBody("body", "main", body);
            inspection.registerFixture("polygon", "body", polygonFixture);
            inspection.registerFixture("edge", "body", edgeFixture);
            inspection.registerFixture("chain", "body", chainFixture,
                    Box2dFixtureSpec.chainLoop(true));
            runtime.start();

            RuntimeValue.ObjectValue polygonGeometry = geometry(runtime, "polygon");
            assertEquals(RuntimeValues.integer(4), field(polygonGeometry, "observedVertices"));
            assertEquals(RuntimeValues.integer(2), field(polygonGeometry, "retainedVertices"));
            assertEquals(RuntimeValues.bool(true), field(polygonGeometry, "truncated"));
            assertEquals(2, ((RuntimeValue.ListValue) field(
                    polygonGeometry, "vertices")).values().size());
            assertEquals(new RuntimeValue.ListValue(java.util.List.of(
                            RuntimeValues.enumValue("SHAPE_VERTICES_TRUNCATED"))),
                    runtime.entity(EntityId.of("box2d.fixture.polygon")).orElseThrow()
                            .property("diagnostics").orElseThrow());

            RuntimeValue.ObjectValue edgeGeometry = geometry(runtime, "edge");
            assertEquals(RuntimeValues.vector2(-1, 0), field(edgeGeometry, "endpoint1"));
            assertEquals(RuntimeValues.bool(true), field(edgeGeometry, "hasAdjacent0"));
            assertEquals(RuntimeValues.vector2(-2, 0), field(edgeGeometry, "adjacent0"));

            RuntimeValue.ObjectValue chainGeometry = geometry(runtime, "chain");
            assertEquals(RuntimeValues.bool(true), field(chainGeometry, "loop"));
            assertEquals(RuntimeValues.bool(true), field(chainGeometry, "truncated"));
            runtime.close();
            inspection.close();
        } finally {
            world.dispose();
        }
    }

    private static RuntimeValue.ObjectValue geometry(AgentRuntime runtime, String fixtureId) {
        return (RuntimeValue.ObjectValue) runtime.entity(
                EntityId.of("box2d.fixture." + fixtureId)).orElseThrow()
                .property("geometry").orElseThrow();
    }

    private static RuntimeValue field(RuntimeValue.ObjectValue value, String name) {
        return value.fields().stream().filter(field -> field.name().equals(name))
                .findFirst().orElseThrow().value();
    }
}
