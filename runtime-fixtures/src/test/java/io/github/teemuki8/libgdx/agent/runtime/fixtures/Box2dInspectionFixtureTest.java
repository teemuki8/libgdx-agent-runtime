package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.Box2D;
import com.badlogic.gdx.physics.box2d.CircleShape;
import com.badlogic.gdx.physics.box2d.Fixture;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.GdxNativesLoader;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dAdapterLimits;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dInspection;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dUnitTransform;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dWorldSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTimelineSpec;
import io.github.teemuki8.libgdx.agent.runtime.mcp.RuntimeToolHandler;
import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolVersion;
import io.github.teemuki8.libgdx.agent.runtime.protocol.PublishedRuntime;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeCommand;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRequest;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeResponse;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Box2dInspectionFixtureTest {
    private static final long FIXED_STEP_NANOS = 16_666_667L;

    @BeforeAll
    static void initializeNativeBox2d() {
        GdxNativesLoader.load();
        Box2D.init();
    }

    @Test
    void actualBox2dStepIsCapturedAndAvailableThroughExistingProtocolEntityQuery() {
        World world = new World(new Vector2(0, -10), true);
        try {
            BodyDef bodyDefinition = new BodyDef();
            bodyDefinition.type = BodyDef.BodyType.DynamicBody;
            bodyDefinition.position.set(2, 4);
            Body ball = world.createBody(bodyDefinition);
            CircleShape shape = new CircleShape();
            shape.setRadius(0.5f);
            Fixture fixture = ball.createFixture(shape, 1);
            shape.dispose();

            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("box2d-inspection-fixture"))
                    .build();
            try (Box2dInspection inspection = new Box2dInspection(
                    runtime, Box2dAdapterLimits.developmentDefaults())) {
                inspection.registerWorld("main", world, new Box2dWorldSpec(
                        true, true, true, 6, 2, OptionalDouble.of(60),
                        new Box2dUnitTransform(100)));
                inspection.registerBody("ball", "main", ball);
                inspection.registerFixture("ball-shape", "ball", fixture);
                runtime.simulation().register(SimulationTimelineSpec.fixedStep(FIXED_STEP_NANOS));
                runtime.start();

                for (int tick = 0; tick < 3; tick++) {
                    runtime.simulation().tick(FIXED_STEP_NANOS, supplied -> {
                        world.step((float) (supplied / 1_000_000_000.0), 6, 2);
                        return supplied;
                    });
                }

                RuntimeRegistry registry = new RuntimeRegistry();
                try (PublishedRuntime publication = registry.publish(runtime);
                        RuntimeToolHandler handler = new RuntimeToolHandler(
                                new RuntimeProtocolService(registry))) {
                    assertEquals(runtime.sessionId(), publication.sessionId());
                    RuntimeResponse.Result.Entity entity = assertInstanceOf(
                            RuntimeResponse.Result.Entity.class,
                            assertInstanceOf(RuntimeResponse.Success.class,
                                    new RuntimeProtocolService(registry).execute(new RuntimeRequest(
                                            ProtocolVersion.V2_1, "box2d-ball",
                                            runtime.sessionId().value(),
                                            new RuntimeCommand.Entity(
                                                    "box2d.body.ball", 0, 3, 10))))
                                    .result());

                    assertEquals("box2d.body", entity.latest().type().value());
                    RuntimeValue.Vector2Value position = assertInstanceOf(
                            RuntimeValue.Vector2Value.class,
                            entity.latest().property("position").orElseThrow());
                    assertTrue(position.y().value().doubleValue() < 4);
                    assertEquals(RuntimeValues.integer(1),
                            entity.latest().property("registeredFixtureCount").orElseThrow());

                    McpSchema.CallToolResult mcp = handler.handle(
                            McpSchema.CallToolRequest.builder("runtime_entity")
                                    .arguments(Map.of(
                                            "sessionId", runtime.sessionId().value(),
                                            "entityId", "box2d.body.ball"))
                                    .build()).block(Duration.ofSeconds(5));
                    assertNotNull(mcp);
                    assertFalse(mcp.isError());
                    assertTrue(mcp.structuredContent().toString().contains("box2d.body.ball"));
                }
            } finally {
                runtime.close();
            }
        } finally {
            world.dispose();
        }
    }
}
