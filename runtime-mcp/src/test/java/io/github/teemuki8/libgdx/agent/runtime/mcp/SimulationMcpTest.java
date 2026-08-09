package io.github.teemuki8.libgdx.agent.runtime.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTimelineSpec;
import io.github.teemuki8.libgdx.agent.runtime.protocol.PublishedRuntime;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
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

    private static McpSchema.CallToolRequest call(
            String name, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
    }
}
