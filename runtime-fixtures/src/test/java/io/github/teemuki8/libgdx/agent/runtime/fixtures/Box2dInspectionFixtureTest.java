package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.box2d.Box2d;
import com.badlogic.gdx.box2d.enums.b2BodyType;
import com.badlogic.gdx.box2d.structs.b2BodyDef;
import com.badlogic.gdx.box2d.structs.b2BodyId;
import com.badlogic.gdx.box2d.structs.b2Capsule;
import com.badlogic.gdx.box2d.structs.b2ShapeDef;
import com.badlogic.gdx.box2d.structs.b2ShapeId;
import com.badlogic.gdx.box2d.structs.b2WorldDef;
import com.badlogic.gdx.box2d.structs.b2WorldId;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dAdapterLimits;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dInspection;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dShapeSpec;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dUnitTransform;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dWorldSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Box2dInspectionFixtureTest {
    @BeforeAll
    static void initializeNativeBox2d() {
        Box2d.initialize();
    }

    @Test
    void actualNativeCapsuleCrossesThePublishedRuntimeEntityBoundary() {
        b2WorldDef worldDef = Box2d.b2DefaultWorldDef();
        worldDef.workerCount(0);
        b2WorldId world = Box2d.b2CreateWorld(worldDef.asPointer());
        b2BodyId body = null;
        b2ShapeId shape = null;
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("box2d3-inspection-fixture"))
                .build();
        Box2dInspection inspection = new Box2dInspection(
                runtime, Box2dAdapterLimits.developmentDefaults());
        try {
            b2BodyDef bodyDef = Box2d.b2DefaultBodyDef();
            bodyDef.type(b2BodyType.b2_dynamicBody);
            body = Box2d.b2CreateBody(world, bodyDef.asPointer());
            b2ShapeDef shapeDef = Box2d.b2DefaultShapeDef();
            shapeDef.density(1.0f);
            b2Capsule capsule = new b2Capsule();
            capsule.center1().x(-0.75f);
            capsule.center2().x(0.75f);
            capsule.radius(0.2f);
            shape = Box2d.b2CreateCapsuleShape(body, shapeDef.asPointer(), capsule.asPointer());

            inspection.registerWorld("main", world,
                    new Box2dWorldSpec(4, new Box2dUnitTransform(32)));
            inspection.registerBody("fighter", "main", body);
            inspection.registerShape("fighter-capsule", "fighter", shape,
                    Box2dShapeSpec.defaults());
            runtime.start();

            var bodyEntity = runtime.entity(EntityId.of("box2d.body.fighter")).orElseThrow();
            assertEquals(RuntimeValues.enumValue("DYNAMIC"), bodyEntity.properties().stream()
                    .filter(property -> property.name().equals("bodyType"))
                    .findFirst().orElseThrow().value());
            var shapeEntity = runtime.entity(EntityId.of("box2d.fixture.fighter-capsule"))
                    .orElseThrow();
            assertEquals(RuntimeValues.enumValue("CAPSULE"), shapeEntity.properties().stream()
                    .filter(property -> property.name().equals("shapeType"))
                    .findFirst().orElseThrow().value());
            assertTrue(shapeEntity.properties().stream().noneMatch(property ->
                    property.value().toString().contains("index1")
                            || property.value().toString().contains("generation")));
        } finally {
            runtime.close();
            inspection.close();
            if (shape != null && Box2d.b2Shape_IsValid(shape)) {
                Box2d.b2DestroyShape(shape, true);
            }
            if (body != null && Box2d.b2Body_IsValid(body)) {
                Box2d.b2DestroyBody(body);
            }
            if (Box2d.b2World_IsValid(world)) {
                Box2d.b2DestroyWorld(world);
            }
        }
    }
}
