package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import com.badlogic.gdx.box2d.Box2d;
import com.badlogic.gdx.box2d.enums.b2BodyType;
import com.badlogic.gdx.box2d.structs.b2BodyDef;
import com.badlogic.gdx.box2d.structs.b2BodyId;
import com.badlogic.gdx.box2d.structs.b2Circle;
import com.badlogic.gdx.box2d.structs.b2JointId;
import com.badlogic.gdx.box2d.structs.b2Polygon;
import com.badlogic.gdx.box2d.structs.b2RevoluteJointDef;
import com.badlogic.gdx.box2d.structs.b2ShapeDef;
import com.badlogic.gdx.box2d.structs.b2ShapeId;
import com.badlogic.gdx.box2d.structs.b2Vec2;
import com.badlogic.gdx.box2d.structs.b2WorldDef;
import com.badlogic.gdx.box2d.structs.b2WorldId;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dAdapterLimits;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dContactLimits;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dInspection;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dRegistration;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dShapeSpec;
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
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingLimits;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.libgdx.LibGdxAgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.libgdx.LibGdxFixedStepSimulation;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Actual-native Box2D 3 scenario fixture composed only from public runtime integrations. */
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

    private static final int SUB_STEP_COUNT = 4;
    private static final String[] BODY_IDS = {
        "ground", "wall", "ball", "collision-left", "collision-right", "player",
        "joint-a", "joint-b"
    };
    private static final String[] SHAPE_IDS = {
        "ground-shape", "wall-shape", "ball-shape", "collision-left-shape",
        "collision-right-shape", "player-shape"
    };

    private final AgentRuntime runtime;
    private final Box2dInspection inspection;
    private final LibGdxFixedStepSimulation simulation;
    private final Box2dRegistration<b2WorldId> worldRegistration;
    private final String omittedShapeId;
    private final long reportedExecutedStepNanos;
    private final boolean divergentPlayerInput;
    private final LinkedHashMap<String, Box2dRegistration<b2BodyId>> bodyRegistrations =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, Box2dRegistration<b2ShapeId>> shapeRegistrations =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, Box2dRegistration<b2JointId>> jointRegistrations =
            new LinkedHashMap<>();
    private NativeScene scene;
    private int renderCount;
    private int sceneGeneration;
    private long postPhysicsTicks;
    private boolean playerControlActive = true;
    private boolean closed;

    /** Creates, registers, and starts the fixture on the application/capture thread. */
    public Box2dConformanceSimulation(ApplicationCommandDispatcher dispatcher) {
        this(dispatcher, FIXED_STEP_NANOS, null,
                Box2dAdapterLimits.developmentDefaults(),
                Box2dContactLimits.developmentDefaults(), false);
    }

    Box2dConformanceSimulation(ApplicationCommandDispatcher dispatcher,
            long reportedExecutedStepNanos, String omittedShapeId,
            Box2dAdapterLimits adapterLimits, Box2dContactLimits contactLimits,
            boolean divergentPlayerInput) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(contactLimits, "contactLimits");
        this.reportedExecutedStepNanos = reportedExecutedStepNanos;
        this.omittedShapeId = omittedShapeId;
        this.divergentPlayerInput = divergentPlayerInput;
        runtime = LibGdxAgentRuntime.builder()
                .captureThread(Thread.currentThread())
                .sessionId(SESSION_ID)
                .commandDispatcher(dispatcher)
                .recordingLimits(new RecordingLimits(16, 64, 4_096, 100_000,
                        Duration.ofHours(1).toNanos(), 1_048_576, 256, 512))
                .build();
        scene = createScene(Scenario.BALL_DROP);
        inspection = new Box2dInspection(runtime, Objects.requireNonNull(adapterLimits,
                "adapterLimits"));
        worldRegistration = inspection.registerWorld(WORLD_ID, scene.world(), worldSpec());
        registerDescendants(scene);

        runtime.inputs().register(InputSpec.builder("move-player")
                .description("Sets horizontal player velocity before the selected physics tick")
                .requiredDecimal("velocityX")
                .handler(parameters -> {
                    float velocity = parameters.requiredDecimal("velocityX").floatValue();
                    if (divergentPlayerInput && sceneGeneration % 2 == 0) {
                        velocity += 1;
                    }
                    Box2d.b2Body_SetLinearVelocity(
                            scene.bodies().get("player"), vector(velocity, 0));
                })
                .build());
        runtime.inputs().register(InputSpec.builder("set-player-control")
                .description("Activates or deactivates the player body before the selected tick")
                .requiredBoolean("active")
                .handler(parameters -> playerControlActive =
                        parameters.requiredBoolean("active"))
                .build());
        runtime.entities().register(EntityId.of("fixture.post-physics"),
                EntityType.of("fixture.game-logic"), () -> "post-physics",
                inspector -> inspector.property("completedTicks", () -> postPhysicsTicks));
        runtime.entities().register(EntityId.of("fixture.player-control"),
                EntityType.of("fixture.game-logic"), () -> "player-control",
                inspector -> inspector.property("active", () -> playerControlActive));
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override public CheckpointHandle create() {
                return checkpoint();
            }

            @Override public void restore(CheckpointHandle handle) {
                restoreCheckpoint((SceneCheckpoint) handle);
            }

            @Override public void dispose(CheckpointHandle handle) {
                // The immutable checkpoint owns no native resource.
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
                            setEnabled(scene.bodies().get("player"), playerControlActive);
                            Box2d.b2World_Step(
                                    scene.world(), tick.fixedStepSeconds(), SUB_STEP_COUNT);
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
        destroyScene(scene);
        closed = true;
    }

    private void reset(Scenario scenario) {
        replaceScene(createScene(scenario));
        postPhysicsTicks = 0;
        playerControlActive = Box2d.b2Body_IsEnabled(scene.bodies().get("player"));
    }

    private void replaceScene(NativeScene replacement) {
        NativeScene previous = scene;
        jointRegistrations.values().forEach(Box2dRegistration::close);
        jointRegistrations.clear();
        shapeRegistrations.values().forEach(Box2dRegistration::close);
        shapeRegistrations.clear();
        bodyRegistrations.values().forEach(Box2dRegistration::close);
        bodyRegistrations.clear();
        worldRegistration.rebind(replacement.world());
        registerDescendants(replacement);
        scene = replacement;
        sceneGeneration++;
        runtime.fixedStepSimulation().clearAccumulator();
        destroyScene(previous);
    }

    private void registerDescendants(NativeScene value) {
        for (String bodyId : BODY_IDS) {
            bodyRegistrations.put(bodyId,
                    inspection.registerBody(bodyId, WORLD_ID, value.bodies().get(bodyId)));
        }
        for (int index = 0; index < SHAPE_IDS.length; index++) {
            String shapeId = SHAPE_IDS[index];
            String bodyId = BODY_IDS[index];
            if (!shapeId.equals(omittedShapeId)) {
                shapeRegistrations.put(shapeId, inspection.registerShape(
                        shapeId, bodyId, value.shapes().get(shapeId),
                        Box2dShapeSpec.defaults()));
            }
        }
        jointRegistrations.put("static-link", inspection.registerJoint(
                "static-link", WORLD_ID, value.joints().get("static-link")));
    }

    private static Box2dWorldSpec worldSpec() {
        return new Box2dWorldSpec(SUB_STEP_COUNT, new Box2dUnitTransform(100));
    }

    private static NativeScene createScene(Scenario scenario) {
        b2WorldDef worldDef = Box2d.b2DefaultWorldDef();
        worldDef.gravity().y(scenario == Scenario.BALL_DROP ? -10 : 0);
        worldDef.workerCount(0);
        b2WorldId world = Box2d.b2CreateWorld(worldDef.asPointer());
        LinkedHashMap<String, b2BodyId> bodies = new LinkedHashMap<>();
        LinkedHashMap<String, b2ShapeId> shapes = new LinkedHashMap<>();

        b2BodyId ground = body(world, b2BodyType.b2_staticBody, 0, 0, true, false);
        bodies.put("ground", ground);
        shapes.put("ground-shape", boxShape(ground, 8, 0.5f, 0));

        b2BodyId wall = body(world, b2BodyType.b2_staticBody, 3, 2,
                scenario == Scenario.PLAYER_MOVEMENT, false);
        bodies.put("wall", wall);
        shapes.put("wall-shape", boxShape(wall, 0.5f, 3, 0));

        b2BodyId ball = circleBody(world, 0, 5, scenario == Scenario.BALL_DROP, false);
        bodies.put("ball", ball);
        shapes.put("ball-shape", circleShape(ball, 0.5f));

        b2BodyId left = circleBody(world, -1.5f, 1, scenario == Scenario.COLLISION, false);
        Box2d.b2Body_SetLinearVelocity(left, vector(2, 0));
        bodies.put("collision-left", left);
        shapes.put("collision-left-shape", circleShape(left, 0.5f));

        b2BodyId right = circleBody(world, 1.5f, 1, scenario == Scenario.COLLISION, false);
        Box2d.b2Body_SetLinearVelocity(right, vector(-2, 0));
        bodies.put("collision-right", right);
        shapes.put("collision-right-shape", circleShape(right, 0.5f));

        b2BodyId player = circleBody(world, 0, 1,
                scenario == Scenario.PLAYER_MOVEMENT, true);
        bodies.put("player", player);
        shapes.put("player-shape", circleShape(player, 0.5f));

        b2BodyId jointA = body(world, b2BodyType.b2_dynamicBody, 20, 20, false, false);
        b2BodyId jointB = body(world, b2BodyType.b2_dynamicBody, 21, 20, false, false);
        bodies.put("joint-a", jointA);
        bodies.put("joint-b", jointB);
        b2RevoluteJointDef jointDef = Box2d.b2DefaultRevoluteJointDef();
        jointDef.setBodyIdA(jointA);
        jointDef.setBodyIdB(jointB);
        LinkedHashMap<String, b2JointId> joints = new LinkedHashMap<>();
        joints.put("static-link", Box2d.b2CreateRevoluteJoint(world, jointDef.asPointer()));

        return new NativeScene(scenario, world, Map.copyOf(bodies), Map.copyOf(shapes),
                Map.copyOf(joints));
    }

    private static b2BodyId body(b2WorldId world, b2BodyType type,
            float x, float y, boolean enabled, boolean fixedRotation) {
        b2BodyDef definition = Box2d.b2DefaultBodyDef();
        definition.type(type);
        definition.position().x(x);
        definition.position().y(y);
        definition.isEnabled(enabled);
        definition.fixedRotation(fixedRotation);
        return Box2d.b2CreateBody(world, definition.asPointer());
    }

    private static b2BodyId circleBody(
            b2WorldId world, float x, float y, boolean enabled, boolean fixedRotation) {
        b2BodyDef definition = Box2d.b2DefaultBodyDef();
        definition.type(b2BodyType.b2_dynamicBody);
        definition.position().x(x);
        definition.position().y(y);
        definition.isEnabled(enabled);
        definition.fixedRotation(fixedRotation);
        definition.linearDamping(0.05f);
        return Box2d.b2CreateBody(world, definition.asPointer());
    }

    private static b2ShapeId boxShape(
            b2BodyId body, float halfWidth, float halfHeight, float density) {
        b2ShapeDef definition = shapeDef(density);
        b2Polygon polygon = Box2d.b2MakeBox(halfWidth, halfHeight);
        return Box2d.b2CreatePolygonShape(body, definition.asPointer(), polygon.asPointer());
    }

    private static b2ShapeId circleShape(b2BodyId body, float radius) {
        b2ShapeDef definition = shapeDef(1);
        b2Circle circle = new b2Circle();
        circle.radius(radius);
        return Box2d.b2CreateCircleShape(body, definition.asPointer(), circle.asPointer());
    }

    private static b2ShapeDef shapeDef(float density) {
        b2ShapeDef definition = Box2d.b2DefaultShapeDef();
        definition.density(density);
        definition.material().friction(0.6f);
        definition.material().restitution(0);
        return definition;
    }

    private static void setEnabled(b2BodyId body, boolean enabled) {
        if (enabled && !Box2d.b2Body_IsEnabled(body)) {
            Box2d.b2Body_Enable(body);
        } else if (!enabled && Box2d.b2Body_IsEnabled(body)) {
            Box2d.b2Body_Disable(body);
        }
    }

    private static b2Vec2 vector(float x, float y) {
        b2Vec2 vector = new b2Vec2();
        vector.x(x);
        vector.y(y);
        return vector;
    }

    private static void destroyScene(NativeScene value) {
        value.joints().values().stream().filter(Box2d::b2Joint_IsValid)
                .forEach(Box2d::b2DestroyJoint);
        value.shapes().values().stream().filter(Box2d::b2Shape_IsValid)
                .forEach(shape -> Box2d.b2DestroyShape(shape, true));
        value.bodies().values().stream().filter(Box2d::b2Body_IsValid)
                .forEach(Box2d::b2DestroyBody);
        if (Box2d.b2World_IsValid(value.world())) {
            Box2d.b2DestroyWorld(value.world());
        }
    }

    private void gameLogicAfterPhysics() {
        postPhysicsTicks++;
    }

    private SceneCheckpoint checkpoint() {
        LinkedHashMap<String, BodyState> bodies = new LinkedHashMap<>();
        scene.bodies().forEach((id, body) -> {
            b2Vec2 position = Box2d.b2Body_GetPosition(body);
            b2Vec2 velocity = Box2d.b2Body_GetLinearVelocity(body);
            bodies.put(id, new BodyState(
                    Box2d.b2Body_IsEnabled(body), position.x(), position.y(),
                    Box2d.b2Rot_GetAngle(Box2d.b2Body_GetRotation(body)),
                    velocity.x(), velocity.y(), Box2d.b2Body_GetAngularVelocity(body),
                    Box2d.b2Body_IsAwake(body)));
        });
        return new SceneCheckpoint(scene.scenario(), Map.copyOf(bodies), postPhysicsTicks);
    }

    private void restoreCheckpoint(SceneCheckpoint checkpoint) {
        replaceScene(createScene(checkpoint.scenario()));
        checkpoint.bodies().forEach((id, state) -> {
            b2BodyId body = scene.bodies().get(id);
            setEnabled(body, state.active());
            Box2d.b2Body_SetTransform(body, vector(state.x(), state.y()),
                    Box2d.b2MakeRot(state.angle()));
            Box2d.b2Body_SetLinearVelocity(body,
                    vector(state.velocityX(), state.velocityY()));
            Box2d.b2Body_SetAngularVelocity(body, state.angularVelocity());
            Box2d.b2Body_SetAwake(body, state.awake());
        });
        postPhysicsTicks = checkpoint.postPhysicsTicks();
        playerControlActive = Box2d.b2Body_IsEnabled(scene.bodies().get("player"));
    }

    private enum Scenario {
        BALL_DROP, COLLISION, PLAYER_MOVEMENT
    }

    private record NativeScene(
            Scenario scenario, b2WorldId world, Map<String, b2BodyId> bodies,
            Map<String, b2ShapeId> shapes, Map<String, b2JointId> joints) {}

    private record SceneCheckpoint(
            Scenario scenario, Map<String, BodyState> bodies,
            long postPhysicsTicks) implements CheckpointHandle {}

    private record BodyState(boolean active, float x, float y, float angle,
            float velocityX, float velocityY, float angularVelocity, boolean awake) {}
}
