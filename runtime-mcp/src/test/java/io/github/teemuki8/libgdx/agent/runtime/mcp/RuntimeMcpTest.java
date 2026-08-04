package io.github.teemuki8.libgdx.agent.runtime.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.ActionSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.BaselineKind;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionScope;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionType;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.EventSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.FactMetadata;
import io.github.teemuki8.libgdx.agent.runtime.core.InputSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.ChangeCause;
import io.github.teemuki8.libgdx.agent.runtime.core.Reason;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationControllerSpec;
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
            assertEquals(15, catalog.tools().size());
            assertEquals(false,
                    catalog.tool("runtime_command_cancel").inputSchema()
                            .get("additionalProperties"));
            assertEquals(false,
                    catalog.tool("runtime_assert").inputSchema().get("additionalProperties"));

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
    void attributionToolsUseExactClosedMetadataFilters() {
        Fixture fixture = fixture();
        try (PublishedRuntime publication = fixture.registry.publish(fixture.runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(fixture.registry))) {
            assertEquals(fixture.runtime.sessionId(), publication.sessionId());
            for (String tool : List.of("runtime_attributed_changes",
                    "runtime_attributed_events", "runtime_attributed_decisions")) {
                McpSchema.CallToolResult result = handler.handle(call(tool, Map.of(
                        "sessionId", "mcp-fixture", "sourceSubsystem", "combat",
                        "correlationId", "attack-172"))).block(Duration.ofSeconds(5));
                assertFalse(result.isError(), tool);
                assertTrue(result.structuredContent().toString().contains("attack-172"), tool);
                McpSchema.CallToolResult rejected = handler.handle(call(tool, Map.of(
                        "sessionId", "mcp-fixture", "sourceSubsystem", "combat",
                        "expression", "getClass()"))).block(Duration.ofSeconds(5));
                assertTrue(rejected.isError(), tool);
            }
        }
    }

    @Test
    void semanticActionToolUsesRegisteredClosedNaturalJsonSchema() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        int[] executions = {0};
        AgentRuntime runtime = AgentRuntime.builder().sessionId(SessionId.of("actions"))
                .clock(() -> 1).commandDispatcher(queue::addLast).build();
        runtime.actions().register(ActionSpec.builder("player.attack")
                .description("Attack one target").requiredEntityId("targetEntity")
                .handler(parameters -> {
                    assertEquals("enemy-1",
                            parameters.requiredEntityId("targetEntity").value());
                    executions[0]++;
                    runtime.frame(1, () -> {});
                }).build());
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            McpSchema.CallToolResult catalog = handler.handle(call(
                    "runtime_actions", Map.of("sessionId", "actions")))
                    .block(Duration.ofSeconds(5));
            assertFalse(catalog.isError());
            Map<String, Object> request = Map.of(
                    "sessionId", "actions", "action", "player.attack",
                    "actionRequestId", "attack-1", "correlationId", "attack-172",
                    "timeoutNanos", 1_000,
                    "parameters", Map.of("targetEntity", "enemy-1"));
            McpSchema.CallToolResult queued = handler.handle(call("runtime_action", request))
                    .block(Duration.ofSeconds(5));
            assertFalse(queued.isError());
            queue.removeFirst().run();
            McpSchema.CallToolResult completed = handler.handle(call("runtime_action", request))
                    .block(Duration.ofSeconds(5));
            assertFalse(completed.isError());
            assertTrue(completed.structuredContent().toString().contains("completedFrameId"));
            assertEquals(1, executions[0]);

            McpSchema.CallToolResult unknown = handler.handle(call("runtime_action", Map.of(
                    "sessionId", "actions", "action", "player.attack",
                    "actionRequestId", "attack-2", "timeoutNanos", 1_000,
                    "parameters", Map.of("targetEntity", "enemy-1", "script", "run()"))))
                    .block(Duration.ofSeconds(5));
            assertTrue(unknown.isError());
            assertEquals(1, executions[0]);
        }
    }

    @Test
    void declarativeAssertionToolUsesClosedNaturalJsonSchema() {
        Fixture fixture = fixture();
        try (PublishedRuntime publication = fixture.registry.publish(fixture.runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(fixture.registry))) {
            assertEquals(fixture.runtime.sessionId(), publication.sessionId());
            McpSchema.Tool tool = new RuntimeToolCatalog(
                    new RuntimeProtocolService(fixture.registry).toolNames())
                    .tool("runtime_assert");
            Map<?, ?> toolProperties = (Map<?, ?>) tool.inputSchema().get("properties");
            List<?> assertionSchemas = (List<?>) ((Map<?, ?>) toolProperties.get("assertion"))
                    .get("oneOf");
            Map<?, ?> snapshotProperties = (Map<?, ?>) assertionSchemas.getLast();
            Map<?, ?> snapshotFields = (Map<?, ?>) snapshotProperties.get("properties");
            Map<?, ?> comparisonScope =
                    (Map<?, ?>) snapshotFields.get("comparisonScope");
            Map<?, ?> comparisonFields = (Map<?, ?>) comparisonScope.get("properties");
            assertEquals(100,
                    ((Map<?, ?>) comparisonFields.get("entityIds")).get("maxItems"));
            Map<String, Object> request = Map.of(
                    "sessionId", "mcp-fixture", "fromFrame", 0, "toFrame", 1,
                    "executionEpochId", 0, "evidenceLimit", 8,
                    "assertion", Map.of(
                            "assertionType", "propertyEquals", "entityId", "enemy-1",
                            "property", "health", "expected", 75));
            McpSchema.CallToolResult result = handler.handle(call("runtime_assert", request))
                    .block(Duration.ofSeconds(5));

            assertFalse(result.isError());
            assertTrue(result.structuredContent().toString().contains("PASS"));
            McpSchema.CallToolResult rejected = handler.handle(call("runtime_assert", Map.of(
                    "sessionId", "mcp-fixture", "fromFrame", 0, "toFrame", 1,
                    "executionEpochId", 0, "evidenceLimit", 8,
                    "assertion", Map.of(
                            "assertionType", "entityExists", "entityId", "enemy-1",
                            "expression", "getClass()")))).block(Duration.ofSeconds(5));
            assertTrue(rejected.isError());
        }
    }

    @Test
    void simulationControlToolsExposeClosedSchemasAndBoundedTickEvidence() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        int[] ticks = {0};
        AgentRuntime runtime = AgentRuntime.builder().sessionId(SessionId.of("mcp-control"))
                .clock(() -> 1).commandDispatcher(queue::addLast).build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .tick(deltaNanos -> ticks[0]++)
                .condition("ready", "At least one tick completed", () -> ticks[0] > 0)
                .build());
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeToolCatalog catalog =
                    new RuntimeToolCatalog(new RuntimeProtocolService(registry).toolNames());
            assertFalse((Boolean) catalog.tool("runtime_wait")
                    .inputSchema().get("additionalProperties"));
            assertEquals(2, ((List<?>) catalog.tool("runtime_control")
                    .inputSchema().get("oneOf")).size());
            assertTrue(handler.handle(call("runtime_control", Map.of(
                    "sessionId", "mcp-control", "action", "PAUSE")))
                    .block(Duration.ofSeconds(5)).isError());
            assertTrue(handler.handle(call("runtime_control", Map.of(
                    "sessionId", "mcp-control", "action", "STATUS",
                    "controlRequestId", "not-allowed", "timeoutNanos", 1_000)))
                    .block(Duration.ofSeconds(5)).isError());

            Map<String, Object> pause = Map.of(
                    "sessionId", "mcp-control", "action", "PAUSE",
                    "controlRequestId", "pause-1", "timeoutNanos", 1_000);
            assertFalse(handler.handle(call("runtime_control", pause))
                    .block(Duration.ofSeconds(5)).isError());
            queue.removeFirst().run();
            McpSchema.CallToolResult paused =
                    handler.handle(call("runtime_control", pause)).block(Duration.ofSeconds(5));
            assertEquals(true, ((Map<?, ?>) structured(paused).get("descriptor")).get("paused"));

            Map<String, Object> wait = Map.of(
                    "sessionId", "mcp-control", "controlRequestId", "wait-1",
                    "conditionId", "ready", "maximumTicks", 2,
                    "deltaNanos", 16_666_667, "evidenceLimit", 8, "timeoutNanos", 1_000);
            handler.handle(call("runtime_wait", wait)).block(Duration.ofSeconds(5));
            queue.removeFirst().run();
            McpSchema.CallToolResult completed =
                    handler.handle(call("runtime_wait", wait)).block(Duration.ofSeconds(5));
            assertFalse(completed.isError());
            assertEquals("CONDITION_SATISFIED",
                    ((Map<?, ?>) structured(completed).get("operation")).get("stopReason"));
            assertEquals(1, ticks[0]);

            McpSchema.CallToolResult rejected = handler.handle(call("runtime_wait", Map.of(
                    "sessionId", "mcp-control", "controlRequestId", "wait-2",
                    "conditionId", "ready", "maximumTicks", 2,
                    "deltaNanos", 1, "evidenceLimit", 8, "timeoutNanos", 1_000,
                    "script", "run()"))).block(Duration.ofSeconds(5));
            assertTrue(rejected.isError());
        }
    }

    @Test
    void registeredInputToolsUseClosedSchemaAndReturnTickFrameEvidence() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        String[] key = {""};
        AgentRuntime runtime = AgentRuntime.builder().sessionId(SessionId.of("mcp-input"))
                .clock(() -> 1).commandDispatcher(queue::addLast).build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).tick(deltaNanos -> {}).build());
        runtime.inputs().register(InputSpec.builder("key-down")
                .requiredString("key")
                .handler(parameters -> key[0] = parameters.requiredString("key"))
                .build());
        runtime.inputs().register(InputSpec.builder("pointer-click")
                .requiredInteger("button")
                .handler(parameters -> {})
                .build());
        runtime.start();
        runtime.controls().control(true, "pause-input", Duration.ofSeconds(1));
        queue.removeFirst().run();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService protocol = new RuntimeProtocolService(registry);
            RuntimeToolCatalog catalog = new RuntimeToolCatalog(
                    protocol.toolNames(), protocol.actionCatalog(), protocol.inputCatalog());
            Map<?, ?> inputSchema = catalog.tool("runtime_input").inputSchema();
            List<?> branches = (List<?>) inputSchema.get("oneOf");
            assertEquals(2, branches.size());
            Map<?, ?> keyBranch = (Map<?, ?>) branches.getFirst();
            assertFalse((Boolean) keyBranch.get("additionalProperties"));
            Map<?, ?> keyProperties = (Map<?, ?>) keyBranch.get("properties");
            assertEquals("key-down", ((Map<?, ?>) keyProperties.get("input")).get("const"));
            assertFalse((Boolean) ((Map<?, ?>) keyProperties.get("parameters"))
                    .get("additionalProperties"));
            Map<?, ?> pointerBranch = (Map<?, ?>) branches.get(1);
            Map<?, ?> pointerProperties = (Map<?, ?>) pointerBranch.get("properties");
            assertEquals("pointer-click",
                    ((Map<?, ?>) pointerProperties.get("input")).get("const"));

            Map<String, Object> input = Map.of(
                    "sessionId", "mcp-input", "input", "key-down",
                    "inputRequestId", "key-1", "parameters", Map.of("key", "SPACE"),
                    "timeoutNanos", 1_000);
            handler.handle(call("runtime_input", input)).block(Duration.ofSeconds(5));
            queue.removeFirst().run();
            Map<String, Object> advance = Map.of(
                    "sessionId", "mcp-input", "controlRequestId", "input-tick",
                    "ticks", 1, "deltaNanos", 16_666_667, "timeoutNanos", 1_000);
            handler.handle(call("runtime_advance", advance)).block(Duration.ofSeconds(5));
            queue.removeFirst().run();
            McpSchema.CallToolResult completed =
                    handler.handle(call("runtime_input", input)).block(Duration.ofSeconds(5));
            assertFalse(completed.isError());
            Map<?, ?> injection = (Map<?, ?>) structured(completed).get("injection");
            assertEquals("EXECUTED", injection.get("state"));
            assertEquals(1L, injection.get("actualTick"));
            assertEquals("SPACE", key[0]);

            assertTrue(handler.handle(call("runtime_input", Map.of(
                    "sessionId", "mcp-input", "input", "key-down",
                    "inputRequestId", "key-2",
                    "parameters", Map.of("key", "A", "script", "run()"),
                    "timeoutNanos", 1_000))).block(Duration.ofSeconds(5)).isError());
            assertTrue(handler.handle(call("runtime_input", Map.of(
                    "sessionId", "mcp-input", "input", "key-down",
                    "inputRequestId", "key-3",
                    "parameters", Map.of("button", 1),
                    "timeoutNanos", 1_000))).block(Duration.ofSeconds(5)).isError());
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
                    .sourceSubsystem("combat")
                    .sourceLocation("DamageSystem.java:84")
                    .correlationId("attack-172")
                    .attribute("amount", RuntimeValues.integer(25)));
            FactMetadata metadata = FactMetadata.empty().withSourceSubsystem("combat")
                    .withCorrelationId("attack-172");
            try (DecisionScope decision = runtime.beginDecision(
                    DecisionType.of("target-selection"), EntityId.of("tower-1"), metadata)) {
                decision.reject(EntityId.of("enemy-2"), Reason.of("out-of-range"));
                decision.accept(EntityId.of("enemy-1"));
                decision.choose(EntityId.of("enemy-1"), Reason.of("nearest-in-range"));
            }
            runtime.causeNextChange(EntityId.of("enemy-1"), "health",
                    ChangeCause.semantic("damage").withMetadata(metadata));
            health[0] = 75;
        });
        return new Fixture(runtime, new RuntimeRegistry());
    }

    private record Fixture(AgentRuntime runtime, RuntimeRegistry registry) {}
}
