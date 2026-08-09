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
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.GdxNativesLoader;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntitySnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
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
            assertEquals("box2d.world", worldEntity.type().value());
            assertEquals(RuntimeValues.vector2(0, -9.8), property(worldEntity, "gravity"));
            assertEquals(RuntimeValues.integer(1), property(worldEntity, "registeredBodyCount"));
            assertEquals(RuntimeValues.integer(2), property(worldEntity, "totalBodyCount"));
            assertEquals(RuntimeValues.decimal(100),
                    property(worldEntity, "renderUnitsPerMeter"));

            EntitySnapshot bodyEntity = runtime.entity(EntityId.of("box2d.body.ball"))
                    .orElseThrow();
            assertEquals(RuntimeValues.enumValue("DYNAMIC"), property(bodyEntity, "bodyType"));
            assertEquals(RuntimeValues.vector2(2, 3), property(bodyEntity, "position"));
            assertEquals(RuntimeValues.vector2(4, 5), property(bodyEntity, "linearVelocity"));
            assertEquals(RuntimeValues.bool(true), property(bodyEntity, "active"));
            assertEquals(RuntimeValues.integer(1), property(bodyEntity, "registeredFixtureCount"));

            EntitySnapshot fixtureEntity = runtime.entity(EntityId.of("box2d.fixture.ball-shape"))
                    .orElseThrow();
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

    private static Box2dWorldSpec worldSpec() {
        return new Box2dWorldSpec(true, true, true, 6, 2,
                OptionalDouble.of(60), new Box2dUnitTransform(100));
    }

    private static RuntimeValue property(EntitySnapshot entity, String name) {
        return entity.property(name).orElseThrow();
    }
}
