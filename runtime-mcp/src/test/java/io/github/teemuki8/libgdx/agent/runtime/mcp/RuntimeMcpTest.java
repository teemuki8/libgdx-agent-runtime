package io.github.teemuki8.libgdx.agent.runtime.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.BaselineKind;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionScope;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionType;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.EventSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.Reason;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolJson;
import io.github.teemuki8.libgdx.agent.runtime.protocol.PublishedRuntime;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class RuntimeMcpTest {
    private static final ObjectMapper JSON = ProtocolJson.mapper();

    @Test
    void catalogContainsExactlyEightClosedSchemas() {
        RuntimeToolCatalog catalog = new RuntimeToolCatalog();
        assertEquals(List.of(
                "runtime_sessions", "runtime_capabilities", "runtime_frames", "runtime_snapshot",
                "runtime_entity", "runtime_changes", "runtime_events", "runtime_decisions"),
                catalog.tools().stream().map(McpSchema.Tool::name).toList());
        catalog.tools().forEach(tool -> {
            assertEquals(false, tool.inputSchema().get("additionalProperties"));
            assertNotNull(tool.description());
        });
        McpSchema.Tool capabilities = catalog.tool("runtime_capabilities");
        Map<?, ?> properties = (Map<?, ?>) capabilities.inputSchema().get("properties");
        assertTrue(properties.containsKey("protocolMinor"));
    }

    @Test
    void invokesAllEightToolsWithDeterministicStructuredResults() throws Exception {
        Fixture fixture = fixture();
        try (PublishedRuntime publication = fixture.registry.publish(fixture.runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(fixture.registry))) {
            assertEquals(fixture.runtime.sessionId(), publication.sessionId());
            List<McpSchema.CallToolRequest> calls = List.of(
                    call("runtime_sessions", Map.of()),
                    call("runtime_capabilities", Map.of("sessionId", "mcp-fixture")),
                    call("runtime_frames", Map.of("sessionId", "mcp-fixture")),
                    call("runtime_snapshot", Map.of("sessionId", "mcp-fixture")),
                    call("runtime_entity", Map.of(
                            "sessionId", "mcp-fixture", "entityId", "enemy-1")),
                    call("runtime_changes", Map.of(
                            "sessionId", "mcp-fixture", "property", "health")),
                    call("runtime_events", Map.of(
                            "sessionId", "mcp-fixture", "eventType", "damage.applied")),
                    call("runtime_decisions", Map.of(
                            "sessionId", "mcp-fixture", "reasonCode", "out-of-range")));
            for (McpSchema.CallToolRequest call : calls) {
                McpSchema.CallToolResult first =
                        handler.handle(call).block(Duration.ofSeconds(5));
                McpSchema.CallToolResult second =
                        handler.handle(call).block(Duration.ofSeconds(5));
                assertNotNull(first);
                assertFalse(first.isError(), call.name());
                assertEquals(JSON.writeValueAsString(first.structuredContent()),
                        JSON.writeValueAsString(second.structuredContent()), call.name());
            }
        }
    }

    @Test
    void malformedNonexistentAndExcessiveCallsReturnTypedErrors() {
        Fixture fixture = fixture();
        try (PublishedRuntime publication = fixture.registry.publish(fixture.runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(fixture.registry))) {
            assertEquals(fixture.runtime.sessionId(), publication.sessionId());
            McpSchema.CallToolResult malformed = handler.handle(call(
                    "runtime_snapshot", Map.of("sessionId", "mcp-fixture", "path", "/tmp")))
                    .block(Duration.ofSeconds(5));
            assertTrue(malformed.isError());
            assertEquals("INVALID_QUERY", structured(malformed).get("code"));

            McpSchema.CallToolResult missing = handler.handle(call(
                    "runtime_snapshot", Map.of("sessionId", "missing")))
                    .block(Duration.ofSeconds(5));
            assertTrue(missing.isError());
            assertEquals("SESSION_NOT_FOUND", structured(missing).get("code"));

            McpSchema.CallToolResult excessive = handler.handle(call(
                    "runtime_frames", Map.of("sessionId", "mcp-fixture", "limit", 1001)))
                    .block(Duration.ofSeconds(5));
            assertTrue(excessive.isError());
            assertEquals("INVALID_QUERY", structured(excessive).get("code"));
        }
    }

    @Test
    void capabilitiesPreserveV1ByDefaultAndOptIntoExtensionMetadata() {
        Fixture fixture = fixture();
        try (PublishedRuntime publication = fixture.registry.publish(fixture.runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(fixture.registry))) {
            assertEquals(fixture.runtime.sessionId(), publication.sessionId());
            McpSchema.CallToolResult v1 = handler.handle(call(
                    "runtime_capabilities", Map.of("sessionId", "mcp-fixture")))
                    .block(Duration.ofSeconds(5));
            assertNotNull(v1);
            assertFalse(v1.isError());
            assertFalse(structured(v1).containsKey("capabilityReport"));

            McpSchema.CallToolResult current = handler.handle(call(
                    "runtime_capabilities", Map.of(
                            "sessionId", "mcp-fixture", "protocolMinor", 1)))
                    .block(Duration.ofSeconds(5));
            assertNotNull(current);
            assertFalse(current.isError());
            assertTrue(structured(current).containsKey("capabilityReport"));
        }
    }

    @Test
    void registeredDispatchAddsClosedStatusAndCancellationTools() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        int[] executions = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("commands"))
                .commandDispatcher(applicationQueue::addLast)
                .build();
        runtime.start();
        runtime.commands().orElseThrow().submit(
                "action-1", Duration.ofSeconds(1), () -> executions[0]++);
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeToolCatalog catalog = new RuntimeToolCatalog(
                    new RuntimeProtocolService(registry).toolNames());
            assertEquals(11, catalog.tools().size());
            assertEquals(false,
                    catalog.tool("runtime_command_cancel").inputSchema()
                            .get("additionalProperties"));

            McpSchema.CallToolResult status = handler.handle(call(
                    "runtime_command_status", Map.of(
                            "sessionId", "commands", "commandRequestId", "action-1")))
                    .block(Duration.ofSeconds(5));
            assertFalse(status.isError());
            assertEquals("QUEUED", ((Map<?, ?>) structured(status).get("command")).get("status")
                    instanceof Map<?, ?> commandStatus ? commandStatus.get("state") : null);

            McpSchema.CallToolResult cancelled = handler.handle(call(
                    "runtime_command_cancel", Map.of(
                            "sessionId", "commands", "commandRequestId", "action-1")))
                    .block(Duration.ofSeconds(5));
            assertFalse(cancelled.isError());
            McpSchema.CallToolResult unknownField = handler.handle(call(
                    "runtime_command_status", Map.of(
                            "sessionId", "commands", "commandRequestId", "action-1",
                            "expression", "java.lang.Runtime")))
                    .block(Duration.ofSeconds(5));
            assertTrue(unknownField.isError());
            assertEquals("INVALID_QUERY", structured(unknownField).get("code"));
            applicationQueue.removeFirst().run();
            assertEquals(0, executions[0]);
        }
    }

    @Test
    void registeredScenariosAddClosedCatalogAndResetTools() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("scenarios"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.scenarios().register("basic-combat", "Known state", () -> {});
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            McpSchema.CallToolResult list = handler.handle(call(
                    "runtime_scenarios", Map.of("sessionId", "scenarios")))
                    .block(Duration.ofSeconds(5));
            assertFalse(list.isError());
            McpSchema.CallToolResult reset = handler.handle(call(
                    "runtime_reset", Map.of("sessionId", "scenarios",
                            "scenarioId", "basic-combat", "resetRequestId", "reset-1",
                            "timeoutNanos", 1_000)))
                    .block(Duration.ofSeconds(5));
            assertFalse(reset.isError());
            McpSchema.CallToolResult rejected = handler.handle(call(
                    "runtime_reset", Map.of("sessionId", "scenarios",
                            "scenarioId", "basic-combat", "resetRequestId", "reset-2",
                            "timeoutNanos", 1_000, "script", "System.exit(0)")))
                    .block(Duration.ofSeconds(5));
            assertTrue(rejected.isError());
        }
    }

    @Test
    void executionEpochToolUsesClosedProtocolThirteenSchema() {
        Fixture fixture = fixture();
        fixture.runtime.startEpoch(BaselineKind.CHECKPOINT_RESTORE);
        try (PublishedRuntime publication = fixture.registry.publish(fixture.runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(fixture.registry))) {
            assertEquals(fixture.runtime.sessionId(), publication.sessionId());
            McpSchema.CallToolResult result = handler.handle(call(
                    "runtime_epoch_frames", Map.of(
                            "sessionId", "mcp-fixture", "executionEpochId", 1)))
                    .block(Duration.ofSeconds(5));
            assertFalse(result.isError());
            assertTrue(result.structuredContent().toString().contains("CHECKPOINT_RESTORE"));
            McpSchema.CallToolResult rejected = handler.handle(call(
                    "runtime_epoch_frames", Map.of(
                            "sessionId", "mcp-fixture", "executionEpochId", 1,
                            "expression", "Runtime.getRuntime()")))
                    .block(Duration.ofSeconds(5));
            assertTrue(rejected.isError());
        }
    }

    @Test
    @Timeout(10)
    void stdioServerStartsAndShutsDownCleanlyAtEof() {
        RuntimeMcpServer server = RuntimeMcpServer.open(
                new RuntimeProtocolService(new RuntimeRegistry()),
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        server.awaitTermination();
        server.close();
    }

    private static McpSchema.CallToolRequest call(
            String name, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
    }

    private static Map<String, Object> structured(McpSchema.CallToolResult result) {
        return JSON.convertValue(
                result.structuredContent(), new TypeReference<Map<String, Object>>() {});
    }

    private static Fixture fixture() {
        long[] health = {100};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("mcp-fixture")).build();
        runtime.entities().register(EntityId.of("enemy-1"), EntityType.of("enemy"),
                () -> "Enemy", inspector -> inspector.property("health", () -> health[0]));
        runtime.start();
        runtime.frame(1, () -> {
            runtime.emit(EventSpec.type("damage.applied")
                    .subject(EntityId.of("enemy-1"))
                    .attribute("amount", RuntimeValues.integer(25)));
            try (DecisionScope decision = runtime.beginDecision(
                    DecisionType.of("target-selection"), EntityId.of("tower-1"))) {
                decision.reject(EntityId.of("enemy-2"), Reason.of("out-of-range"));
                decision.accept(EntityId.of("enemy-1"));
                decision.choose(EntityId.of("enemy-1"), Reason.of("nearest-in-range"));
            }
            health[0] = 75;
        });
        return new Fixture(runtime, new RuntimeRegistry());
    }

    private record Fixture(AgentRuntime runtime, RuntimeRegistry registry) {}
}
