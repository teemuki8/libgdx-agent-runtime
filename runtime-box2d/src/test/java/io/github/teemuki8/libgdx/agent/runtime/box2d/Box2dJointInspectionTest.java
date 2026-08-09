package io.github.teemuki8.libgdx.agent.runtime.box2d;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.Box2D;
import com.badlogic.gdx.physics.box2d.Joint;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.physics.box2d.joints.DistanceJointDef;
import com.badlogic.gdx.physics.box2d.joints.PrismaticJointDef;
import com.badlogic.gdx.physics.box2d.joints.RevoluteJointDef;
import com.badlogic.gdx.physics.box2d.joints.WeldJointDef;
import com.badlogic.gdx.utils.GdxNativesLoader;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntitySnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Box2dJointInspectionTest {
    @BeforeAll
    static void initializeNativeBox2d() {
        GdxNativesLoader.load();
        Box2D.init();
    }

    @Test
    void distanceRevoluteAndPrismaticJointsExposeClosedDetailsAndReactionEvidence() {
        World world = new World(new Vector2(), true);
        try {
            BodyDef definition = new BodyDef();
            definition.type = BodyDef.BodyType.DynamicBody;
            Body bodyA = world.createBody(definition);
            definition.position.set(2, 0);
            Body bodyB = world.createBody(definition);

            DistanceJointDef distanceDef = new DistanceJointDef();
            distanceDef.initialize(bodyA, bodyB, new Vector2(), new Vector2(2, 0));
            distanceDef.length = 2;
            distanceDef.frequencyHz = 3;
            distanceDef.dampingRatio = 0.5f;
            Joint distance = world.createJoint(distanceDef);
            RevoluteJointDef revoluteDef = new RevoluteJointDef();
            revoluteDef.initialize(bodyA, bodyB, new Vector2(1, 0));
            revoluteDef.enableLimit = true;
            revoluteDef.lowerAngle = -0.5f;
            revoluteDef.upperAngle = 0.5f;
            Joint revolute = world.createJoint(revoluteDef);
            PrismaticJointDef prismaticDef = new PrismaticJointDef();
            prismaticDef.initialize(bodyA, bodyB, new Vector2(), new Vector2(1, 0));
            prismaticDef.enableMotor = true;
            prismaticDef.motorSpeed = 2;
            Joint prismatic = world.createJoint(prismaticDef);
            WeldJointDef weldDef = new WeldJointDef();
            weldDef.initialize(bodyA, bodyB, new Vector2(1, 0));
            Joint weld = world.createJoint(weldDef);

            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("box2d-joints"))
                    .build();
            Box2dInspection inspection = new Box2dInspection(runtime,
                    Box2dAdapterLimits.developmentDefaults());
            inspection.registerWorld("main", world, new Box2dWorldSpec(
                    true, true, true, 6, 2, OptionalDouble.of(60),
                    new Box2dUnitTransform(1)));
            inspection.registerBody("a", "main", bodyA);
            inspection.registerBody("b", "main", bodyB);
            inspection.registerJoint("distance", "main", distance);
            inspection.registerJoint("revolute", "main", revolute);
            inspection.registerJoint("prismatic", "main", prismatic);
            inspection.registerJoint("weld", "main", weld);
            runtime.start();

            EntitySnapshot distanceEntity = entity(runtime, "distance");
            assertEquals(List.of(
                    "active", "anchorA", "anchorB", "bodyAId", "bodyBId",
                    "collideConnected", "detail", "id", "jointType", "reactionForce",
                    "reactionTorque", "runtimeEntityId", "worldId"),
                    distanceEntity.properties().stream().map(RuntimeValue.Field::name).toList());
            assertEquals(RuntimeValues.enumValue("DISTANCE"),
                    distanceEntity.property("jointType").orElseThrow());
            assertEquals(RuntimeValues.string("a"),
                    distanceEntity.property("bodyAId").orElseThrow());
            assertEquals(RuntimeValues.string("b"),
                    distanceEntity.property("bodyBId").orElseThrow());
            assertEquals(RuntimeValues.bool(true),
                    distanceEntity.property("active").orElseThrow());
            assertEquals(RuntimeValues.vector2(0, 0),
                    distanceEntity.property("reactionForce").orElseThrow());
            assertEquals(RuntimeValues.enumValue("DISTANCE"), field(
                    (RuntimeValue.ObjectValue) distanceEntity.property("detail").orElseThrow(),
                    "type"));

            assertEquals(RuntimeValues.enumValue("REVOLUTE"), field(
                    detail(runtime, "revolute"), "type"));
            assertEquals(RuntimeValues.bool(true), field(
                    detail(runtime, "revolute"), "limitEnabled"));
            assertEquals(RuntimeValues.enumValue("PRISMATIC"), field(
                    detail(runtime, "prismatic"), "type"));
            assertEquals(RuntimeValues.bool(true), field(
                    detail(runtime, "prismatic"), "motorEnabled"));
            assertEquals(RuntimeValues.enumValue("GENERIC"), field(
                    detail(runtime, "weld"), "type"));
            assertEquals(RuntimeValues.enumValue("WELD"), field(
                    detail(runtime, "weld"), "nativeJointType"));
            runtime.close();
            inspection.close();
        } finally {
            world.dispose();
        }
    }

    @Test
    void reactionEvidenceIsNullWithoutExplicitInverseStep() {
        World world = new World(new Vector2(), true);
        try {
            Body bodyA = world.createBody(new BodyDef());
            Body bodyB = world.createBody(new BodyDef());
            DistanceJointDef definition = new DistanceJointDef();
            definition.initialize(bodyA, bodyB, new Vector2(), new Vector2(1, 0));
            Joint joint = world.createJoint(definition);
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("box2d-joint-no-inverse-step"))
                    .build();
            Box2dInspection inspection = new Box2dInspection(
                    runtime, Box2dAdapterLimits.developmentDefaults());
            inspection.registerWorld("main", world, new Box2dWorldSpec(
                    true, true, true, 6, 2, OptionalDouble.empty(),
                    new Box2dUnitTransform(1)));
            inspection.registerBody("a", "main", bodyA);
            inspection.registerBody("b", "main", bodyB);
            inspection.registerJoint("distance", "main", joint);
            runtime.start();

            assertEquals(RuntimeValues.nullValue(),
                    entity(runtime, "distance").property("reactionForce").orElseThrow());
            assertEquals(RuntimeValues.nullValue(),
                    entity(runtime, "distance").property("reactionTorque").orElseThrow());
            runtime.close();
            inspection.close();
        } finally {
            world.dispose();
        }
    }

    private static EntitySnapshot entity(AgentRuntime runtime, String id) {
        return runtime.entity(EntityId.of("box2d.joint." + id)).orElseThrow();
    }

    private static RuntimeValue.ObjectValue detail(AgentRuntime runtime, String id) {
        return (RuntimeValue.ObjectValue) entity(runtime, id).property("detail").orElseThrow();
    }

    private static RuntimeValue field(RuntimeValue.ObjectValue value, String name) {
        return value.fields().stream().filter(field -> field.name().equals(name))
                .findFirst().orElseThrow().value();
    }
}
