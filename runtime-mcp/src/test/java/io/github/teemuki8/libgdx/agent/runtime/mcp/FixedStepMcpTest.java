package io.github.teemuki8.libgdx.agent.runtime.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepDropPolicy;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepSimulationConfiguration;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class FixedStepMcpTest {
    @Test
    void exposesClosedStateUpdatesAndConfiguredAdvanceWithoutDeltaInput() {
        RuntimeRegistry registry = new RuntimeRegistry();
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("fixed-step-mcp"))
                .clock(new IncrementingClock())
                .commandDispatcher(applicationQueue::addLast)
                .build();
        runtime.fixedStepSimulation().register(new FixedStepSimulationConfiguration(
                10, 20, 20, 2, 10,
                FixedStepDropPolicy.DROP_WHOLE_TICKS_KEEP_REMAINDER, true), supplied -> supplied);
        runtime.start();
        runtime.fixedStepSimulation().update(5);
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        applicationQueue.removeFirst().run();
        registry.publish(runtime);

        RuntimeProtocolService service = new RuntimeProtocolService(registry);
        try (RuntimeToolHandler handler = new RuntimeToolHandler(service)) {
            RuntimeToolCatalog catalog = new RuntimeToolCatalog(service.toolNames());
            assertTrue(catalog.toolNames().contains("runtime_fixed_step"));
            assertTrue(catalog.toolNames().contains("runtime_fixed_step_updates"));
            assertTrue(catalog.toolNames().contains("runtime_simulation_advance"));
            Map<String, Object> advanceSchema = catalog.tool(
                    "runtime_simulation_advance").inputSchema();
            assertEquals(false, advanceSchema.get("additionalProperties"));
            assertFalse(((Map<?, ?>) advanceSchema.get("properties")).containsKey("deltaNanos"));

            McpSchema.CallToolResult state = handler.handle(call(
                    "runtime_fixed_step", Map.of("sessionId", "fixed-step-mcp")))
                    .block(Duration.ofSeconds(5));
            assertFalse(state.isError());
            assertEquals("fixedStep", ((Map<?, ?>) state.structuredContent()).get("type"));

            McpSchema.CallToolResult updates = handler.handle(call(
                    "runtime_fixed_step_updates", Map.of(
                            "sessionId", "fixed-step-mcp", "fromSequence", 1,
                            "toSequence", 1, "limit", 10)))
                    .block(Duration.ofSeconds(5));
            assertFalse(updates.isError());

            Map<String, Object> advanceArguments = Map.of(
                            "sessionId", "fixed-step-mcp",
                            "controlRequestId", "advance", "ticks", 2,
                            "timeoutNanos", Duration.ofSeconds(1).toNanos());
            McpSchema.CallToolResult advance = handler.handle(call(
                    "runtime_simulation_advance", advanceArguments))
                    .block(Duration.ofSeconds(5));
            assertFalse(advance.isError());
            applicationQueue.removeFirst().run();
            advance = handler.handle(call("runtime_simulation_advance", advanceArguments))
                    .block(Duration.ofSeconds(5));
            assertFalse(advance.isError());
            assertEquals(2, runtime.controls().currentTick());

            McpSchema.CallToolResult unknown = handler.handle(call(
                    "runtime_simulation_advance", Map.of(
                            "sessionId", "fixed-step-mcp", "controlRequestId", "other",
                            "ticks", 1, "timeoutNanos", 1_000, "deltaNanos", 10)))
                    .block(Duration.ofSeconds(5));
            assertTrue(unknown.isError());
        }
    }

    private static McpSchema.CallToolRequest call(
            String name, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
    }

    private static final class IncrementingClock
            implements io.github.teemuki8.libgdx.agent.runtime.core.MonotonicClock {
        private long value;

        @Override
        public long nanoTime() {
            return ++value;
        }
    }
}
