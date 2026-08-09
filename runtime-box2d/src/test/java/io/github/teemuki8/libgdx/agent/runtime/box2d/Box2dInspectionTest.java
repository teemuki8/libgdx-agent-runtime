package io.github.teemuki8.libgdx.agent.runtime.box2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.Box2D;
import com.badlogic.gdx.physics.box2d.CircleShape;
import com.badlogic.gdx.physics.box2d.Fixture;
import com.badlogic.gdx.physics.box2d.FixtureDef;
import com.badlogic.gdx.physics.box2d.Joint;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.physics.box2d.joints.DistanceJointDef;
import com.badlogic.gdx.utils.GdxNativesLoader;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntimeException;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntitySnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Box2dInspectionTest {
    @BeforeAll
    static void initializeNativeBox2d() {
        GdxNativesLoader.load();
        Box2D.init();
    }

    @Test
    void explicitlyRegisteredWorldBodyAndFixtureBecomeClosedRuntimeEntities() {
        World world = new World(new Vector2(0, -9.8f), true);
        try {
            BodyDef bodyDef = new BodyDef();
            bodyDef.type = BodyDef.BodyType.DynamicBody;
            bodyDef.position.set(2, 3);
            Body ball = world.createBody(bodyDef);
            ball.setLinearVelocity(4, 5);
            CircleShape circle = new CircleShape();
            circle.setRadius(0.5f);
            circle.setPosition(new Vector2(0.1f, 0.2f));
            FixtureDef fixtureDef = new FixtureDef();
            fixtureDef.shape = circle;
            fixtureDef.density = 2;
            fixtureDef.friction = 0.3f;
            fixtureDef.restitution = 0.4f;
            fixtureDef.filter.categoryBits = (short) 0x8001;
            fixtureDef.filter.maskBits = (short) 0xFFFE;
            fixtureDef.filter.groupIndex = -2;
            Fixture fixture = ball.createFixture(fixtureDef);
            circle.dispose();
            world.createBody(new BodyDef());

            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("box2d-inspection"))
                    .build();
            Box2dInspection inspection = new Box2dInspection(runtime,
                    Box2dAdapterLimits.developmentDefaults());
            inspection.registerWorld("main", world, worldSpec());
            inspection.registerBody("ball", "main", ball);
            inspection.registerFixture("ball-shape", "ball", fixture);
            runtime.start();

            EntitySnapshot worldEntity = runtime.entity(EntityId.of("box2d.world.main"))
                    .orElseThrow();
            assertEquals(List.of(
                    "continuousPhysics", "fixedStepNanos", "gravity", "id", "locked",
                    "positionIterations", "registeredBodyCount", "registeredFixtureCount",
                    "registeredJointCount", "renderUnitsPerMeter", "runtimeEntityId",
                    "sleepingAllowed", "totalBodyCount", "totalContactCount",
                    "totalFixtureCount", "totalJointCount", "velocityIterations", "warmStarting"),
                    propertyNames(worldEntity));
            assertEquals("box2d.world", worldEntity.type().value());
            assertEquals(RuntimeValues.vector2(0, -9.8), property(worldEntity, "gravity"));
            assertEquals(RuntimeValues.integer(1), property(worldEntity, "registeredBodyCount"));
            assertEquals(RuntimeValues.integer(2), property(worldEntity, "totalBodyCount"));
            assertEquals(RuntimeValues.decimal(100),
                    property(worldEntity, "renderUnitsPerMeter"));

            EntitySnapshot bodyEntity = runtime.entity(EntityId.of("box2d.body.ball"))
                    .orElseThrow();
            assertEquals(List.of(
                    "active", "angleRadians", "angularDamping", "angularVelocity", "awake",
                    "bodyType", "bullet", "fixedRotation", "gravityScale", "id", "inertia",
                    "linearDamping", "linearVelocity", "mass", "position",
                    "registeredFixtureCount", "runtimeEntityId", "sleepingAllowed",
                    "totalFixtureCount", "worldId"), propertyNames(bodyEntity));
            assertEquals(RuntimeValues.enumValue("DYNAMIC"), property(bodyEntity, "bodyType"));
            assertEquals(RuntimeValues.vector2(2, 3), property(bodyEntity, "position"));
            assertEquals(RuntimeValues.vector2(4, 5), property(bodyEntity, "linearVelocity"));
            assertEquals(RuntimeValues.bool(true), property(bodyEntity, "active"));
            assertEquals(RuntimeValues.integer(1), property(bodyEntity, "registeredFixtureCount"));

            EntitySnapshot fixtureEntity = runtime.entity(EntityId.of("box2d.fixture.ball-shape"))
                    .orElseThrow();
            assertEquals(List.of(
                    "bodyId", "categoryBits", "density", "diagnostics", "friction", "geometry",
                    "groupIndex", "id", "maskBits", "restitution", "runtimeEntityId", "sensor",
                    "shapeType"), propertyNames(fixtureEntity));
            assertEquals(RuntimeValues.enumValue("CIRCLE"), property(fixtureEntity, "shapeType"));
            assertEquals(RuntimeValues.integer(0x8001), property(fixtureEntity, "categoryBits"));
            assertEquals(RuntimeValues.integer(0xFFFE), property(fixtureEntity, "maskBits"));
            assertEquals(RuntimeValues.integer(-2), property(fixtureEntity, "groupIndex"));
            RuntimeValue.ObjectValue geometry = (RuntimeValue.ObjectValue) property(
                    fixtureEntity, "geometry");
            assertTrue(geometry.fields().stream().anyMatch(field -> field.name().equals("radius")
                    && field.value().equals(RuntimeValues.decimal(0.5))));

            assertFalse(runtime.entity(EntityId.of("box2d.body.unregistered")).isPresent());
            runtime.close();
            inspection.close();
        } finally {
            world.dispose();
        }
    }

    @Test
    void registrationBoundsRelationshipsThreadAndRebindAreExplicit() throws Exception {
        World first = new World(new Vector2(), true);
        World second = new World(new Vector2(), true);
        try {
            Body firstBody = first.createBody(new BodyDef());
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("box2d-registration"))
                    .build();
            Box2dInspection inspection = new Box2dInspection(runtime,
                    new Box2dAdapterLimits(1, 1, 1, 1, 32, 64, 4));
            Box2dRegistration<World> world = inspection.registerWorld(
                    "main", first, worldSpec());
            Box2dRegistration<Body> body = inspection.registerBody("body", "main", firstBody);
            CircleShape circle = new CircleShape();
            circle.setRadius(1);
            Fixture firstFixture = firstBody.createFixture(circle, 0);
            circle.dispose();
            Box2dRegistration<Fixture> fixture = inspection.registerFixture(
                    "fixture", "body", firstFixture);

            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerWorld("other", second, worldSpec()));
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerBody("other", "main", firstBody));
            assertThrows(IllegalStateException.class, () -> world.rebind(second));
            assertThrows(IllegalStateException.class, body::close);

            AtomicReference<Throwable> wrongThread = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    body.rebind(firstBody);
                } catch (Throwable failure) {
                    wrongThread.set(failure);
                }
            });
            thread.start();
            thread.join();
            assertTrue(wrongThread.get() instanceof IllegalStateException);

            fixture.close();
            assertThrows(IllegalStateException.class, () -> fixture.rebind(firstFixture));
            body.close();
            world.rebind(second);
            runtime.start();
            assertEquals(RuntimeValues.vector2(0, 0), property(runtime.entity(
                    world.runtimeEntityId()).orElseThrow(), "gravity"));
            runtime.close();
            inspection.close();
            assertThrows(IllegalStateException.class,
                    () -> inspection.registerWorld("closed", first, worldSpec()));
        } finally {
            first.dispose();
            second.dispose();
        }
    }

    @Test
    void destroyedWorldCanBeRecreatedUnderTheSameStableRegistration() {
        World original = new World(new Vector2(1, -9), true);
        World replacement = new World(new Vector2(2, -8), true);
        boolean originalDisposed = false;
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("box2d-world-recreation"))
                .build();
        Box2dInspection inspection = new Box2dInspection(
                runtime, Box2dAdapterLimits.developmentDefaults());
        try {
            Box2dRegistration<World> registration = inspection.registerWorld(
                    "main", original, worldSpec());
            original.dispose();
            originalDisposed = true;

            registration.rebind(replacement);
            runtime.start();

            assertEquals(EntityId.of("box2d.world.main"), registration.runtimeEntityId());
            assertEquals(RuntimeValues.vector2(2, -8), property(runtime.entity(
                    registration.runtimeEntityId()).orElseThrow(), "gravity"));
        } finally {
            runtime.close();
            inspection.close();
            if (!originalDisposed) {
                original.dispose();
            }
            replacement.dispose();
        }
    }

    @Test
    void rebindAndCloseRejectOpenFrameWithoutPoisoningRegistration() {
        World original = new World(new Vector2(), true);
        World replacement = new World(new Vector2(3, -7), true);
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("box2d-open-frame-lifecycle"))
                .build();
        Box2dInspection inspection = new Box2dInspection(
                runtime, Box2dAdapterLimits.developmentDefaults());
        try {
            Box2dRegistration<World> registration = inspection.registerWorld(
                    "main", original, worldSpec());
            runtime.start();
            runtime.beginFrame(1);
            try {
                assertThrows(AgentRuntimeException.class, () -> registration.rebind(replacement));
                assertThrows(AgentRuntimeException.class, registration::close);
                assertThrows(AgentRuntimeException.class, inspection::close);
            } finally {
                runtime.endFrame();
            }
            registration.rebind(replacement);
            runtime.frame(1, () -> {});
            assertEquals(RuntimeValues.vector2(3, -7), property(runtime.entity(
                    registration.runtimeEntityId()).orElseThrow(), "gravity"));
            inspection.close();
        } finally {
            runtime.close();
            inspection.close();
            original.dispose();
            replacement.dispose();
        }
    }

    @Test
    void everyRegistrationKindEnforcesCapacityAndCloseIsIdempotent() {
        World world = new World(new Vector2(), true);
        World excessWorld = new World(new Vector2(), true);
        try {
            Body bodyA = world.createBody(new BodyDef());
            Body bodyB = world.createBody(new BodyDef());
            Body excessBody = world.createBody(new BodyDef());
            CircleShape circle = new CircleShape();
            circle.setRadius(1);
            Fixture fixtureA = bodyA.createFixture(circle, 0);
            Fixture fixtureB = bodyA.createFixture(circle, 0);
            circle.dispose();
            DistanceJointDef firstDefinition = new DistanceJointDef();
            firstDefinition.initialize(bodyA, bodyB, new Vector2(), new Vector2(1, 0));
            Joint firstJoint = world.createJoint(firstDefinition);
            DistanceJointDef secondDefinition = new DistanceJointDef();
            secondDefinition.initialize(bodyA, bodyB, new Vector2(), new Vector2(2, 0));
            Joint secondJoint = world.createJoint(secondDefinition);
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("box2d-registration-capacities"))
                    .build();
            Box2dInspection inspection = new Box2dInspection(runtime,
                    new Box2dAdapterLimits(1, 2, 1, 1, 32, 64, 4));
            Box2dRegistration<World> worldRegistration = inspection.registerWorld(
                    "main", world, worldSpec());
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerWorld("excess", excessWorld, worldSpec()));
            Box2dRegistration<Body> bodyARegistration = inspection.registerBody(
                    "a", "main", bodyA);
            Box2dRegistration<Body> bodyBRegistration = inspection.registerBody(
                    "b", "main", bodyB);
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerBody("excess", "main", excessBody));
            Box2dRegistration<Fixture> fixtureRegistration = inspection.registerFixture(
                    "fixture", "a", fixtureA);
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerFixture("excess", "a", fixtureB));
            Box2dRegistration<Joint> jointRegistration = inspection.registerJoint(
                    "joint", "main", firstJoint);
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerJoint("excess", "main", secondJoint));

            jointRegistration.close();
            jointRegistration.close();
            fixtureRegistration.close();
            fixtureRegistration.close();
            bodyARegistration.close();
            bodyARegistration.close();
            bodyBRegistration.close();
            worldRegistration.close();
            worldRegistration.close();
            inspection.close();
            inspection.close();
            runtime.close();
        } finally {
            world.dispose();
            excessWorld.dispose();
        }
    }

    @Test
    void duplicateNativeObjectsAndConflictingRelationshipsAreRejected() {
        World main = new World(new Vector2(), true);
        World other = new World(new Vector2(), true);
        try {
            Body bodyA = main.createBody(new BodyDef());
            Body bodyB = main.createBody(new BodyDef());
            Body unregistered = main.createBody(new BodyDef());
            Body remote = other.createBody(new BodyDef());
            Body remoteB = other.createBody(new BodyDef());
            CircleShape circle = new CircleShape();
            circle.setRadius(1);
            Fixture fixtureA = bodyA.createFixture(circle, 0);
            Fixture fixtureA2 = bodyA.createFixture(circle, 0);
            Fixture fixtureB = bodyB.createFixture(circle, 0);
            circle.dispose();
            DistanceJointDef registeredDefinition = new DistanceJointDef();
            registeredDefinition.initialize(bodyA, bodyB, new Vector2(), new Vector2(1, 0));
            Joint registeredJoint = main.createJoint(registeredDefinition);
            DistanceJointDef missingEndpointDefinition = new DistanceJointDef();
            missingEndpointDefinition.initialize(
                    bodyA, unregistered, new Vector2(), new Vector2(2, 0));
            Joint missingEndpointJoint = main.createJoint(missingEndpointDefinition);
            DistanceJointDef remoteDefinition = new DistanceJointDef();
            remoteDefinition.initialize(remote, remoteB, new Vector2(), new Vector2(1, 0));
            Joint remoteJoint = other.createJoint(remoteDefinition);
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("box2d-registration-conflicts"))
                    .build();
            Box2dInspection inspection = new Box2dInspection(
                    runtime, Box2dAdapterLimits.developmentDefaults());
            inspection.registerWorld("main", main, worldSpec());
            inspection.registerWorld("other", other, worldSpec());
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerWorld("main", other, worldSpec()));
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerWorld("main-alias", main, worldSpec()));
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerBody("wrong-world", "main", remote));
            inspection.registerBody("a", "main", bodyA);
            inspection.registerBody("b", "main", bodyB);
            inspection.registerBody("remote", "other", remote);
            inspection.registerBody("remote-b", "other", remoteB);
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerBody("a", "main", bodyB));
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerBody("a-alias", "main", bodyA));
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerFixture("wrong-body", "a", fixtureB));
            inspection.registerFixture("fixture", "a", fixtureA);
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerFixture("fixture", "a", fixtureA2));
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerFixture("fixture-alias", "a", fixtureA));
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerJoint("missing", "main", missingEndpointJoint));
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerJoint("wrong-world", "main", remoteJoint));
            inspection.registerJoint("joint", "main", registeredJoint);
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerJoint("joint", "main", registeredJoint));
            assertThrows(IllegalArgumentException.class,
                    () -> inspection.registerJoint("joint-alias", "main", registeredJoint));
            runtime.close();
            inspection.close();
        } finally {
            main.dispose();
            other.dispose();
        }
    }

    @Test
    void destroyedBodyAndFixtureCanRebindToRecreatedNativeObjects() {
        World world = new World(new Vector2(), true);
        try {
            Body fixtureOwner = world.createBody(new BodyDef());
            CircleShape originalShape = new CircleShape();
            originalShape.setRadius(1);
            Fixture originalFixture = fixtureOwner.createFixture(originalShape, 1);
            originalShape.dispose();
            BodyDef originalBodyDefinition = new BodyDef();
            originalBodyDefinition.position.set(1, 2);
            Body originalBody = world.createBody(originalBodyDefinition);
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("box2d-leaf-recreation"))
                    .build();
            Box2dInspection inspection = new Box2dInspection(
                    runtime, Box2dAdapterLimits.developmentDefaults());
            inspection.registerWorld("main", world, worldSpec());
            inspection.registerBody("fixture-owner", "main", fixtureOwner);
            Box2dRegistration<Fixture> fixtureRegistration = inspection.registerFixture(
                    "shape", "fixture-owner", originalFixture);
            Box2dRegistration<Body> bodyRegistration = inspection.registerBody(
                    "moving", "main", originalBody);

            fixtureOwner.destroyFixture(originalFixture);
            CircleShape replacementShape = new CircleShape();
            replacementShape.setRadius(2);
            Fixture replacementFixture = fixtureOwner.createFixture(replacementShape, 2);
            replacementShape.dispose();
            fixtureRegistration.rebind(replacementFixture);
            world.destroyBody(originalBody);
            BodyDef replacementBodyDefinition = new BodyDef();
            replacementBodyDefinition.position.set(5, 6);
            bodyRegistration.rebind(world.createBody(replacementBodyDefinition));
            runtime.start();

            assertEquals(RuntimeValues.decimal(2), property(runtime.entity(
                    fixtureRegistration.runtimeEntityId()).orElseThrow(), "density"));
            assertEquals(RuntimeValues.vector2(5, 6), property(runtime.entity(
                    bodyRegistration.runtimeEntityId()).orElseThrow(), "position"));
            runtime.close();
            inspection.close();
        } finally {
            world.dispose();
        }
    }

    private static Box2dWorldSpec worldSpec() {
        return new Box2dWorldSpec(true, true, true, 6, 2,
                OptionalDouble.of(60), new Box2dUnitTransform(100));
    }

    private static RuntimeValue property(EntitySnapshot entity, String name) {
        return entity.property(name).orElseThrow();
    }

    private static List<String> propertyNames(EntitySnapshot entity) {
        return entity.properties().stream().map(RuntimeValue.Field::name).toList();
    }
}
