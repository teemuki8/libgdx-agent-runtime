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
import com.badlogic.gdx.physics.box2d.PolygonShape;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.GdxNativesLoader;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dAdapterLimits;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dAssertions;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dContactLimits;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dContactPolicy;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dContacts;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dInspection;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dUnitTransform;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dWorldSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.AssertionStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntitySnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameId;
import io.github.teemuki8.libgdx.agent.runtime.core.ExecutionEpochId;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertionResult;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertionScope;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertionSpec;
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
    void actualBox2dContactEvidenceUsesExistingProtocolAndMcpQueries() {
        World world = new World(new Vector2(0, -10), true);
        try {
            Body ground = world.createBody(new BodyDef());
            PolygonShape groundShape = new PolygonShape();
            groundShape.setAsBox(8, 0.5f);
            Fixture groundFixture = ground.createFixture(groundShape, 0);
            groundShape.dispose();

            BodyDef bodyDefinition = new BodyDef();
            bodyDefinition.type = BodyDef.BodyType.DynamicBody;
            bodyDefinition.position.set(0, 1.05f);
            Body ball = world.createBody(bodyDefinition);
            CircleShape shape = new CircleShape();
            shape.setRadius(0.5f);
            Fixture fixture = ball.createFixture(shape, 1);
            shape.dispose();
            ball.setLinearVelocity(0, -2);

            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("box2d-inspection-fixture"))
                    .build();
            try (Box2dInspection inspection = new Box2dInspection(
                    runtime, Box2dAdapterLimits.developmentDefaults())) {
                inspection.registerWorld("main", world, new Box2dWorldSpec(
                        true, true, true, 6, 2, OptionalDouble.of(60),
                        new Box2dUnitTransform(100)));
                inspection.registerBody("ground", "main", ground);
                inspection.registerFixture("ground-shape", "ground", groundFixture);
                inspection.registerBody("ball", "main", ball);
                inspection.registerFixture("ball-shape", "ball", fixture);
                Box2dContacts contacts = inspection.registerContacts("main",
                        Box2dContactLimits.developmentDefaults(),
                        Box2dContactPolicy.developmentDefaults());
                world.setContactListener(contacts.listener());
                runtime.simulation().register(SimulationTimelineSpec.fixedStep(FIXED_STEP_NANOS));
                runtime.start();

                for (int tick = 0; tick < 3; tick++) {
                    runtime.simulation().tick(FIXED_STEP_NANOS, supplied -> {
                        contacts.captureStep(() -> world.step(
                                (float) (supplied / 1_000_000_000.0), 6, 2));
                        return supplied;
                    });
                }

                EntitySnapshot contactEntity = runtime.entity(
                        EntityId.of("box2d.contacts.main")).orElseThrow();
                assertEquals("box2d.contacts", contactEntity.type().value());
                RuntimeValue.ListValue activeContacts = assertInstanceOf(
                        RuntimeValue.ListValue.class,
                        contactEntity.property("activeContacts").orElseThrow());
                assertFalse(activeContacts.values().isEmpty());
                assertTrue(java.util.stream.LongStream.rangeClosed(1, 3)
                        .mapToObj(frame -> runtime.frame(new FrameId(frame)).orElseThrow())
                        .flatMap(frame -> frame.events().stream())
                        .anyMatch(event -> event.type().value().equals("box2d.contact.begin")));

                Box2dAssertions.ContactEndpoint ballEndpoint =
                        new Box2dAssertions.ContactEndpoint("ball", "ball-shape", 0);
                Box2dAssertions.ContactEndpoint groundEndpoint =
                        new Box2dAssertions.ContactEndpoint("ground", "ground-shape", 0);
                SimulationAssertionSpec contactOccurred = Box2dAssertions.contactOccurred(
                        "main", ballEndpoint, groundEndpoint);
                SimulationAssertionScope contactScope = new SimulationAssertionScope(
                        new ExecutionEpochId(0), 1, 3, 8);
                SimulationAssertionResult directAssertion = runtime.assertions()
                        .evaluateSimulation(contactOccurred, contactScope);
                assertEquals(AssertionStatus.PASS, directAssertion.status());

                SimulationAssertionSpec absentContact = Box2dAssertions.contactDidNotOccur(
                        "main", new Box2dAssertions.ContactEndpoint(
                                "ball", "ball-shape", 0),
                        new Box2dAssertions.ContactEndpoint(
                                "wall", "unobserved-wall-shape", 0));
                SimulationAssertionResult incompleteNegative = runtime.assertions()
                        .evaluateSimulation(absentContact, new SimulationAssertionScope(
                                new ExecutionEpochId(0), 1, 4, 8));
                assertEquals(AssertionStatus.INCONCLUSIVE, incompleteNegative.status());
                assertTrue(incompleteNegative.evidenceIncomplete());

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

                    RuntimeResponse.Result.Entity contactResult = assertInstanceOf(
                            RuntimeResponse.Result.Entity.class,
                            assertInstanceOf(RuntimeResponse.Success.class,
                                    new RuntimeProtocolService(registry).execute(new RuntimeRequest(
                                            ProtocolVersion.V2_1, "box2d-contacts",
                                            runtime.sessionId().value(),
                                            new RuntimeCommand.Entity(
                                                    "box2d.contacts.main", 0, 3, 10))))
                                    .result());
                    assertEquals("box2d.contacts", contactResult.latest().type().value());
                    assertFalse(assertInstanceOf(RuntimeValue.ListValue.class,
                            contactResult.latest().property("activeContacts").orElseThrow())
                            .values().isEmpty());

                    RuntimeResponse.Result.Events contactEvents = assertInstanceOf(
                            RuntimeResponse.Result.Events.class,
                            assertInstanceOf(RuntimeResponse.Success.class,
                                    new RuntimeProtocolService(registry).execute(new RuntimeRequest(
                                            ProtocolVersion.V2_1, "box2d-contact-events",
                                            runtime.sessionId().value(),
                                            new RuntimeCommand.Events(0, 3,
                                                    "box2d.contact.begin", false,
                                                    "box2d.body.ball", "box2d.body.ground", 10))))
                                    .result());
                    assertEquals(1, contactEvents.page().items().size());

                    RuntimeResponse.Result.SimulationAssertion protocolAssertion =
                            assertInstanceOf(RuntimeResponse.Result.SimulationAssertion.class,
                                    assertInstanceOf(RuntimeResponse.Success.class,
                                            new RuntimeProtocolService(registry).execute(
                                                    new RuntimeRequest(
                                                            ProtocolVersion.V2_2,
                                                            "box2d-contact-assertion",
                                                            runtime.sessionId().value(),
                                                            new RuntimeCommand.SimulationAssert(
                                                                    contactOccurred.assertion(),
                                                                    contactOccurred
                                                                            .evidenceRequirements(),
                                                                    0, 1, 3, 8))))
                                            .result());
                    assertEquals(AssertionStatus.PASS,
                            protocolAssertion.result().status());

                    McpSchema.CallToolResult mcp = handler.handle(
                            McpSchema.CallToolRequest.builder("runtime_entity")
                                    .arguments(Map.of(
                                            "sessionId", runtime.sessionId().value(),
                                            "entityId", "box2d.body.ball"))
                                    .build()).block(Duration.ofSeconds(5));
                    assertNotNull(mcp);
                    assertFalse(mcp.isError());
                    assertTrue(mcp.structuredContent().toString().contains("box2d.body.ball"));

                    McpSchema.CallToolResult contactMcp = handler.handle(
                            McpSchema.CallToolRequest.builder("runtime_entity")
                                    .arguments(Map.of(
                                            "sessionId", runtime.sessionId().value(),
                                            "entityId", "box2d.contacts.main"))
                                    .build()).block(Duration.ofSeconds(5));
                    assertNotNull(contactMcp);
                    assertFalse(contactMcp.isError());
                    assertTrue(contactMcp.structuredContent().toString()
                            .contains("activeContacts"));

                    McpSchema.CallToolResult contactEventsMcp = handler.handle(
                            McpSchema.CallToolRequest.builder("runtime_events")
                                    .arguments(Map.of(
                                            "sessionId", runtime.sessionId().value(),
                                            "eventType", "box2d.contact.begin",
                                            "subject", "box2d.body.ball",
                                            "source", "box2d.body.ground"))
                                    .build()).block(Duration.ofSeconds(5));
                    assertNotNull(contactEventsMcp);
                    assertFalse(contactEventsMcp.isError());
                    assertTrue(contactEventsMcp.structuredContent().toString()
                            .contains("box2d.contact.begin"));

                    McpSchema.CallToolResult assertionMcp = handler.handle(
                            McpSchema.CallToolRequest.builder("runtime_simulation_assert")
                                    .arguments(Map.of(
                                            "sessionId", runtime.sessionId().value(),
                                            "executionEpochId", 0,
                                            "fromEpochTick", 1,
                                            "toEpochTick", 3,
                                            "evidenceLimit", 8,
                                            "evidenceRequirements", java.util.List.of(Map.of(
                                                    "entityId", "box2d.contacts.main",
                                                    "property", "complete")),
                                            "assertion", Map.of(
                                                    "assertionType", "eventCount",
                                                    "eventType", "box2d.contact.begin",
                                                    "subject", "box2d.body.ball",
                                                    "source", "box2d.body.ground",
                                                    "attributes", Map.of(
                                                            "worldId", "main",
                                                            "key", Map.of(
                                                                    "fixtureAId", "ball-shape",
                                                                    "childIndexA", 0,
                                                                    "fixtureBId", "ground-shape",
                                                                    "childIndexB", 0)),
                                                    "expectation", "AT_LEAST_ONE",
                                                    "exactCount", 0)))
                                    .build()).block(Duration.ofSeconds(5));
                    assertNotNull(assertionMcp);
                    assertFalse(assertionMcp.isError(),
                            () -> String.valueOf(assertionMcp.structuredContent()));
                    assertEquals("PASS", ((Map<?, ?>) ((Map<?, ?>)
                            assertionMcp.structuredContent()).get("result")).get("status"));
                }
            } finally {
                runtime.close();
            }
        } finally {
            world.dispose();
        }
    }
}
