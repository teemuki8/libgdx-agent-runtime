package io.github.teemuki8.libgdx.agent.runtime.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.InputSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.InputTimelineLimits;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationControllerSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTimelineSpec;
import io.github.teemuki8.libgdx.agent.runtime.protocol.PublishedRuntime;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InputTimelineMcpTest {
    @Test
    void exposesClosedInputTimelineToolAndExecutesRegisteredScalarTransitions() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        ArrayList<Object> observed = new ArrayList<>();
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-timeline-mcp"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(16));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(deltaNanos -> deltaNanos)
                .build());
        runtime.inputs().register(InputSpec.builder("boolean-input")
                .requiredBoolean("value")
                .handler(parameters -> observed.add(parameters.requiredBoolean("value")))
                .build());
        runtime.inputs().register(InputSpec.builder("integer-input")
                .requiredInteger("value")
                .handler(parameters -> observed.add(parameters.requiredInteger("value")))
                .build());
        runtime.inputs().register(InputSpec.builder("decimal-input")
                .requiredDecimal("value")
                .handler(parameters -> observed.add(parameters.requiredDecimal("value")))
                .build());
        runtime.inputs().register(InputSpec.builder("string-input")
                .requiredString("value")
                .handler(parameters -> observed.add(parameters.requiredString("value")))
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        queue.removeFirst().run();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            RuntimeProtocolService protocol = new RuntimeProtocolService(registry);
            RuntimeToolCatalog catalog = new RuntimeToolCatalog(
                    protocol.toolNames(), protocol.actionCatalog(), protocol.inputCatalog());
            McpSchema.Tool timelineTool = catalog.tool("runtime_input_timeline");
            assertEquals(false, timelineTool.inputSchema().get("additionalProperties"));
            Map<?, ?> timelineProperties =
                    (Map<?, ?>) timelineTool.inputSchema().get("properties");
            assertEquals(List.of("sessionId", "timelineRequestId", "totalTicks",
                    "transitions", "timeoutNanos"),
                    timelineTool.inputSchema().get("required"));
            Map<?, ?> transitionsSchema = (Map<?, ?>) timelineProperties.get("transitions");
            assertEquals("array", transitionsSchema.get("type"));
            assertEquals(InputTimelineLimits.MAXIMUM_TRANSITIONS,
                    transitionsSchema.get("maxItems"));
            List<?> branches = (List<?>) ((Map<?, ?>) transitionsSchema.get("items"))
                    .get("oneOf");
            assertEquals(4, branches.size());
            List<String> inputIds = branches.stream().map(branch -> {
                Map<?, ?> properties = (Map<?, ?>) ((Map<?, ?>) branch).get("properties");
                Map<?, ?> inputId = (Map<?, ?>) properties.get("inputId");
                return (String) inputId.get("const");
            }).sorted().toList();
            assertEquals(List.of("boolean-input", "decimal-input", "integer-input",
                    "string-input"), inputIds);
            Map<?, ?> firstProperties = (Map<?, ?>) ((Map<?, ?>) branches.getFirst())
                    .get("properties");
            assertClosedObject(firstProperties.get("parameters"));
            assertEquals(false, ((Map<?, ?>) firstProperties.get("parameters"))
                    .get("additionalProperties"));

            Map<String, Object> arguments = Map.of(
                    "sessionId", "input-timeline-mcp",
                    "timelineRequestId", "mcp-timeline-1",
                    "totalTicks", 4,
                    "transitions", List.of(
                            Map.of("transitionId", "bool", "timelineTick", 1,
                                    "inputId", "boolean-input",
                                    "parameters", Map.of("value", true)),
                            Map.of("transitionId", "integer", "timelineTick", 2,
                                    "inputId", "integer-input",
                                    "parameters", Map.of("value", 7)),
                            Map.of("transitionId", "decimal", "timelineTick", 3,
                                    "inputId", "decimal-input",
                                    "parameters", Map.of("value", 0.5)),
                            Map.of("transitionId", "string", "timelineTick", 4,
                                    "inputId", "string-input",
                                    "parameters", Map.of("value", "stop"))),
                    "timeoutNanos", Duration.ofSeconds(2).toNanos());
            McpSchema.CallToolResult queued = handler.handle(
                    call("runtime_input_timeline", arguments)).block(Duration.ofSeconds(2));
            assertFalse(queued.isError(), queued::toString);
            Map<?, ?> queuedContent = (Map<?, ?>) queued.structuredContent();
            assertEquals("inputTimeline", queuedContent.get("type"));
            Map<?, ?> queuedOperation = (Map<?, ?>) queuedContent.get("operation");
            assertEquals("QUEUED", ((Map<?, ?>) ((Map<?, ?>) queuedOperation.get("command"))
                    .get("status")).get("state"));
            queue.removeFirst().run();
            McpSchema.CallToolResult completed = handler.handle(
                    call("runtime_input_timeline", arguments)).block(Duration.ofSeconds(2));
            assertFalse(completed.isError(), completed::toString);
            Map<?, ?> content = (Map<?, ?>) completed.structuredContent();
            assertEquals("inputTimeline", content.get("type"));
            Map<?, ?> result = (Map<?, ?>) ((Map<?, ?>) content.get("operation"))
                    .get("result");
            assertEquals("COMPLETED", result.get("stopReason"));
            Map<?, ?> bounds = (Map<?, ?>) result.get("bounds");
            assertEquals(4, bounds.get("completedTicks"));
            assertEquals(4, bounds.get("executedTransitions"));
            assertEquals(List.of(true, 7L, new BigDecimal("0.5"), "stop"), observed);
        }
    }

    @Test
    void rejectsClosedTimelineFieldsBeforeAnyHandlerRuns() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        int[] handlerCalls = {0};
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-timeline-reject"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(16));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(deltaNanos -> deltaNanos)
                .build());
        runtime.inputs().register(InputSpec.builder("boolean-input")
                .requiredBoolean("value")
                .handler(parameters -> handlerCalls[0]++)
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        queue.removeFirst().run();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            Map<String, Object> base = new LinkedHashMap<>(Map.of(
                    "sessionId", "input-timeline-reject",
                    "timelineRequestId", "reject-timeline",
                    "totalTicks", 1,
                    "transitions", List.of(Map.of(
                            "transitionId", "one", "timelineTick", 1,
                            "inputId", "boolean-input",
                            "parameters", Map.of("value", true))),
                    "timeoutNanos", Duration.ofSeconds(1).toNanos()));

            LinkedHashMap<String, Object> unknownTop = new LinkedHashMap<>(base);
            unknownTop.put("unknown", true);
            assertTrue(handler.handle(call("runtime_input_timeline", unknownTop))
                    .block(Duration.ofSeconds(5)).isError());
            LinkedHashMap<String, Object> unknownTransition = new LinkedHashMap<>(base);
            unknownTransition.put("transitions", List.of(Map.of(
                    "transitionId", "one", "timelineTick", 1,
                    "inputId", "boolean-input",
                    "parameters", Map.of("value", true),
                    "unknownTransitionField", true)));
            assertTrue(handler.handle(call("runtime_input_timeline", unknownTransition))
                    .block(Duration.ofSeconds(5)).isError());
            LinkedHashMap<String, Object> wrongType = new LinkedHashMap<>(base);
            wrongType.put("transitions", List.of(Map.of(
                    "transitionId", "one", "timelineTick", 1,
                    "inputId", "boolean-input",
                    "parameters", Map.of("value", "not-a-boolean"))));
            assertTrue(handler.handle(call("runtime_input_timeline", wrongType))
                    .block(Duration.ofSeconds(5)).isError());
            LinkedHashMap<String, Object> nestedValue = new LinkedHashMap<>(base);
            nestedValue.put("transitions", List.of(Map.of(
                    "transitionId", "one", "timelineTick", 1,
                    "inputId", "boolean-input",
                    "parameters", Map.of("value", List.of(true)))));
            assertTrue(handler.handle(call("runtime_input_timeline", nestedValue))
                    .block(Duration.ofSeconds(5)).isError());
            LinkedHashMap<String, Object> unknownInput = new LinkedHashMap<>(base);
            unknownInput.put("transitions", List.of(Map.of(
                    "transitionId", "one", "timelineTick", 1,
                    "inputId", "missing-input",
                    "parameters", Map.of("value", true))));
            assertTrue(handler.handle(call("runtime_input_timeline", unknownInput))
                    .block(Duration.ofSeconds(5)).isError());
            LinkedHashMap<String, Object> duplicateChild = new LinkedHashMap<>(base);
            duplicateChild.put("transitions", List.of(
                    Map.of("transitionId", "one", "timelineTick", 1,
                            "inputId", "boolean-input",
                            "parameters", Map.of("value", true)),
                    Map.of("transitionId", "one", "timelineTick", 1,
                            "inputId", "boolean-input",
                            "parameters", Map.of("value", false))));
            assertTrue(handler.handle(call("runtime_input_timeline", duplicateChild))
                    .block(Duration.ofSeconds(5)).isError());
            LinkedHashMap<String, Object> outOfOrder = new LinkedHashMap<>(base);
            outOfOrder.put("transitions", List.of(
                    Map.of("transitionId", "two", "timelineTick", 2,
                            "inputId", "boolean-input",
                            "parameters", Map.of("value", true)),
                    Map.of("transitionId", "one", "timelineTick", 1,
                            "inputId", "boolean-input",
                            "parameters", Map.of("value", false))));
            assertTrue(handler.handle(call("runtime_input_timeline", outOfOrder))
                    .block(Duration.ofSeconds(5)).isError());
            assertEquals(0, handlerCalls[0]);
            assertTrue(queue.isEmpty());
        }
    }

    @Test
    void emptyInputCatalogKeepsToolDiscoverableButSchemaUnsatisfiable() {
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-timeline-empty"))
                .build();
        runtime.start();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            RuntimeProtocolService protocol = new RuntimeProtocolService(registry);
            RuntimeToolCatalog catalog = new RuntimeToolCatalog(
                    protocol.toolNames(), protocol.actionCatalog(), protocol.inputCatalog());
            McpSchema.Tool timelineTool = catalog.tool("runtime_input_timeline");
            Map<?, ?> transitionsSchema = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>)
                    timelineTool.inputSchema().get("properties")).get("transitions"));
            assertTrue(transitionsSchema.containsKey("minItems"));
            assertEquals(1, transitionsSchema.get("minItems"));
            assertTrue(transitionsSchema.containsKey("items"));
        }
    }

    private static void assertClosedObject(Object raw) {
        assertEquals(false, ((Map<?, ?>) raw).get("additionalProperties"));
    }

    private static McpSchema.CallToolRequest call(
            String name, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
    }
}
