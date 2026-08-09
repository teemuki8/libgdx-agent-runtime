package io.github.teemuki8.libgdx.agent.runtime.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.InputSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationControllerSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTimelineSpec;
import io.github.teemuki8.libgdx.agent.runtime.protocol.PublishedRuntime;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SimulationDeterminismMcpTest {
    @Test
    void exposesClosedNaturalTickAwareDeterminismTool() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("simulation-determinism-mcp"))
                .clock(() -> 1).commandDispatcher(queue::addLast).build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(16));
        runtime.entities().register(EntityId.of("world"), EntityType.of("physics"),
                () -> "world", inspector -> inspector.property("step", () -> 16L));
        runtime.entities().register(EntityId.of("body"), EntityType.of("physics"),
                () -> "body", inspector -> inspector.property("position", () -> position[0]));
        runtime.entities().register(EntityId.of("contacts"), EntityType.of("physics"),
                () -> "contacts", inspector -> inspector.property("complete", () -> true));
        runtime.inputs().register(InputSpec.builder("move").requiredInteger("amount")
                .handler(parameters -> position[0] += parameters.requiredInteger("amount"))
                .build());
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).acknowledgedTick(delta -> delta).build());
        runtime.scenarios().register("move", context -> position[0] = 0);
        runtime.start();

        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeToolCatalog catalog = new RuntimeToolCatalog(
                    new RuntimeProtocolService(registry).toolNames(), List.of(),
                    runtime.inputs().list());
            McpSchema.Tool tool = catalog.tool("runtime_simulation_determinism_check");
            assertEquals(false, tool.inputSchema().get("additionalProperties"));
            Map<?, ?> properties = (Map<?, ?>) tool.inputSchema().get("properties");
            Map<?, ?> inputs = (Map<?, ?>) properties.get("inputs");
            Map<?, ?> inputVariant = (Map<?, ?>) ((List<?>)
                    ((Map<?, ?>) inputs.get("items")).get("oneOf")).getFirst();
            assertEquals(false, inputVariant.get("additionalProperties"));
            Map<?, ?> parameterSchema = (Map<?, ?>) ((Map<?, ?>)
                    inputVariant.get("properties")).get("parameters");
            assertEquals(false, parameterSchema.get("additionalProperties"));

            Map<String, Object> request = request();
            McpSchema.CallToolResult queued = handler.handle(call(
                    "runtime_simulation_determinism_check", request))
                    .block(Duration.ofSeconds(5));
            assertFalse(queued.isError());
            queue.removeFirst().run();
            McpSchema.CallToolResult completed = handler.handle(call(
                    "runtime_simulation_determinism_check", request))
                    .block(Duration.ofSeconds(5));
            assertFalse(completed.isError());
            Map<?, ?> content = (Map<?, ?>) completed.structuredContent();
            assertEquals("simulationDeterminism", content.get("type"));
            Map<?, ?> operation = (Map<?, ?>) content.get("operation");
            assertEquals("EQUAL", ((Map<?, ?>) operation.get("result")).get("status"));

            LinkedHashMap<String, Object> invalid = new LinkedHashMap<>(request);
            invalid.put("inputs", List.of(Map.of(
                    "epochTick", 1, "inputId", "move",
                    "parameters", Map.of("amount", 1, "unknown", true))));
            assertTrue(handler.handle(call("runtime_simulation_determinism_check", invalid))
                    .block(Duration.ofSeconds(5)).isError());
        }
    }

    private static Map<String, Object> request() {
        LinkedHashMap<String, Object> request = new LinkedHashMap<>();
        request.put("sessionId", "simulation-determinism-mcp");
        request.put("determinismRequestId", "mcp-physics");
        request.put("scenarioId", "move");
        request.put("randomSeed", 7);
        request.put("configuration", List.of());
        request.put("repeatCount", 2);
        request.put("ticksPerRepeat", 2);
        request.put("deltaNanos", 16);
        request.put("profile", Map.of(
                "comparisonScope", Map.of(
                        "entityIds", List.of("body"),
                        "properties", List.of("position"),
                        "excludedProperties", List.of(),
                        "includeEvents", false,
                        "includeDecisions", false),
                "includeUiCorrelations", false));
        request.put("inputs", List.of(Map.of(
                "epochTick", 1, "inputId", "move",
                "parameters", Map.of("amount", 1))));
        request.put("configurationRequirements", List.of(Map.of(
                "entityId", "world", "property", "step", "expected", 16)));
        request.put("evidenceRequirements", List.of(Map.of(
                "entityId", "contacts", "property", "complete")));
        request.put("eventTypes", List.of());
        request.put("timeoutNanos", Duration.ofSeconds(1).toNanos());
        return Map.copyOf(request);
    }

    private static McpSchema.CallToolRequest call(
            String name, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
    }
}
