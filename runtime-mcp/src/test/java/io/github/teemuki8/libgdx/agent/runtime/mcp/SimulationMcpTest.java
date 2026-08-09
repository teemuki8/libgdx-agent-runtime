package io.github.teemuki8.libgdx.agent.runtime.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTimelineSpec;
import io.github.teemuki8.libgdx.agent.runtime.protocol.PublishedRuntime;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SimulationMcpTest {
    @Test
    void exposesClosedSimulationStateAndTickHistoryTools() {
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("simulation-mcp"))
                .clock(() -> 1)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(10));
        runtime.start();
        runtime.simulation().tick(10, supplied -> supplied);

        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeToolCatalog catalog = new RuntimeToolCatalog(
                    new RuntimeProtocolService(registry).toolNames());
            assertTrue(catalog.toolNames().contains("runtime_simulation"));
            assertTrue(catalog.toolNames().contains("runtime_simulation_ticks"));
            assertEquals(false,
                    catalog.tool("runtime_simulation_ticks").inputSchema()
                            .get("additionalProperties"));

            McpSchema.CallToolResult state = handler.handle(call(
                    "runtime_simulation", Map.of("sessionId", "simulation-mcp")))
                    .block(Duration.ofSeconds(5));
            assertFalse(state.isError());
            assertEquals("simulation", ((Map<?, ?>) state.structuredContent()).get("type"));

            McpSchema.CallToolResult ticks = handler.handle(call(
                    "runtime_simulation_ticks", Map.of(
                            "sessionId", "simulation-mcp",
                            "executionEpochId", 0,
                            "fromEpochTick", 1,
                            "toEpochTick", 1,
                            "limit", 10)))
                    .block(Duration.ofSeconds(5));
            assertFalse(ticks.isError());
            assertEquals("simulationTicks", ((Map<?, ?>) ticks.structuredContent()).get("type"));

            McpSchema.CallToolResult unknown = handler.handle(call(
                    "runtime_simulation", Map.of(
                            "sessionId", "simulation-mcp", "unknown", true)))
                    .block(Duration.ofSeconds(5));
            assertTrue(unknown.isError());
        }
    }

    @Test
    void simulationAssertionToolUsesClosedNaturalSchemaAndIncompleteSafeEvaluation() {
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("simulation-assert-mcp"))
                .clock(() -> 1)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(10));
        runtime.entities().register(EntityId.of("ball"), EntityType.of("body"), () -> "ball",
                inspector -> inspector.property("awake", () -> true));
        runtime.start();
        runtime.simulation().tick(10, supplied -> supplied);

        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeToolCatalog catalog = new RuntimeToolCatalog(
                    new RuntimeProtocolService(registry).toolNames());
            McpSchema.Tool tool = catalog.tool("runtime_simulation_assert");
            assertEquals(false, tool.inputSchema().get("additionalProperties"));
            Map<?, ?> assertion = (Map<?, ?>) ((Map<?, ?>) tool.inputSchema().get("properties"))
                    .get("assertion");
            List<?> variants = (List<?>) assertion.get("oneOf");
            assertEquals(11, variants.size());
            assertTrue(variants.stream().allMatch(value ->
                    Boolean.FALSE.equals(((Map<?, ?>) value).get("additionalProperties"))));

            Map<String, Object> base = simulationAssertionRequest(1, Map.of(
                            "assertionType", "propertyEquals",
                            "entityId", "ball",
                            "property", "awake",
                            "expected", true));
            McpSchema.CallToolResult passed = handler.handle(
                    call("runtime_simulation_assert", base)).block(Duration.ofSeconds(5));
            assertFalse(passed.isError());
            Map<?, ?> passedContent = (Map<?, ?>) passed.structuredContent();
            assertEquals("simulationAssertion", passedContent.get("type"));
            assertEquals("PASS", ((Map<?, ?>) passedContent.get("result")).get("status"));

            Map<String, Object> vector = Map.of("x", 0, "y", 0);
            Map<String, Object> area = Map.of(
                    "minimumX", -1, "minimumY", -1, "maximumX", 1, "maximumY", 1);
            List<Map<String, Object>> remainingVariants = List.of(
                    Map.of("assertionType", "entityExists", "entityId", "ball"),
                    Map.of("assertionType", "scalarApproximatelyEquals", "entityId", "ball",
                            "property", "scalar", "expected", 0, "absoluteTolerance", 0),
                    Map.of("assertionType", "vectorApproximatelyEquals", "entityId", "ball",
                            "property", "position", "expected", vector,
                            "absoluteTolerance", 0, "toleranceMode", "COMPONENT"),
                    Map.of("assertionType", "vectorInArea", "entityId", "ball",
                            "property", "position", "area", area, "relation", "INSIDE",
                            "extent", "FINAL"),
                    Map.of("assertionType", "vectorMagnitudeAtMost", "entityId", "ball",
                            "property", "velocity", "maximum", 0, "extent", "FINAL"),
                    Map.of("assertionType", "vectorDistanceApproximatelyEquals",
                            "leftEntityId", "ball", "leftProperty", "position",
                            "rightEntityId", "ball", "rightProperty", "position",
                            "expectedDistance", 0, "absoluteTolerance", 0),
                    Map.of("assertionType", "wrappedAngleApproximatelyEquals",
                            "entityId", "ball", "property", "angle", "expected", 0,
                            "period", 6.28, "absoluteTolerance", 0),
                    Map.of("assertionType", "eventCount", "eventType", "missing.event",
                            "attributes", Map.of(), "expectation", "NONE", "exactCount", 0),
                    Map.of("assertionType", "objectListContains", "entityId", "ball",
                            "property", "contacts", "selector", Map.of("sensor", false),
                            "extent", "FINAL"),
                    Map.of("assertionType", "allOf", "terms", List.of(
                            Map.of("assertionType", "entityExists", "entityId", "ball"),
                            Map.of("assertionType", "propertyEquals", "entityId", "ball",
                                    "property", "awake", "expected", true))));
            for (Map<String, Object> variant : remainingVariants) {
                McpSchema.CallToolResult decoded = handler.handle(call(
                        "runtime_simulation_assert", simulationAssertionRequest(1, variant)))
                        .block(Duration.ofSeconds(5));
                assertFalse(decoded.isError(), () -> "variant was rejected: " + variant);
            }

            McpSchema.CallToolResult incomplete = handler.handle(call(
                    "runtime_simulation_assert", simulationAssertionRequest(2, Map.of(
                                    "assertionType", "eventCount",
                                    "eventType", "missing.event",
                                    "subject", "ball",
                                    "attributes", Map.of(),
                                    "expectation", "NONE",
                                    "exactCount", 0))))
                    .block(Duration.ofSeconds(5));
            assertFalse(incomplete.isError());
            assertEquals("INCONCLUSIVE", ((Map<?, ?>) ((Map<?, ?>) incomplete.structuredContent())
                    .get("result")).get("status"));

            McpSchema.CallToolResult unknown = handler.handle(call(
                    "runtime_simulation_assert", Map.of(
                            "sessionId", "simulation-assert-mcp",
                            "executionEpochId", 0,
                            "fromEpochTick", 1,
                            "toEpochTick", 1,
                            "evidenceLimit", 8,
                            "evidenceRequirements", List.of(),
                            "assertion", Map.of(
                                    "assertionType", "entityExists",
                                    "entityId", "ball",
                                    "unknown", true))))
                    .block(Duration.ofSeconds(5));
            assertTrue(unknown.isError());
        }
    }

    private static McpSchema.CallToolRequest call(
            String name, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
    }

    private static Map<String, Object> simulationAssertionRequest(
            long toEpochTick, Map<String, Object> assertion) {
        return Map.of(
                "sessionId", "simulation-assert-mcp",
                "executionEpochId", 0,
                "fromEpochTick", 1,
                "toEpochTick", toEpochTick,
                "evidenceLimit", 8,
                "evidenceRequirements", List.of(),
                "assertion", assertion);
    }
}
