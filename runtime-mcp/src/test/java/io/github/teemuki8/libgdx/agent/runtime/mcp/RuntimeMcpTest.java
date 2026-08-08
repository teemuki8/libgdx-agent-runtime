package io.github.teemuki8.libgdx.agent.runtime.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.ActionSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.BaselineKind;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointHandle;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointProvider;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionScope;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionType;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.EventSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.FactMetadata;
import io.github.teemuki8.libgdx.agent.runtime.core.InputSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.ChangeCause;
import io.github.teemuki8.libgdx.agent.runtime.core.Reason;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeConfiguration;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeLimits;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationControllerSpec;
import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolJson;
import io.github.teemuki8.libgdx.agent.runtime.protocol.PublishedRuntime;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
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
    void entityHistoryToolUsesClosedVersionPaginationSchema() {
        RuntimeToolCatalog serverCatalog = new RuntimeToolCatalog(
                new RuntimeProtocolService(new RuntimeRegistry()).toolNames());
        McpSchema.Tool history = serverCatalog.tool("runtime_entity_history");
        assertEquals(false, history.inputSchema().get("additionalProperties"));
        Map<?, ?> properties = (Map<?, ?>) history.inputSchema().get("properties");
        assertTrue(properties.containsKey("entityId"));
        assertTrue(properties.containsKey("versionOffset"));
        assertTrue(properties.containsKey("versionLimit"));
        assertTrue(properties.containsKey("fromFrame"));
        assertTrue(properties.containsKey("toFrame"));
        // The frozen V1 base catalog keeps exactly eight tools.
        assertEquals(List.of(
                "runtime_sessions", "runtime_capabilities", "runtime_frames", "runtime_snapshot",
                "runtime_entity", "runtime_changes", "runtime_events", "runtime_decisions"),
                new RuntimeToolCatalog().tools().stream().map(McpSchema.Tool::name).toList());
    }

    @Test
    void entityHistoryToolPagesRemovedEntityAndRejectsUnknownOrInvalidArguments() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("mcp-removed")).build();
        boolean[] include = {true};
        long[] health = {100};
        runtime.entities().registerSource("enemies", () -> include[0]
                ? java.util.stream.Stream.of(io.github.teemuki8.libgdx.agent.runtime.core
                        .InspectableEntity.of(EntityId.of("enemy-1"), EntityType.of("enemy"),
                                () -> "Enemy",
                                inspector -> inspector.property("health", () -> health[0])))
                : java.util.stream.Stream.empty());
        runtime.start();
        for (int frame = 1; frame <= 5; frame++) {
            int value = frame;
            runtime.frame(1, () -> health[0] = 100 - value * 5);
        }
        include[0] = false;
        runtime.frame(1, () -> {});
        runtime.frame(1, () -> {});
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            McpSchema.CallToolResult first = handler.handle(call(
                    "runtime_entity_history", Map.of(
                            "sessionId", "mcp-removed", "entityId", "enemy-1",
                            "fromFrame", 1, "toFrame", 7, "versionOffset", 0,
                            "versionLimit", 2))).block(Duration.ofSeconds(5));
            assertNotNull(first);
            assertFalse(first.isError());
            Map<?, ?> page = assertInstanceOf(Map.class,
                    structured(first).get("page"));
            assertEquals(List.of(1L, 2L), ((List<?>) page.get("versions")).stream()
                    .map(value -> ((Map<?, ?>) value).get("frameId"))
                    .map(value -> (long) ((Map<?, ?>) value).get("value")).toList());
            assertEquals(2L, ((Number) page.get("nextVersionOffset")).longValue());
            assertEquals(true, page.get("hasMoreVersions"));
            assertTrue(page.containsKey("finalRetainedState"));

            McpSchema.CallToolResult second = handler.handle(call(
                    "runtime_entity_history", Map.of(
                            "sessionId", "mcp-removed", "entityId", "enemy-1",
                            "fromFrame", 1, "toFrame", 7,
                            "versionOffset", 4, "versionLimit", 2)))
                    .block(Duration.ofSeconds(5));
            assertFalse(second.isError());
            Map<?, ?> secondPage = assertInstanceOf(Map.class,
                    structured(second).get("page"));
            assertEquals(1, ((List<?>) secondPage.get("versions")).size());
            assertEquals(false, secondPage.get("hasMoreVersions"));

            McpSchema.CallToolResult unknown = handler.handle(call(
                    "runtime_entity_history", Map.of(
                            "sessionId", "mcp-removed", "entityId", "enemy-1",
                            "versionOffset", 0, "versionLimit", 2, "script", "run()")))
                    .block(Duration.ofSeconds(5));
            assertTrue(unknown.isError());
            assertEquals("INVALID_QUERY", structured(unknown).get("code"));

            McpSchema.CallToolResult invalid = handler.handle(call(
                    "runtime_entity_history", Map.of(
                            "sessionId", "mcp-removed", "entityId", "enemy-1",
                            "versionOffset", -1, "versionLimit", 2)))
                    .block(Duration.ofSeconds(5));
            assertTrue(invalid.isError());
            assertEquals("INVALID_QUERY", structured(invalid).get("code"));
        }
    }

    @Test
    void entityHistoryToolReportsNotRetainedAfterFullEviction() {
        io.github.teemuki8.libgdx.agent.runtime.core.RuntimeLimits limits =
                new io.github.teemuki8.libgdx.agent.runtime.core.RuntimeLimits(
                        1, 2_000, 5_000, 128, 256, 256, 64, 4_096, 256, 16, 1_000);
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("mcp-evicted"))
                .configuration(new io.github.teemuki8.libgdx.agent.runtime.core
                        .RuntimeConfiguration(true, limits))
                .build();
        boolean[] include = {true};
        runtime.entities().registerSource("enemies", () -> include[0]
                ? java.util.stream.Stream.of(io.github.teemuki8.libgdx.agent.runtime.core
                        .InspectableEntity.of(EntityId.of("enemy-1"), EntityType.of("enemy"),
                                () -> "Enemy", inspector -> inspector.property("index", () -> 1L)))
                : java.util.stream.Stream.empty());
        runtime.start();
        runtime.frame(1, () -> {});
        runtime.frame(1, () -> {});
        include[0] = false;
        runtime.frame(1, () -> {});
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            McpSchema.CallToolResult evicted = handler.handle(call(
                    "runtime_entity_history", Map.of(
                            "sessionId", "mcp-evicted", "entityId", "enemy-1")))
                    .block(Duration.ofSeconds(5));
            assertTrue(evicted.isError());
            assertEquals("ENTITY_HISTORY_NOT_RETAINED", structured(evicted).get("code"));
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
            assertEquals(19, catalog.tools().size());
            assertEquals(false,
                    catalog.tool("runtime_command_cancel").inputSchema()
                            .get("additionalProperties"));
            assertEquals(false,
                    catalog.tool("runtime_assert").inputSchema().get("additionalProperties"));
            assertTrue(catalog.toolNames().containsAll(List.of(
                    "runtime_recording_start", "runtime_recording_stop",
                    "runtime_recording_get")));

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
    void checkpointToolsKeepOpaqueHandlesInternalAndReturnRestoreBaseline() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        int[] state = {3};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("mcp-checkpoint"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override public CheckpointHandle create() {
                return new McpCheckpoint(state[0]);
            }
            @Override public void restore(CheckpointHandle handle) {
                state[0] = ((McpCheckpoint) handle).value();
            }
            @Override public void dispose(CheckpointHandle handle) {}
        });
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            Map<String, Object> create = Map.of(
                    "sessionId", "mcp-checkpoint", "checkpointId", "save-1",
                    "description", "Before change", "checkpointRequestId", "create-1",
                    "timeoutNanos", 1_000);
            handler.handle(call("runtime_checkpoint_create", create)).block(Duration.ofSeconds(5));
            queue.removeFirst().run();
            McpSchema.CallToolResult created = handler.handle(
                    call("runtime_checkpoint_create", create)).block(Duration.ofSeconds(5));
            assertFalse(created.isError());
            assertFalse(created.structuredContent().toString().contains("McpCheckpoint"));

            state[0] = 8;
            runtime.frame(1, () -> {});
            Map<String, Object> restore = Map.of(
                    "sessionId", "mcp-checkpoint", "checkpointId", "save-1",
                    "checkpointRequestId", "restore-1", "timeoutNanos", 1_000);
            handler.handle(call("runtime_checkpoint_restore", restore))
                    .block(Duration.ofSeconds(5));
            queue.removeFirst().run();
            McpSchema.CallToolResult restored = handler.handle(
                    call("runtime_checkpoint_restore", restore)).block(Duration.ofSeconds(5));
            assertFalse(restored.isError());
            assertEquals(3, state[0]);
            Map<?, ?> operation = (Map<?, ?>) structured(restored).get("operation");
            assertEquals(2L, ((Map<?, ?>) operation.get("baselineFrameId")).get("value"));

            assertTrue(handler.handle(call("runtime_checkpoint_create", Map.of(
                    "sessionId", "mcp-checkpoint", "checkpointId", "save-2",
                    "checkpointRequestId", "create-2", "timeoutNanos", 1_000,
                    "payload", Map.of("state", 3)))).block(Duration.ofSeconds(5)).isError());
        }
    }


    @Test
    void uiCorrelationToolsUseDirectionalClosedSchemasAndReturnExplicitEvidence() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("mcp-ui")).build();
        runtime.uiCorrelations().register(
                new io.github.teemuki8.libgdx.agent.runtime.core.UiBinding(
                        "health-binding", EntityId.of("enemy-1"), Optional.of("health"),
                        "battle-ui", "health-bar",
                        new io.github.teemuki8.libgdx.agent.runtime.core.UiBindingValidity(
                                Optional.empty(), Optional.empty(), Optional.empty())));
        runtime.start();
        runtime.uiCorrelations().recordFrame(
                new io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelation(
                        runtime.currentEpoch(), new io.github.teemuki8.libgdx.agent.runtime.core.FrameId(0),
                        "battle-ui", Optional.of("ui-frame-4"), Optional.of("render-token-4")));
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            McpSchema.CallToolResult bindings = handler.handle(call("runtime_ui_bindings", Map.of(
                    "sessionId", "mcp-ui", "entityId", "enemy-1", "property", "health",
                    "executionEpochId", 0, "runtimeFrameId", 0, "limit", 8)))
                    .block(Duration.ofSeconds(5));
            assertFalse(bindings.isError());
            assertEquals("MATCHED",
                    ((Map<?, ?>) structured(bindings).get("result")).get("status"));

            McpSchema.CallToolResult frames = handler.handle(call("runtime_ui_frames", Map.of(
                    "sessionId", "mcp-ui", "correlationToken", "render-token-4", "limit", 8)))
                    .block(Duration.ofSeconds(5));
            assertFalse(frames.isError());
            Map<?, ?> page = (Map<?, ?>) structured(frames).get("page");
            assertEquals(1, ((List<?>) page.get("items")).size());

            assertTrue(handler.handle(call("runtime_ui_bindings", Map.of(
                    "sessionId", "mcp-ui", "entityId", "enemy-1",
                    "uiSessionId", "battle-ui", "uiControlId", "health-bar",
                    "executionEpochId", 0, "runtimeFrameId", 0, "limit", 8)))
                    .block(Duration.ofSeconds(5)).isError());
            assertTrue(handler.handle(call("runtime_ui_frames", Map.of(
                    "sessionId", "mcp-ui", "correlationToken", "render-token-4",
                    "uiSessionId", "battle-ui", "limit", 8, "script", "run()")))
                    .block(Duration.ofSeconds(5)).isError());
        }
    }

    @Test
    void recordingToolsUseClosedSchemasAndReturnBoundedManifestChunks() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("mcp-recording"))
                .commandDispatcher(queue::addLast)
                .build();
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            Map<String, Object> start = Map.of(
                    "sessionId", "mcp-recording",
                    "recordingId", "mcp-run",
                    "recordingRequestId", "mcp-start",
                    "randomSeed", 17,
                    "configuration", List.of(Map.of("name", "mode", "value", "test")),
                    "replayGuaranteed", false,
                    "timeoutNanos", 1_000_000_000);
            assertFalse(handler.handle(call("runtime_recording_start", start))
                    .block(Duration.ofSeconds(5)).isError());
            queue.removeFirst().run();
            assertFalse(handler.handle(call("runtime_recording_start", start))
                    .block(Duration.ofSeconds(5)).isError());
            runtime.frame(1, () -> {});

            Map<String, Object> stop = Map.of(
                    "sessionId", "mcp-recording",
                    "recordingId", "mcp-run",
                    "recordingRequestId", "mcp-stop",
                    "timeoutNanos", 1_000_000_000);
            handler.handle(call("runtime_recording_stop", stop))
                    .block(Duration.ofSeconds(5));
            queue.removeFirst().run();
            McpSchema.CallToolResult chunk = handler.handle(call("runtime_recording_get", Map.of(
                    "sessionId", "mcp-recording", "recordingId", "mcp-run",
                    "offset", 0, "limit", 8))).block(Duration.ofSeconds(5));
            assertFalse(chunk.isError());
            Map<String, Object> body = structured(chunk);
            Map<?, ?> metadata =
                    (Map<?, ?>) ((Map<?, ?>) body.get("chunk")).get("metadata");
            assertEquals("mcp-run", metadata.get("recordingId"));
            assertEquals(17, ((Number) metadata.get("randomSeed")).intValue());

            assertTrue(handler.handle(call("runtime_recording_get", Map.of(
                    "sessionId", "mcp-recording", "recordingId", "mcp-run",
                    "offset", 0, "limit", 8, "script", "run()")))
                    .block(Duration.ofSeconds(5)).isError());
        }
    }

    @Test
    void determinismToolUsesClosedProfileAndReturnsFirstClassEvidence() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] value = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("mcp-determinism"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.entities().register(
                io.github.teemuki8.libgdx.agent.runtime.core.EntityId.of("counter"),
                io.github.teemuki8.libgdx.agent.runtime.core.EntityType.of("state"),
                () -> "Counter", inspector -> inspector.property("value", () -> value[0]));
        runtime.controls().register(
                io.github.teemuki8.libgdx.agent.runtime.core.SimulationControllerSpec.builder()
                        .pause(() -> {}).resume(() -> {}).tick(delta -> value[0]++).build());
        runtime.scenarios().register("seeded",
                context -> value[0] = context.randomSeed().orElseThrow());
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            Map<String, Object> comparisonScope = Map.of(
                    "entityIds", List.of("counter"),
                    "properties", List.of("value"),
                    "excludedProperties", List.of(),
                    "includeEvents", false,
                    "includeDecisions", false);
            Map<String, Object> request = Map.of(
                    "sessionId", "mcp-determinism",
                    "determinismRequestId", "determinism-1",
                    "scenarioId", "seeded",
                    "randomSeed", 7,
                    "configuration", List.of(),
                    "repeatCount", 2,
                    "ticksPerRepeat", 2,
                    "deltaNanos", 1,
                    "profile", Map.of(
                            "comparisonScope", comparisonScope,
                            "includeUiCorrelations", false),
                    "timeoutNanos", 1_000_000_000);
            assertFalse(handler.handle(call("runtime_determinism_check", request))
                    .block(Duration.ofSeconds(5)).isError());
            queue.removeFirst().run();
            McpSchema.CallToolResult completed =
                    handler.handle(call("runtime_determinism_check", request))
                            .block(Duration.ofSeconds(5));
            assertFalse(completed.isError());
            Map<?, ?> operation = (Map<?, ?>) structured(completed).get("operation");
            Map<?, ?> result = (Map<?, ?>) operation.get("result");
            assertEquals("EQUAL", result.get("status"));
            assertTrue(((String) result.get("message")).contains("configured observable state"));

            java.util.LinkedHashMap<String, Object> unknown =
                    new java.util.LinkedHashMap<>(request);
            unknown.put("script", "run()");
            assertTrue(handler.handle(call("runtime_determinism_check", unknown))
                    .block(Duration.ofSeconds(5)).isError());
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

    @Test
    void framerAcceptsExactByteLimitAndRejectsOneByteMore() throws Exception {
        byte[] exact = new byte[ProtocolJson.MAX_REQUEST_BYTES];
        Arrays.fill(exact, (byte) 'a');
        BoundedJsonRpcFramer framer = new BoundedJsonRpcFramer(
                new ByteArrayInputStream(concat(exact, new byte[]{'\n'})));
        assertEquals(ProtocolJson.MAX_REQUEST_BYTES, framer.read().length());
        assertNull(framer.read());

        byte[] oneOver = new byte[ProtocolJson.MAX_REQUEST_BYTES + 1];
        Arrays.fill(oneOver, (byte) 'b');
        framer = new BoundedJsonRpcFramer(
                new ByteArrayInputStream(concat(oneOver, new byte[]{'\n'})));
        assertThrows(BoundedJsonRpcFramer.RejectedLineException.class, framer::read);
        assertNull(framer.read());
    }

    @Test
    void framerDrainsOversizedFrameThroughNewlineWithoutRetention() throws Exception {
        byte[] huge = new byte[4 * 1024 * 1024];
        Arrays.fill(huge, (byte) 'x');
        BoundedJsonRpcFramer framer = new BoundedJsonRpcFramer(new ByteArrayInputStream(
                concat(huge, new byte[]{'\n'}, "hello\n".getBytes(StandardCharsets.UTF_8))));
        assertThrows(BoundedJsonRpcFramer.RejectedLineException.class, framer::read);
        assertEquals("hello", framer.read());
        assertNull(framer.read());
    }

    @Test
    void framerRejectsMalformedUtf8AndContinuesAfterTheLine() throws Exception {
        byte[] input = concat(
                new byte[]{(byte) 0xC3, (byte) 0x28, '\n'},
                "ok\n".getBytes(StandardCharsets.UTF_8));
        BoundedJsonRpcFramer framer = new BoundedJsonRpcFramer(new ByteArrayInputStream(input));
        assertThrows(BoundedJsonRpcFramer.RejectedLineException.class, framer::read);
        assertEquals("ok", framer.read());
        assertNull(framer.read());
    }

    @Test
    void framerStripsCrLfTerminatorForReadLineCompatibility() throws Exception {
        BoundedJsonRpcFramer framer = new BoundedJsonRpcFramer(new ByteArrayInputStream(
                "{\"a\":1}\r\nnext\r\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("{\"a\":1}", framer.read());
        assertEquals("next", framer.read());
        assertNull(framer.read());
    }

    @Test
    void framerTerminatesExceptionallyOnPartialFrameAtEof() {
        BoundedJsonRpcFramer framer = new BoundedJsonRpcFramer(new ByteArrayInputStream(
                "{\"method\"".getBytes(StandardCharsets.UTF_8)));
        assertThrows(BoundedJsonRpcFramer.UnterminatedFrameException.class, framer::read);
    }

    @Test
    void framerTerminatesExceptionallyOnOversizedFrameWithoutNewline() {
        byte[] huge = new byte[ProtocolJson.MAX_REQUEST_BYTES + 1];
        Arrays.fill(huge, (byte) 'x');
        BoundedJsonRpcFramer framer = new BoundedJsonRpcFramer(new ByteArrayInputStream(huge));
        assertThrows(BoundedJsonRpcFramer.UnterminatedFrameException.class, framer::read);
    }

    @Test
    void framerStateAtRetentionCapCannotWrapOrReEnterRetentionAndRecovers() throws Exception {
        // A newline-free frame long enough to overflow an unbounded int counter is
        // equivalent, for framing purposes, to having already retained limit+1 bytes:
        // extra bytes must not wrap, re-enter retention, or change the rejection, and a
        // newline followed by a valid frame still recovers.
        byte[] input = concat("x".repeat(16).getBytes(StandardCharsets.UTF_8),
                "\n".getBytes(StandardCharsets.UTF_8),
                "ok\n".getBytes(StandardCharsets.UTF_8));
        BoundedJsonRpcFramer seeded = new BoundedJsonRpcFramer(
                new ByteArrayInputStream(input),
                BoundedJsonRpcFramer.MAX_FRAME_BYTES + 1, false);
        assertThrows(BoundedJsonRpcFramer.RejectedLineException.class, seeded::read);
        assertEquals("ok", seeded.read());
        assertNull(seeded.read());

        // Seeded directly in drain mode: even with no retained bytes, further content is
        // never retained and recovery still works.
        BoundedJsonRpcFramer draining = new BoundedJsonRpcFramer(
                new ByteArrayInputStream(input), 0, true);
        assertThrows(BoundedJsonRpcFramer.RejectedLineException.class, draining::read);
        assertEquals("ok", draining.read());
        assertNull(draining.read());
    }

    @Test
    void constrainedMapperEnforcesProtocolJsonStreamBounds() throws Exception {
        McpJsonMapper mapper = new ConstrainedMcpJsonMapper();
        mapper.readValue("[".repeat(32) + "]".repeat(32), Object.class);
        assertThrows(IOException.class, () ->
                mapper.readValue("[".repeat(33) + "]".repeat(33), Object.class));
        mapper.readValue("[\"" + "a".repeat(ProtocolJson.MAX_STRING_LENGTH) + "\"]", Object.class);
        assertThrows(IOException.class, () ->
                mapper.readValue("[\"" + "a".repeat(ProtocolJson.MAX_STRING_LENGTH + 1) + "\"]",
                        Object.class));
        mapper.readValue("[" + "9".repeat(128) + "]", Object.class);
        assertThrows(IOException.class, () ->
                mapper.readValue("[" + "9".repeat(129) + "]", Object.class));
        assertNotNull(mapper.readValue("{\"method\":\"initialize\"}", Object.class));
    }

    @Test
    @Timeout(30)
    void stdioRejectsOversizedFrameDrainsAndExecutesLaterRequest() throws Exception {
        byte[] huge = new byte[2 * 1024 * 1024];
        Arrays.fill(huge, (byte) 'x');
        StdioOutcome outcome = runServer(concat(huge, new byte[]{'\n'}, initializeFrame(1)));
        assertNull(outcome.failure());
        List<Map<String, Object>> responses = responses(outcome);
        assertEquals(-32700, errorCode(nullIdError(responses)));
        assertTrue(withId(responses, 1).containsKey("result"));
        assertEquals(List.of(initializeJson(1)), outcome.mapper().deserialized());
    }

    @Test
    @Timeout(30)
    void stdioRejectsMaxPlusOneFrameBeforeDeserialization() throws Exception {
        byte[] oneOver = new byte[ProtocolJson.MAX_REQUEST_BYTES + 1];
        Arrays.fill(oneOver, (byte) 'b');
        StdioOutcome outcome = runServer(concat(oneOver, new byte[]{'\n'}, initializeFrame(2)));
        assertNull(outcome.failure());
        List<Map<String, Object>> responses = responses(outcome);
        assertEquals(-32700, errorCode(nullIdError(responses)));
        assertTrue(withId(responses, 2).containsKey("result"));
        assertEquals(List.of(initializeJson(2)), outcome.mapper().deserialized());
    }

    @Test
    @Timeout(30)
    void stdioRejectsMalformedUtf8AndExecutesLaterRequest() throws Exception {
        byte[] bad = new byte[]{(byte) 0xC3, (byte) 0x28};
        StdioOutcome outcome = runServer(concat(bad, new byte[]{'\n'}, initializeFrame(3)));
        assertNull(outcome.failure());
        List<Map<String, Object>> responses = responses(outcome);
        assertEquals(-32700, errorCode(nullIdError(responses)));
        assertTrue(withId(responses, 3).containsKey("result"));
        assertEquals(List.of(initializeJson(3)), outcome.mapper().deserialized());
    }

    @Test
    @Timeout(30)
    void stdioRejectsDepthAndStringLimitFramesButLaterRequestWorks() throws Exception {
        byte[] depthFrame = ("[".repeat(33) + "]".repeat(33) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] stringFrame = ("[\"" + "a".repeat(ProtocolJson.MAX_STRING_LENGTH + 1) + "\"]\n")
                .getBytes(StandardCharsets.UTF_8);
        StdioOutcome outcome = runServer(concat(depthFrame, stringFrame, initializeFrame(4)));
        assertNull(outcome.failure());
        List<Map<String, Object>> responses = responses(outcome);
        assertEquals(2, responses.stream().filter(r -> r.get("id") == null).count());
        assertEquals(-32700, errorCode(nullIdError(responses)));
        assertTrue(withId(responses, 4).containsKey("result"));
        assertEquals(List.of(depthFrameContent(), stringFrameContent(), initializeJson(4)),
                outcome.mapper().deserialized());
    }

    @Test
    @Timeout(30)
    void stdioAcceptsExactlyMaxBytesAndInitializes() throws Exception {
        String init = initializeJson(5);
        int pad = ProtocolJson.MAX_REQUEST_BYTES - init.length();
        assertTrue(pad > 0);
        String padded = init.substring(0, init.length() - 1) + " ".repeat(pad) + "}";
        assertEquals(ProtocolJson.MAX_REQUEST_BYTES, padded.length());
        StdioOutcome outcome = runServer((padded + "\n").getBytes(StandardCharsets.UTF_8));
        assertNull(outcome.failure());
        List<Map<String, Object>> responses = responses(outcome);
        assertTrue(withId(responses, 5).containsKey("result"));
        assertEquals(List.of(padded), outcome.mapper().deserialized());
    }

    @Test
    @Timeout(30)
    void stdioOversizedFrameWithoutNewlineTerminatesExceptionallyUnparsed() {
        byte[] huge = new byte[ProtocolJson.MAX_REQUEST_BYTES + 1];
        Arrays.fill(huge, (byte) 'x');
        StdioOutcome outcome = runServer(huge);
        assertInstanceOf(BoundedJsonRpcFramer.UnterminatedFrameException.class, outcome.failure());
        assertTrue(outcome.mapper().deserialized().isEmpty());
    }

    @Test
    @Timeout(30)
    void stdioPartialFrameAtEofTerminatesExceptionallyUnparsed() {
        StdioOutcome outcome = runServer(
                "{\"method\":\"initialize\"".getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(BoundedJsonRpcFramer.UnterminatedFrameException.class, outcome.failure());
        assertTrue(outcome.mapper().deserialized().isEmpty());
    }

    @Test
    void boundedSinkAcceptsExactLimitAndRejectsOneMoreByteWithoutPartialRetention()
            throws Exception {
        BoundedOutputStream sink = new BoundedOutputStream(10);
        sink.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        assertEquals(10, sink.count());
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, sink.toByteArray());
        assertThrows(BoundedOutputStream.OverflowException.class, () -> sink.write(11));
        assertEquals(10, sink.count());
        assertEquals(10, sink.toByteArray().length);
        // An overflowing range is rejected whole: no partial prefix is retained.
        assertThrows(BoundedOutputStream.OverflowException.class,
                () -> sink.write(new byte[]{1, 2, 3}, 0, 3));
        assertEquals(10, sink.count());
        assertEquals(10, sink.toByteArray().length);
    }

    @Test
    void constrainedMapperStreamsExactlyToTheResponseCapAndStopsAtOneMore() throws Exception {
        ConstrainedMcpJsonMapper mapper = new ConstrainedMcpJsonMapper();
        BoundedOutputStream probe = new BoundedOutputStream(ProtocolJson.MAX_RESPONSE_BYTES);
        mapper.writeValue(probe, McpSchema.JSONRPCResponse.result(1, "a".repeat(8_000_000)));
        int overhead = (int) probe.count() - 8_000_000;
        int exactLength = ProtocolJson.MAX_RESPONSE_BYTES - overhead;
        BoundedOutputStream exact = new BoundedOutputStream(ProtocolJson.MAX_RESPONSE_BYTES);
        mapper.writeValue(exact, McpSchema.JSONRPCResponse.result(1, "a".repeat(exactLength)));
        assertEquals(ProtocolJson.MAX_RESPONSE_BYTES, exact.count());
        assertEquals(ProtocolJson.MAX_RESPONSE_BYTES, exact.toByteArray().length);

        BoundedOutputStream oneOver = new BoundedOutputStream(ProtocolJson.MAX_RESPONSE_BYTES);
        // Jackson 3 reports the sink's checked rejection as an unchecked JacksonException
        // with reference-chain context; the sink's own flag is the overflow evidence.
        RuntimeException failure = assertThrows(RuntimeException.class, () -> mapper.writeValue(
                oneOver, McpSchema.JSONRPCResponse.result(1, "a".repeat(exactLength + 1))));
        assertInstanceOf(tools.jackson.core.JacksonException.class, failure);
        assertTrue(oneOver.overflowed());
        assertTrue(oneOver.count() <= ProtocolJson.MAX_RESPONSE_BYTES);
    }

    @Test
    @Timeout(30)
    void stdioResponseBytesCheckedAreExactlyTheBytesSent() throws Exception {
        StdioOutcome outcome = runServer(initializeFrame(9));
        assertNull(outcome.failure());
        List<byte[]> serialized = outcome.mapper().serialized();
        assertEquals(1, serialized.size());
        byte[] outputBytes = outcome.output().toByteArray();
        assertTrue(outputBytes.length > 0);
        assertEquals('\n', outputBytes[outputBytes.length - 1]);
        byte[] line = Arrays.copyOf(outputBytes, outputBytes.length - 1);
        assertArrayEquals(serialized.get(0), line);
        Map<String, Object> response =
                JSON.readValue(line, new TypeReference<Map<String, Object>>() {});
        assertEquals(9, response.get("id"));
        assertTrue(response.containsKey("result"));
    }

    @Test
    @Timeout(30)
    void stdioOversizedToolResultReturnsBoundedErrorAndLaterRequestsStillWork() throws Exception {
        // The tiny cap admits the initialize and session-list responses but not the
        // entity result, proving exact-cap rejection and bounded error recovery.
        Fixture fixture = blobRuntime("mcp-fixture", "a".repeat(20_000));
        try (PublishedRuntime publication = fixture.registry().publish(fixture.runtime())) {
            RuntimeProtocolService service = new RuntimeProtocolService(fixture.registry());
            byte[] input = concat(
                    initializeFrame(13),
                    initializedNotification(),
                    (entityToolCall(14, "mcp-fixture") + "\n").getBytes(StandardCharsets.UTF_8),
                    (sessionsToolCall(15) + "\n").getBytes(StandardCharsets.UTF_8));
            StdioOutcome outcome = runServer(service, input, 2_048);
            assertNull(outcome.failure());
            List<Map<String, Object>> responses = responses(outcome);
            assertEquals(3, responses.size());
            assertTrue(withId(responses, 13).containsKey("result"));
            Map<String, Object> oversized = withId(responses, 14);
            assertEquals(-32001, errorCode(oversized));
            assertTrue(withId(responses, 15).containsKey("result"));
            // The oversized result never leaks a partial frame or its content.
            assertFalse(outcome.output().toString(StandardCharsets.UTF_8)
                    .contains("a".repeat(4_096)));
            // The checked payload for the oversized message was rejected whole, never retained.
            assertEquals(0, outcome.mapper().serialized().get(1).length);
            assertEquals(3, outcome.mapper().serialized().size());
        }
    }

    @Test
    @Timeout(60)
    void stdioOversizedAggregateToolResultCannotRetainUnboundedMessageBytes() throws Exception {
        String payload = "a".repeat(10_000_000);
        Fixture fixture = blobRuntime("mcp-fixture", payload);
        try (PublishedRuntime publication = fixture.registry().publish(fixture.runtime())) {
            RuntimeProtocolService service = new RuntimeProtocolService(fixture.registry());
            byte[] input = concat(
                    initializeFrame(16),
                    initializedNotification(),
                    (entityToolCall(17, "mcp-fixture") + "\n").getBytes(StandardCharsets.UTF_8));
            StdioOutcome outcome = runServer(service, input);
            assertNull(outcome.failure());
            List<Map<String, Object>> responses = responses(outcome);
            assertEquals(2, responses.size());
            assertTrue(withId(responses, 16).containsKey("result"));
            Map<String, Object> oversized = withId(responses, 17);
            assertEquals(17, oversized.get("id"));
            assertEquals(-32001, errorCode(oversized));
            // One bounded typed error line only: no partial oversized prefix, no 10 MiB emission.
            assertTrue(outcome.output().size() < 1_000_000,
                    "the oversized response must not be written to stdio");
            assertFalse(outcome.output().toString(StandardCharsets.UTF_8)
                    .contains("a".repeat(4_096)));
            // The transport's bounded check rejected the 10 MiB message whole: the serialized
            // probe holds an empty marker, never the oversized array.
            assertEquals(2, outcome.mapper().serialized().size());
            assertEquals(0, outcome.mapper().serialized().get(1).length);
        }
    }

    private static byte[] initializedNotification() {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String entityToolCall(int id, String sessionId) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"runtime_entity\",\"arguments\":{\"sessionId\":\"" + sessionId + "\","
                + "\"entityId\":\"enemy-1\"}}}";
    }

    private static String sessionsToolCall(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"runtime_sessions\",\"arguments\":{}}}";
    }

    private static Fixture blobRuntime(String sessionId, String payload) {
        RuntimeLimits limits = new RuntimeLimits(240, 2_000, 5_000, 128, 256, 256, 64,
                Math.max(payload.length() + 1_024, 2_048), 256, 16, 1_000);
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of(sessionId))
                .configuration(new RuntimeConfiguration(true, limits))
                .build();
        runtime.entities().register(EntityId.of("enemy-1"), EntityType.of("enemy"),
                () -> "Enemy", inspector -> inspector.property("blob",
                        () -> RuntimeValues.string(payload)));
        runtime.start();
        runtime.frame(1, () -> {});
        return new Fixture(runtime, new RuntimeRegistry());
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] combined = new byte[total];
        int position = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, combined, position, part.length);
            position += part.length;
        }
        return combined;
    }

    private static String initializeJson(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}";
    }

    private static byte[] initializeFrame(int id) {
        return (initializeJson(id) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String depthFrameContent() {
        return "[".repeat(33) + "]".repeat(33);
    }

    private static String stringFrameContent() {
        return "[\"" + "a".repeat(ProtocolJson.MAX_STRING_LENGTH + 1) + "\"]";
    }

    private static StdioOutcome runServer(byte[] input) {
        return runServer(new RuntimeProtocolService(new RuntimeRegistry()), input,
                ProtocolJson.MAX_RESPONSE_BYTES);
    }

    private static StdioOutcome runServer(RuntimeProtocolService service, byte[] input) {
        return runServer(service, input, ProtocolJson.MAX_RESPONSE_BYTES);
    }

    private static StdioOutcome runServer(
            RuntimeProtocolService service, byte[] input, int maxResponseBytes) {
        RecordingMapper mapper = new RecordingMapper(maxResponseBytes);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RuntimeMcpServer server = new RuntimeMcpServer(
                service, new ByteArrayInputStream(input), output, mapper, maxResponseBytes);
        Throwable failure = null;
        try {
            server.awaitTermination();
        } catch (CompletionException terminationFailure) {
            failure = terminationFailure.getCause();
        }
        return new StdioOutcome(output, mapper, failure);
    }

    private static List<Map<String, Object>> responses(StdioOutcome outcome) throws IOException {
        List<Map<String, Object>> parsed = new ArrayList<>();
        for (String line : outcome.output().toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isEmpty()) {
                parsed.add(JSON.readValue(line, new TypeReference<Map<String, Object>>() {}));
            }
        }
        return parsed;
    }

    private static Map<String, Object> withId(List<Map<String, Object>> responses, Object id) {
        return responses.stream().filter(r -> Objects.equals(r.get("id"), id)).findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> nullIdError(List<Map<String, Object>> responses) {
        return responses.stream().filter(r -> r.get("id") == null).findFirst().orElseThrow();
    }

    private static int errorCode(Map<String, Object> errorResponse) {
        return (Integer) ((Map<?, ?>) errorResponse.get("error")).get("code");
    }

    private record StdioOutcome(
            ByteArrayOutputStream output, RecordingMapper mapper, Throwable failure) {}

    private static final class RecordingMapper extends ConstrainedMcpJsonMapper {
        private final int maxResponseBytes;
        private final List<String> deserialized = new ArrayList<>();
        private final List<byte[]> serialized = new ArrayList<>();

        RecordingMapper(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }

        List<String> deserialized() {
            return deserialized;
        }

        /**
         * One bounded payload per outbound {@link McpSchema.JSONRPCMessage}: the exact bytes
         * the transport checked against the response cap, or an empty marker when the bounded
         * check overflowed (no oversized message array is ever retained).
         */
        List<byte[]> serialized() {
            return serialized;
        }

        @Override public <T> T readValue(String content, Class<T> type) throws IOException {
            deserialized.add(content);
            return super.readValue(content, type);
        }

        @Override public <T> T readValue(String content, TypeRef<T> type) throws IOException {
            deserialized.add(content);
            return super.readValue(content, type);
        }

        @Override public void writeValue(OutputStream out, Object value) throws IOException {
            BoundedOutputStream probe = new BoundedOutputStream(maxResponseBytes);
            try {
                super.writeValue(probe, value);
                serialized.add(probe.toByteArray());
            } catch (RuntimeException | IOException failure) {
                if (!probe.overflowed()) {
                    throw failure;
                }
                serialized.add(new byte[0]);
            }
            super.writeValue(out, value);
        }
    }

    private static McpSchema.CallToolRequest call(
            String name, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
    }

    private static Map<String, Object> structured(McpSchema.CallToolResult result) {
        return JSON.convertValue(
                result.structuredContent(), new TypeReference<Map<String, Object>>() {});
    }

    private record McpCheckpoint(int value) implements CheckpointHandle {}

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
