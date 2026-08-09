package io.github.teemuki8.libgdx.agent.runtime.examples;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.CircleShape;
import com.badlogic.gdx.physics.box2d.Contact;
import com.badlogic.gdx.physics.box2d.ContactImpulse;
import com.badlogic.gdx.physics.box2d.ContactListener;
import com.badlogic.gdx.physics.box2d.Fixture;
import com.badlogic.gdx.physics.box2d.Joint;
import com.badlogic.gdx.physics.box2d.Manifold;
import com.badlogic.gdx.physics.box2d.PolygonShape;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.physics.box2d.joints.DistanceJointDef;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dAdapterLimits;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dAssertions;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dContactLimits;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dContactPolicy;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dContacts;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dDeterminism;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dInspection;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dRegistration;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dUnitTransform;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dVector;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dWorldSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.ApplicationCommandDispatcher;
import io.github.teemuki8.libgdx.agent.runtime.core.AssertionStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepSimulationConfiguration;
import io.github.teemuki8.libgdx.agent.runtime.core.InputSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertion;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertionScope;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTickQuery;
import io.github.teemuki8.libgdx.agent.runtime.libgdx.LibGdxAgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.libgdx.LibGdxFixedStepSimulation;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Standalone actual-native Box2D consumer using exact fixed ticks as authoritative evidence.
 *
 * <p>The application owns the world, listener installation, fixed-step callback, reset, and native
 * disposal. Stable strings—not native pointers—identify the explicitly selected world objects.
 */
public final class DeterministicBox2dExample implements AutoCloseable {
    /** Stable example session ID. */
    public static final SessionId SESSION_ID = SessionId.of("deterministic-box2d-example");
    /** Authoritative 60 Hz application step. */
    public static final long FIXED_STEP_NANOS = 16_666_667L;

    private static final String WORLD_ID = "main";
    private static final String SCENARIO_ID = "player-movement";
    private static final int VELOCITY_ITERATIONS = 8;
    private static final int POSITION_ITERATIONS = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final List<String> BODY_IDS =
            List.of("player", "wall", "joint-a", "joint-b");
    private static final List<String> FIXTURE_IDS = List.of("player-shape", "wall-shape");

    private final AgentRuntime runtime;
    private final Box2dInspection inspection;
    private final Box2dContacts contacts;
    private final ContactListener installedContactListener;
    private final Box2dRegistration<World> worldRegistration;
    private final LinkedHashMap<String, Box2dRegistration<Body>> bodyRegistrations =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, Box2dRegistration<Fixture>> fixtureRegistrations =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, Box2dRegistration<Joint>> jointRegistrations =
            new LinkedHashMap<>();
    private final LibGdxFixedStepSimulation simulation;
    private NativeScene scene;
    private int applicationBeginContacts;
    private boolean closed;

    private DeterministicBox2dExample(ApplicationCommandDispatcher dispatcher) {
        runtime = LibGdxAgentRuntime.builder()
                .captureThread(Thread.currentThread())
                .sessionId(SESSION_ID)
                .commandDispatcher(Objects.requireNonNull(dispatcher, "dispatcher"))
                .build();
        scene = createScene();
        inspection = new Box2dInspection(runtime, Box2dAdapterLimits.developmentDefaults());
        worldRegistration = inspection.registerWorld(WORLD_ID, scene.world(), worldSpec());
        registerScene(scene);
        contacts = inspection.registerContacts(WORLD_ID,
                Box2dContactLimits.developmentDefaults(),
                Box2dContactPolicy.developmentDefaults());
        installedContactListener = contacts.compose(new ContactListener() {
            @Override public void beginContact(Contact contact) {
                applicationBeginContacts++;
            }

            @Override public void endContact(Contact contact) {
                // This example's application listener needs only begin callbacks.
            }

            @Override public void preSolve(Contact contact, Manifold oldManifold) {
                // This example's application listener needs only begin callbacks.
            }

            @Override public void postSolve(Contact contact, ContactImpulse impulse) {
                // This example's application listener needs only begin callbacks.
            }
        });
        scene.world().setContactListener(installedContactListener);

        runtime.inputs().register(InputSpec.builder("move-player")
                .description("Sets horizontal velocity before an exact physics tick")
                .requiredDecimal("velocityX")
                .handler(parameters -> scene.bodies().get("player").setLinearVelocity(
                        parameters.requiredDecimal("velocityX").floatValue(), 0))
                .build());
        runtime.scenarios().register(SCENARIO_ID,
                "Recreates the deterministic player and wall world",
                context -> reset());
        simulation = LibGdxFixedStepSimulation.acknowledged(runtime,
                FixedStepSimulationConfiguration.developmentDefaults(FIXED_STEP_NANOS), tick -> {
                    contacts.captureStep(() -> scene.world().step(
                            tick.fixedStepSeconds(), VELOCITY_ITERATIONS, POSITION_ITERATIONS));
                    return tick.fixedStepNanos();
                });
        runtime.start();
    }

    /** Creates the example on the application/capture thread with application-owned dispatch. */
    public static DeterministicBox2dExample create(ApplicationCommandDispatcher dispatcher) {
        return new DeterministicBox2dExample(dispatcher);
    }

    /** Returns the started runtime for structured inspection and transport publication. */
    public AgentRuntime runtime() {
        return runtime;
    }

    /**
     * Runs reset, scheduled input, 90 exact physics ticks, assertions, and two selected reruns.
     */
    public Box2dResult runWorkflow() {
        var reset = runtime.scenarios().reset(SCENARIO_ID, "box2d-example-reset", TIMEOUT);
        var epoch = reset.executionEpochId().orElseThrow();
        runtime.controls().control(true, "box2d-example-pause", TIMEOUT);
        long targetTick = runtime.controls().currentTick() + 1;
        var parameters = RuntimeValues.object(RuntimeValues.field(
                "velocityX", RuntimeValues.decimal("4")));
        runtime.inputs().inject("move-player", "box2d-example-input", parameters,
                OptionalLong.of(targetTick), TIMEOUT);
        runtime.controls().advanceFixed("box2d-example-advance", 90, TIMEOUT);

        SimulationAssertionScope finalTick = new SimulationAssertionScope(epoch, 90, 90, 8);
        AssertionStatus position = runtime.assertions().evaluateSimulation(
                Box2dAssertions.bodyPositionApproximately(
                        "player", new Box2dVector(2, 1), 0.04,
                        SimulationAssertion.VectorToleranceMode.COMPONENT), finalTick).status();
        var player = new Box2dAssertions.ContactEndpoint("player", "player-shape", 0);
        var wall = new Box2dAssertions.ContactEndpoint("wall", "wall-shape", 0);
        AssertionStatus contact = runtime.assertions().evaluateSimulation(
                Box2dAssertions.contactOccurred(WORLD_ID, player, wall),
                new SimulationAssertionScope(epoch, 1, 90, 8)).status();
        boolean correlated = runtime.simulation().ticks(
                new SimulationTickQuery(epoch, 90, 90, 1)).ticks().getFirst()
                .resultingFrameId().isPresent();

        var settings = new Box2dDeterminism.WorldSettings(
                FIXED_STEP_NANOS, new Box2dVector(0, 0),
                VELOCITY_ITERATIONS, POSITION_ITERATIONS, true, true, true);
        var specification = Box2dDeterminism.builder(
                        WORLD_ID, settings, SCENARIO_ID, 7,
                        RuntimeValues.object(), 2, 90)
                .body("player", "position", "linearVelocity")
                .contactEvents()
                .input(1, "move-player", parameters)
                .build();
        var comparison = runtime.determinism().checkSimulation(
                specification, "box2d-example-determinism", TIMEOUT)
                .result().orElseThrow();

        return new Box2dResult(position, contact, comparison.status(), correlated,
                comparison.message().contains("whole-program determinism is proven"),
                applicationBeginContacts);
    }

    @Override public void close() {
        if (!closed) {
            inspection.close();
            runtime.close();
            scene.world().dispose();
            closed = true;
        }
    }

    private void reset() {
        NativeScene replacement = createScene();
        World previous = scene.world();
        jointRegistrations.values().forEach(Box2dRegistration::close);
        jointRegistrations.clear();
        fixtureRegistrations.values().forEach(Box2dRegistration::close);
        fixtureRegistrations.clear();
        bodyRegistrations.values().forEach(Box2dRegistration::close);
        bodyRegistrations.clear();
        worldRegistration.rebind(replacement.world());
        registerScene(replacement);
        replacement.world().setContactListener(installedContactListener);
        scene = replacement;
        applicationBeginContacts = 0;
        runtime.fixedStepSimulation().clearAccumulator();
        previous.dispose();
    }

    private void registerScene(NativeScene value) {
        for (String bodyId : BODY_IDS) {
            bodyRegistrations.put(bodyId,
                    inspection.registerBody(bodyId, WORLD_ID, value.bodies().get(bodyId)));
        }
        fixtureRegistrations.put("player-shape", inspection.registerFixture(
                "player-shape", "player", value.fixtures().get("player-shape")));
        fixtureRegistrations.put("wall-shape", inspection.registerFixture(
                "wall-shape", "wall", value.fixtures().get("wall-shape")));
        jointRegistrations.put("static-link", inspection.registerJoint(
                "static-link", WORLD_ID, value.joints().get("static-link")));
    }

    private static Box2dWorldSpec worldSpec() {
        return new Box2dWorldSpec(true, true, true,
                VELOCITY_ITERATIONS, POSITION_ITERATIONS,
                OptionalDouble.of(1_000_000_000d / FIXED_STEP_NANOS),
                new Box2dUnitTransform(100));
    }

    private static NativeScene createScene() {
        World world = new World(new Vector2(0, 0), true);
        world.setWarmStarting(true);
        world.setContinuousPhysics(true);
        LinkedHashMap<String, Body> bodies = new LinkedHashMap<>();
        LinkedHashMap<String, Fixture> fixtures = new LinkedHashMap<>();

        Body player = body(world, BodyDef.BodyType.DynamicBody, 0, 1, true);
        player.setFixedRotation(true);
        bodies.put("player", player);
        fixtures.put("player-shape", circleFixture(player, 0.5f));

        Body wall = body(world, BodyDef.BodyType.StaticBody, 3, 2, true);
        bodies.put("wall", wall);
        fixtures.put("wall-shape", boxFixture(wall, 0.5f, 3));

        Body jointA = body(world, BodyDef.BodyType.DynamicBody, 20, 20, false);
        Body jointB = body(world, BodyDef.BodyType.DynamicBody, 21, 20, false);
        bodies.put("joint-a", jointA);
        bodies.put("joint-b", jointB);
        DistanceJointDef definition = new DistanceJointDef();
        definition.initialize(jointA, jointB,
                jointA.getWorldCenter(), jointB.getWorldCenter());
        Map<String, Joint> joints = Map.of("static-link", world.createJoint(definition));
        return new NativeScene(world, Map.copyOf(bodies), Map.copyOf(fixtures), joints);
    }

    private static Body body(World world, BodyDef.BodyType type,
            float x, float y, boolean active) {
        BodyDef definition = new BodyDef();
        definition.type = type;
        definition.position.set(x, y);
        definition.active = active;
        return world.createBody(definition);
    }

    private static Fixture circleFixture(Body body, float radius) {
        CircleShape shape = new CircleShape();
        try {
            shape.setRadius(radius);
            Fixture fixture = body.createFixture(shape, 1);
            fixture.setFriction(0.6f);
            return fixture;
        } finally {
            shape.dispose();
        }
    }

    private static Fixture boxFixture(Body body, float halfWidth, float halfHeight) {
        PolygonShape shape = new PolygonShape();
        try {
            shape.setAsBox(halfWidth, halfHeight);
            Fixture fixture = body.createFixture(shape, 0);
            fixture.setFriction(0.6f);
            return fixture;
        } finally {
            shape.dispose();
        }
    }

    /** Bounded summary of the authoritative structured workflow evidence. */
    public record Box2dResult(
            AssertionStatus positionStatus,
            AssertionStatus contactStatus,
            DeterminismStatus determinismStatus,
            boolean tickFrameCorrelated,
            boolean wholeProgramDeterminismClaimed,
            int applicationBeginContacts) {}

    private record NativeScene(
            World world, Map<String, Body> bodies, Map<String, Fixture> fixtures,
            Map<String, Joint> joints) {}
}
