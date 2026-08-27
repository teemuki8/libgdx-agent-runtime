package io.github.teemuki8.libgdx.agent.runtime.box2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.box2d.Box2d;
import com.badlogic.gdx.box2d.enums.b2BodyType;
import com.badlogic.gdx.box2d.structs.b2BodyDef;
import com.badlogic.gdx.box2d.structs.b2BodyId;
import com.badlogic.gdx.box2d.structs.b2Capsule;
import com.badlogic.gdx.box2d.structs.b2JointId;
import com.badlogic.gdx.box2d.structs.b2RevoluteJointDef;
import com.badlogic.gdx.box2d.structs.b2ShapeDef;
import com.badlogic.gdx.box2d.structs.b2ShapeId;
import com.badlogic.gdx.box2d.structs.b2WorldDef;
import com.badlogic.gdx.box2d.structs.b2WorldId;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntitySnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Box2dInspectionTest {
    @BeforeAll
    static void initializeNativeBox2d() {
        Box2d.initialize();
    }

    @Test
    void publishesCopiedBox2d3WorldBodyCapsuleAndRevoluteFacts() {
        try (NativeScene scene = NativeScene.create("facts")) {
            scene.bodyDef.position().x(2.0f);
            scene.bodyDef.position().y(3.0f);
            scene.dynamicBody = Box2d.b2CreateBody(scene.world, scene.bodyDef.asPointer());
            Box2d.b2Body_SetLinearVelocity(scene.dynamicBody, NativeScene.vector(4.0f, 5.0f));
            scene.shape = scene.createCapsule(scene.dynamicBody);
            scene.staticBody = Box2d.b2CreateBody(scene.world, Box2d.b2DefaultBodyDef().asPointer());
            scene.joint = scene.createRevoluteJoint(scene.staticBody, scene.dynamicBody);

            scene.inspection.registerWorld("main", scene.world,
                    new Box2dWorldSpec(4, new Box2dUnitTransform(100)));
            scene.inspection.registerBody("ball", "main", scene.dynamicBody);
            scene.inspection.registerBody("ground", "main", scene.staticBody);
            scene.inspection.registerShape("ball-shape", "ball", scene.shape,
                    Box2dShapeSpec.defaults());
            scene.inspection.registerJoint("hinge", "main", scene.joint);
            scene.runtime.start();

            EntitySnapshot world = scene.entity("box2d.world.main");
            assertEquals(RuntimeValues.vector2(0, -9.8), property(world, "gravity"));
            assertEquals(RuntimeValues.integer(2), property(world, "totalBodyCount"));
            assertEquals(RuntimeValues.integer(1), property(world, "totalShapeCount"));
            assertEquals(RuntimeValues.integer(1), property(world, "totalJointCount"));
            assertEquals(RuntimeValues.integer(4), property(world, "subStepCount"));

            EntitySnapshot body = scene.entity("box2d.body.ball");
            assertEquals(RuntimeValues.enumValue("DYNAMIC"), property(body, "bodyType"));
            assertEquals(RuntimeValues.vector2(2, 3), property(body, "position"));
            assertEquals(RuntimeValues.vector2(4, 5), property(body, "linearVelocity"));
            assertTrue(decimal(property(body, "mass")) > 0.0);
            assertTrue(decimal(property(body, "inertia")) > 0.0);
            assertEquals(RuntimeValues.decimal(0), property(body, "angularVelocity"));

            EntitySnapshot shape = scene.entity("box2d.fixture.ball-shape");
            assertEquals(RuntimeValues.enumValue("CAPSULE"), property(shape, "shapeType"));
            RuntimeValue.ObjectValue geometry = (RuntimeValue.ObjectValue) property(shape, "geometry");
            assertEquals(RuntimeValues.vector2(0, -0.5), field(geometry, "center1"));
            assertEquals(RuntimeValues.vector2(0, 0.5), field(geometry, "center2"));
            assertEquals(RuntimeValues.decimal(0.25), field(geometry, "radius"));

            EntitySnapshot joint = scene.entity("box2d.joint.hinge");
            assertEquals(RuntimeValues.enumValue("REVOLUTE"), property(joint, "jointType"));
            assertEquals(RuntimeValues.string("ground"), property(joint, "bodyAId"));
            assertEquals(RuntimeValues.string("ball"), property(joint, "bodyBId"));
        }
    }

    @Test
    void rejectsStaleWrongWorldDuplicateBoundsThreadAndClosedCalls() throws Exception {
        try (NativeScene scene = NativeScene.create("failures");
                NativeScene other = NativeScene.create("other")) {
            scene.dynamicBody = Box2d.b2CreateBody(scene.world, scene.bodyDef.asPointer());
            scene.shape = scene.createCapsule(scene.dynamicBody);
            Box2dInspection inspection = new Box2dInspection(scene.runtime,
                    new Box2dAdapterLimits(1, 1, 1, 1, 32, 64, 4));
            inspection.registerWorld("main", scene.world,
                    new Box2dWorldSpec(4, new Box2dUnitTransform(100)));
            inspection.registerBody("body", "main", scene.dynamicBody);

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerWorld("duplicate", scene.world,
                            new Box2dWorldSpec(4, new Box2dUnitTransform(100))));
            assertEquals("native Box2D ID is already registered", duplicate.getMessage());
            IllegalArgumentException bound = assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerWorld("other", other.world,
                            new Box2dWorldSpec(4, new Box2dUnitTransform(100))));
            assertEquals("Box2D world registration limit reached", bound.getMessage());
            b2BodyId wrongWorldBody = Box2d.b2CreateBody(
                    other.world, Box2d.b2DefaultBodyDef().asPointer());
            other.dynamicBody = wrongWorldBody;
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerBody("wrong", "main", wrongWorldBody));

            Box2d.b2DestroyShape(scene.shape, true);
            assertFalse(Box2d.b2Shape_IsValid(scene.shape));
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerShape("stale", "body", scene.shape,
                            Box2dShapeSpec.defaults()));

            AtomicReference<Throwable> wrongThread = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    inspection.registerBody("thread", "main", scene.dynamicBody);
                } catch (Throwable failure) {
                    wrongThread.set(failure);
                }
            });
            thread.start();
            thread.join();
            assertTrue(wrongThread.get() instanceof IllegalStateException);
            Box2dContacts contacts = inspection.registerContacts(
                    "main", Box2dContactLimits.developmentDefaults(),
                    Box2dContactPolicy.developmentDefaults());

            inspection.close();
            assertTrue(contacts.nativeScratchFreed());
            assertThrows(IllegalStateException.class,
                    () -> inspection.registerBody("closed", "main", scene.dynamicBody));
        }
    }

    @Test
    void staleRegisteredIdFailsCaptureInsteadOfPublishingPartialEvidence() {
        try (NativeScene scene = NativeScene.create("stale-capture")) {
            scene.dynamicBody = Box2d.b2CreateBody(scene.world, scene.bodyDef.asPointer());
            scene.inspection.registerWorld("main", scene.world,
                    new Box2dWorldSpec(4, new Box2dUnitTransform(100)));
            scene.inspection.registerBody("body", "main", scene.dynamicBody);

            Box2d.b2DestroyBody(scene.dynamicBody);
            assertFalse(Box2d.b2Body_IsValid(scene.dynamicBody));
            scene.runtime.start();
            var diagnostic = scene.runtime.latestFrame().orElseThrow().stats().diagnostics()
                    .stream().filter(value -> value.entityId().orElseThrow().equals(
                            EntityId.of("box2d.body.body"))).findFirst().orElseThrow();
            assertEquals("provider.property", diagnostic.failure().category());
            assertEquals("java.lang.IllegalArgumentException",
                    diagnostic.failure().exceptionClass());
        }
    }

    private static RuntimeValue property(EntitySnapshot entity, String name) {
        return entity.properties().stream().filter(value -> value.name().equals(name))
                .findFirst().orElseThrow().value();
    }

    private static RuntimeValue field(RuntimeValue.ObjectValue value, String name) {
        return value.fields().stream().filter(field -> field.name().equals(name))
                .findFirst().orElseThrow().value();
    }

    private static double decimal(RuntimeValue value) {
        return ((RuntimeValue.DecimalValue) value).value().doubleValue();
    }

    private static final class NativeScene implements AutoCloseable {
        final AgentRuntime runtime;
        final Box2dInspection inspection;
        final b2WorldId world;
        final b2BodyDef bodyDef = Box2d.b2DefaultBodyDef();
        b2BodyId dynamicBody;
        b2BodyId staticBody;
        b2ShapeId shape;
        b2JointId joint;

        private NativeScene(String session, AgentRuntime runtime, Box2dInspection inspection,
                b2WorldId world) {
            this.runtime = runtime;
            this.inspection = inspection;
            this.world = world;
            bodyDef.type(b2BodyType.b2_dynamicBody);
        }

        static NativeScene create(String session) {
            b2WorldDef definition = Box2d.b2DefaultWorldDef();
            definition.gravity().y(-9.8f);
            definition.workerCount(0);
            b2WorldId world = Box2d.b2CreateWorld(definition.asPointer());
            AgentRuntime runtime = AgentRuntime.builder().sessionId(SessionId.of(session)).build();
            return new NativeScene(session, runtime,
                    new Box2dInspection(runtime, Box2dAdapterLimits.developmentDefaults()), world);
        }

        b2ShapeId createCapsule(b2BodyId body) {
            b2ShapeDef definition = Box2d.b2DefaultShapeDef();
            definition.density(2.0f);
            definition.material().friction(0.3f);
            definition.material().restitution(0.4f);
            b2Capsule capsule = new b2Capsule();
            capsule.center1().y(-0.5f);
            capsule.center2().y(0.5f);
            capsule.radius(0.25f);
            return Box2d.b2CreateCapsuleShape(body, definition.asPointer(), capsule.asPointer());
        }

        b2JointId createRevoluteJoint(b2BodyId bodyA, b2BodyId bodyB) {
            b2RevoluteJointDef definition = Box2d.b2DefaultRevoluteJointDef();
            definition.setBodyIdA(bodyA);
            definition.setBodyIdB(bodyB);
            return Box2d.b2CreateRevoluteJoint(world, definition.asPointer());
        }

        EntitySnapshot entity(String id) {
            return runtime.entity(EntityId.of(id)).orElseThrow();
        }

        static com.badlogic.gdx.box2d.structs.b2Vec2 vector(float x, float y) {
            var value = new com.badlogic.gdx.box2d.structs.b2Vec2();
            value.x(x);
            value.y(y);
            return value;
        }

        @Override public void close() {
            runtime.close();
            inspection.close();
            if (joint != null && Box2d.b2Joint_IsValid(joint)) {
                Box2d.b2DestroyJoint(joint);
            }
            if (shape != null && Box2d.b2Shape_IsValid(shape)) {
                Box2d.b2DestroyShape(shape, true);
            }
            if (dynamicBody != null && Box2d.b2Body_IsValid(dynamicBody)) {
                Box2d.b2DestroyBody(dynamicBody);
            }
            if (staticBody != null && Box2d.b2Body_IsValid(staticBody)) {
                Box2d.b2DestroyBody(staticBody);
            }
            if (Box2d.b2World_IsValid(world)) {
                Box2d.b2DestroyWorld(world);
            }
        }
    }
}
