package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.CommandState;
import io.github.teemuki8.libgdx.agent.runtime.mcp.RuntimeToolHandler;
import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolJson;
import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolVersion;
import io.github.teemuki8.libgdx.agent.runtime.protocol.PublishedRuntime;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeCommand;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRequest;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeResponse;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class FixtureProtocolAndMcpTest {
    @Test
    void fixtureEvidenceRoundTripsThroughProtocol() {
        Fixture fixture = fixture();
        try (PublishedRuntime publication = fixture.registry.publish(fixture.runtime)) {
            assertEquals(fixture.runtime.sessionId(), publication.sessionId());
            RuntimeResponse response = new RuntimeProtocolService(fixture.registry).execute(
                    new RuntimeRequest(ProtocolVersion.V1, "fixture-events",
                            DeterministicSimulation.SESSION_ID.value(),
                            new RuntimeCommand.Events(
                                    0, 45, "projectile.hit", false,
                                    "enemy-2", "projectile-3", 10)));
            RuntimeResponse decoded = ProtocolJson.decodeResponse(ProtocolJson.encode(response));
            RuntimeResponse.Result.Events result = assertInstanceOf(
                    RuntimeResponse.Result.Events.class,
                    assertInstanceOf(RuntimeResponse.Success.class, decoded).result());
            assertEquals(1, result.page().items().size());
            assertEquals(25, ((io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue.IntegerValue)
                    result.page().items().getFirst().attributes().getFirst().value()).value());

            RuntimeResponse capabilityResponse = new RuntimeProtocolService(fixture.registry)
                    .execute(new RuntimeRequest(ProtocolVersion.V1_1, "fixture-capabilities",
                            DeterministicSimulation.SESSION_ID.value(),
                            new RuntimeCommand.Capabilities()));
            RuntimeResponse.Result.Capabilities capabilities = assertInstanceOf(
                    RuntimeResponse.Result.Capabilities.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            ProtocolJson.decodeResponse(ProtocolJson.encode(capabilityResponse)))
                            .result());
            assertEquals(List.of("changes", "decisions", "entities", "events", "frames"),
                    capabilities.capabilityReport().orElseThrow().capabilities().stream()
                            .map(capability -> capability.id()).toList());
        }
        fixture.runtime.close();
    }

    @Test
    void fixtureEvidenceIsAvailableThroughMcpToolCalls() {
        Fixture fixture = fixture();
        try (PublishedRuntime publication = fixture.registry.publish(fixture.runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(fixture.registry))) {
            assertEquals(fixture.runtime.sessionId(), publication.sessionId());
            McpSchema.CallToolResult snapshot = handler.handle(call("runtime_snapshot", Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "entityId", "enemy-1"))).block(Duration.ofSeconds(5));
            assertNotNull(snapshot);
            assertFalse(snapshot.isError());
            assertTrue(snapshot.structuredContent().toString().contains("enemy-1"));

            McpSchema.CallToolResult events = handler.handle(call("runtime_events", Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "eventType", "projectile.hit"))).block(Duration.ofSeconds(5));
            assertNotNull(events);
            assertFalse(events.isError());
            assertTrue(events.structuredContent().toString().contains("projectile.hit"));

            McpSchema.CallToolResult decisions = handler.handle(call("runtime_decisions", Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "reasonCode", "out-of-range"))).block(Duration.ofSeconds(5));
            assertNotNull(decisions);
            assertFalse(decisions.isError());
            assertTrue(decisions.structuredContent().toString().contains("out-of-range"));

            McpSchema.CallToolResult capabilities = handler.handle(call(
                    "runtime_capabilities", Map.of(
                            "sessionId", DeterministicSimulation.SESSION_ID.value(),
                            "protocolMinor", 1))).block(Duration.ofSeconds(5));
            assertNotNull(capabilities);
            assertFalse(capabilities.isError());
            assertTrue(capabilities.structuredContent().toString().contains("capabilityReport"));
        }
        fixture.runtime.close();
    }

    @Test
    void applicationOwnedFixtureQueueIsObservableAndCancellableThroughMcp() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        DeterministicSimulation simulation = new DeterministicSimulation();
        AgentRuntime runtime = simulation.startRuntime(applicationQueue::addLast);
        int[] executions = {0};
        runtime.commands().orElseThrow().submit(
                "fixture-command", Duration.ofSeconds(1), () -> executions[0]++);
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            McpSchema.CallToolResult status = handler.handle(call(
                    "runtime_command_status", Map.of(
                            "sessionId", DeterministicSimulation.SESSION_ID.value(),
                            "commandRequestId", "fixture-command")))
                    .block(Duration.ofSeconds(5));
            assertNotNull(status);
            assertFalse(status.isError());
            assertTrue(status.structuredContent().toString().contains(CommandState.QUEUED.name()));

            McpSchema.CallToolResult cancellation = handler.handle(call(
                    "runtime_command_cancel", Map.of(
                            "sessionId", DeterministicSimulation.SESSION_ID.value(),
                            "commandRequestId", "fixture-command")))
                    .block(Duration.ofSeconds(5));
            assertNotNull(cancellation);
            assertFalse(cancellation.isError());
            assertTrue(cancellation.structuredContent().toString().contains("accepted=true"));
        }
        applicationQueue.removeFirst().run();
        assertEquals(0, executions[0]);
        runtime.close();
    }

    private static McpSchema.CallToolRequest call(
            String name, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
    }

    private static Fixture fixture() {
        DeterministicSimulation simulation = new DeterministicSimulation();
        AgentRuntime runtime = simulation.startRuntime();
        IntStream.rangeClosed(1, 45).forEach(frame -> simulation.advance(runtime, frame));
        return new Fixture(runtime, new RuntimeRegistry());
    }

    private record Fixture(AgentRuntime runtime, RuntimeRegistry registry) {}
}
