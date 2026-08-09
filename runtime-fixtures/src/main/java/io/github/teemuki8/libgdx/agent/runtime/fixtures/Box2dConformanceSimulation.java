package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.CircleShape;
import com.badlogic.gdx.physics.box2d.Fixture;
import com.badlogic.gdx.physics.box2d.Joint;
import com.badlogic.gdx.physics.box2d.PolygonShape;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.physics.box2d.joints.DistanceJointDef;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dAdapterLimits;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dContactLimits;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dContactPolicy;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dContacts;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dInspection;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dRegistration;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dUnitTransform;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dWorldSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.ApplicationCommandDispatcher;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointHandle;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointProvider;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepDropPolicy;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepSimulationConfiguration;
import io.github.teemuki8.libgdx.agent.runtime.core.InputSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.libgdx.LibGdxAgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.libgdx.LibGdxFixedStepSimulation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/** Actual-native Box2D scenario fixture composed only from public runtime integrations. */
public final class Box2dConformanceSimulation implements AutoCloseable {
    /** Stable fixture session. */
    public static final SessionId SESSION_ID = SessionId.of("box2d-conformance-fixture");
    /** Exact application-owned simulation step. */
    public static final long FIXED_STEP_NANOS = 16_666_667L;
    /** Registered Box2D world ID. */
    public static final String WORLD_ID = "main";
    /** Deterministic gravity scenario. */
    public static final String BALL_DROP = "ball-drop";
    /** Deterministic two-body collision scenario. */
    public static final String COLLISION = "collision";
    /** Deterministic input-driven player scenario. */
    public static final String PLAYER_MOVEMENT = "player-movement";

    private static final int VELOCITY_ITERATIONS = 8;
    private static final int POSITION_ITERATIONS = 3;
    private static final String[] BODY_IDS = {
        "ground", "wall", "ball", "collision-left", "collision-right", "player",
        "joint-a", "joint-b"
    };
    private static final String[] FIXTURE_IDS = {
        "ground-shape", "wall-shape", "ball-shape", "collision-left-shape",
        "collision-right-shape", "player-shape"
    };

    private final AgentRuntime runtime;
    private final Box2dInspection inspection;
    private final Box2dContacts contacts;
    private final LibGdxFixedStepSimulation simulation;
    private final Box2dRegistration<World> worldRegistration;
    private final String omittedFixtureId;
    private final long reportedExecutedStepNanos;
    private final boolean divergentPlayerInput;
    private final LinkedHashMap<String, Box2dRegistration<Body>> bodyRegistrations =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, Box2dRegistration<Fixture>> fixtureRegistrations =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, Box2dRegistration<Joint>> jointRegistrations =
            new LinkedHashMap<>();
    private NativeScene scene;
    private int renderCount;
    private int sceneGeneration;
    private long postPhysicsTicks;
    private boolean closed;

    /** Creates, registers, and starts the fixture on the application/capture thread. */
    public Box2dConformanceSimulation(ApplicationCommandDispatcher dispatcher) {
        this(dispatcher, FIXED_STEP_NANOS, null,
                Box2dAdapterLimits.developmentDefaults(),
                Box2dContactLimits.developmentDefaults(), false);
    }

    Box2dConformanceSimulation(ApplicationCommandDispatcher dispatcher,
            long reportedExecutedStepNanos, String omittedFixtureId,
            Box2dAdapterLimits adapterLimits, Box2dContactLimits contactLimits,
            boolean divergentPlayerInput) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        this.reportedExecutedStepNanos = reportedExecutedStepNanos;
        this.omittedFixtureId = omittedFixtureId;
        this.divergentPlayerInput = divergentPlayerInput;
        runtime = LibGdxAgentRuntime.builder()
                .captureThread(Thread.currentThread())
                .sessionId(SESSION_ID)
                .commandDispatcher(dispatcher)
                .build();
        scene = createScene(Scenario.BALL_DROP);
        inspection = new Box2dInspection(runtime, Objects.requireNonNull(adapterLimits,
                "adapterLimits"));
        worldRegistration = inspection.registerWorld(
                WORLD_ID, scene.world(), worldSpec());
        registerDescendants(scene);
        contacts = inspection.registerContacts(WORLD_ID,
                Objects.requireNonNull(contactLimits, "contactLimits"),
                Box2dContactPolicy.developmentDefaults());
        scene.world().setContactListener(contacts.listener());

        runtime.inputs().register(InputSpec.builder("move-player")
                .description("Sets horizontal player velocity before the selected physics tick")
                .requiredDecimal("velocityX")
                .handler(parameters -> {
                    float velocity = parameters.requiredDecimal("velocityX").floatValue();
                    if (divergentPlayerInput && sceneGeneration % 2 == 0) {
                        velocity += 1;
                    }
                    scene.bodies().get("player").setLinearVelocity(velocity, 0);
                })
                .build());
        runtime.entities().register(EntityId.of("fixture.post-physics"),
                EntityType.of("fixture.game-logic"), () -> "post-physics",
                inspector -> inspector.property("completedTicks", () -> postPhysicsTicks));
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override public CheckpointHandle create() {
                return checkpoint();
            }

            @Override public void restore(CheckpointHandle handle) {
                restoreCheckpoint((SceneCheckpoint) handle);
            }

            @Override public void dispose(CheckpointHandle handle) {
                // The immutable checkpoint owns no native object.
            }
        });
        runtime.scenarios().register(BALL_DROP,
                "Recreates the deterministic gravity ball-drop world",
                context -> reset(Scenario.BALL_DROP));
        runtime.scenarios().register(COLLISION,
                "Recreates two opposing dynamic bodies without gravity",
                context -> reset(Scenario.COLLISION));
        runtime.scenarios().register(PLAYER_MOVEMENT,
                "Recreates an input-driven player and static wall",
                context -> reset(Scenario.PLAYER_MOVEMENT));

        simulation = LibGdxFixedStepSimulation.acknowledged(runtime,
                new FixedStepSimulationConfiguration(
                        FIXED_STEP_NANOS, FIXED_STEP_NANOS * 32,
                        FIXED_STEP_NANOS * 16, 8, 1_024,
                        FixedStepDropPolicy.DROP_WHOLE_TICKS_KEEP_REMAINDER, true), tick -> {
                            contacts.captureStep(() -> scene.world().step(
                                    tick.fixedStepSeconds(),
                                    VELOCITY_ITERATIONS, POSITION_ITERATIONS));
                            gameLogicAfterPhysics();
                            return reportedExecutedStepNanos;
                        });
        runtime.start();
    }

    /** Returns the started runtime. */
    public AgentRuntime runtime() {
        return runtime;
    }

    /** Returns the canonical libGDX fixed-step facade. */
    public LibGdxFixedStepSimulation simulation() {
        return simulation;
    }

    /** Records one presentation render without changing simulation evidence. */
    public void recordRender() {
        renderCount++;
    }

    /** Returns the number of supplementary presentation renders. */
    public int renderCount() {
        return renderCount;
    }

    /** Releases registrations and native state on the owner thread. */
    @Override public void close() {
        if (closed) {
            return;
        }
        inspection.close();
        runtime.close();
        scene.world().dispose();
        closed = true;
    }

    private void reset(Scenario scenario) {
        NativeScene replacement = createScene(scenario);
        World previous = scene.world();
        jointRegistrations.values().forEach(Box2dRegistration::close);
        jointRegistrations.clear();
        fixtureRegistrations.values().forEach(Box2dRegistration::close);
        fixtureRegistrations.clear();
        bodyRegistrations.values().forEach(Box2dRegistration::close);
        bodyRegistrations.clear();
        worldRegistration.rebind(replacement.world());
        registerDescendants(replacement);
        replacement.world().setContactListener(contacts.listener());
        scene = replacement;
        sceneGeneration++;
        runtime.fixedStepSimulation().clearAccumulator();
        previous.dispose();
    }

    private void registerDescendants(NativeScene value) {
        for (String bodyId : BODY_IDS) {
            bodyRegistrations.put(bodyId,
                    inspection.registerBody(bodyId, WORLD_ID, value.bodies().get(bodyId)));
        }
        for (int index = 0; index < FIXTURE_IDS.length; index++) {
            String fixtureId = FIXTURE_IDS[index];
            String bodyId = BODY_IDS[index];
            if (!fixtureId.equals(omittedFixtureId)) {
                fixtureRegistrations.put(fixtureId, inspection.registerFixture(
                        fixtureId, bodyId, value.fixtures().get(fixtureId)));
            }
        }
        jointRegistrations.put("static-link", inspection.registerJoint(
                "static-link", WORLD_ID, value.joints().get("static-link")));
    }

    private static Box2dWorldSpec worldSpec() {
        return new Box2dWorldSpec(true, true, true,
                VELOCITY_ITERATIONS, POSITION_ITERATIONS,
                OptionalDouble.of(1_000_000_000d / FIXED_STEP_NANOS),
                new Box2dUnitTransform(100));
    }

    private static NativeScene createScene(Scenario scenario) {
        World world = new World(new Vector2(0,
                scenario == Scenario.BALL_DROP ? -10 : 0), true);
        world.setWarmStarting(true);
        world.setContinuousPhysics(true);
        LinkedHashMap<String, Body> bodies = new LinkedHashMap<>();
        LinkedHashMap<String, Fixture> fixtures = new LinkedHashMap<>();

        Body ground = boxBody(world, BodyDef.BodyType.StaticBody, 0, 0, true);
        bodies.put("ground", ground);
        fixtures.put("ground-shape", boxFixture(ground, 8, 0.5f, 0));

        Body wall = boxBody(world, BodyDef.BodyType.StaticBody, 3, 2,
                scenario == Scenario.PLAYER_MOVEMENT);
        bodies.put("wall", wall);
        fixtures.put("wall-shape", boxFixture(wall, 0.5f, 3, 0));

        Body ball = circleBody(world, 0, 5, scenario == Scenario.BALL_DROP);
        bodies.put("ball", ball);
        fixtures.put("ball-shape", circleFixture(ball, 0.5f));

        Body left = circleBody(world, -1.5f, 1, scenario == Scenario.COLLISION);
        left.setLinearVelocity(2, 0);
        bodies.put("collision-left", left);
        fixtures.put("collision-left-shape", circleFixture(left, 0.5f));

        Body right = circleBody(world, 1.5f, 1, scenario == Scenario.COLLISION);
        right.setLinearVelocity(-2, 0);
        bodies.put("collision-right", right);
        fixtures.put("collision-right-shape", circleFixture(right, 0.5f));

        Body player = circleBody(world, 0, 1, scenario == Scenario.PLAYER_MOVEMENT);
        player.setFixedRotation(true);
        bodies.put("player", player);
        fixtures.put("player-shape", circleFixture(player, 0.5f));

        Body jointA = boxBody(world, BodyDef.BodyType.DynamicBody, 20, 20, false);
        bodies.put("joint-a", jointA);
        Body jointB = boxBody(world, BodyDef.BodyType.DynamicBody, 21, 20, false);
        bodies.put("joint-b", jointB);
        DistanceJointDef jointDefinition = new DistanceJointDef();
        jointDefinition.initialize(jointA, jointB,
                jointA.getWorldCenter(), jointB.getWorldCenter());
        LinkedHashMap<String, Joint> joints = new LinkedHashMap<>();
        joints.put("static-link", world.createJoint(jointDefinition));

        return new NativeScene(world, Map.copyOf(bodies), Map.copyOf(fixtures),
                Map.copyOf(joints));
    }

    private static Body boxBody(World world, BodyDef.BodyType type,
            float x, float y, boolean active) {
        BodyDef definition = new BodyDef();
        definition.type = type;
        definition.position.set(x, y);
        definition.active = active;
        return world.createBody(definition);
    }

    private static Body circleBody(World world, float x, float y, boolean active) {
        Body body = boxBody(world, BodyDef.BodyType.DynamicBody, x, y, active);
        body.setLinearDamping(0.05f);
        return body;
    }

    private static Fixture boxFixture(Body body, float halfWidth, float halfHeight, float density) {
        PolygonShape shape = new PolygonShape();
        try {
            shape.setAsBox(halfWidth, halfHeight);
            Fixture fixture = body.createFixture(shape, density);
            fixture.setFriction(0.6f);
            fixture.setRestitution(0);
            return fixture;
        } finally {
            shape.dispose();
        }
    }

    private static Fixture circleFixture(Body body, float radius) {
        CircleShape shape = new CircleShape();
        try {
            shape.setRadius(radius);
            Fixture fixture = body.createFixture(shape, 1);
            fixture.setFriction(0.6f);
            fixture.setRestitution(0);
            return fixture;
        } finally {
            shape.dispose();
        }
    }

    private void gameLogicAfterPhysics() {
        postPhysicsTicks++;
    }

    private SceneCheckpoint checkpoint() {
        LinkedHashMap<String, BodyState> bodies = new LinkedHashMap<>();
        scene.bodies().forEach((id, body) -> bodies.put(id, new BodyState(
                body.isActive(), body.getPosition().x, body.getPosition().y,
                body.getAngle(), body.getLinearVelocity().x, body.getLinearVelocity().y,
                body.getAngularVelocity(), body.isAwake())));
        return new SceneCheckpoint(Map.copyOf(bodies), postPhysicsTicks);
    }

    private void restoreCheckpoint(SceneCheckpoint checkpoint) {
        checkpoint.bodies().forEach((id, state) -> {
            Body body = scene.bodies().get(id);
            body.setActive(state.active());
            body.setTransform(state.x(), state.y(), state.angle());
            body.setLinearVelocity(state.velocityX(), state.velocityY());
            body.setAngularVelocity(state.angularVelocity());
            body.setAwake(state.awake());
        });
        postPhysicsTicks = checkpoint.postPhysicsTicks();
        runtime.fixedStepSimulation().clearAccumulator();
    }

    private enum Scenario {
        BALL_DROP, COLLISION, PLAYER_MOVEMENT
    }

    private record NativeScene(
            World world, Map<String, Body> bodies, Map<String, Fixture> fixtures,
            Map<String, Joint> joints) {}

    private record SceneCheckpoint(
            Map<String, BodyState> bodies, long postPhysicsTicks) implements CheckpointHandle {}

    private record BodyState(boolean active, float x, float y, float angle,
            float velocityX, float velocityY, float angularVelocity, boolean awake) {}
}
