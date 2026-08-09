package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
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
import java.util.ArrayDeque;
import java.util.OptionalLong;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class FixedStepFixtureTest {
    @Test
    void realFixtureRunsNormalTicksThenScheduledInputAndExactPausedTicks() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        FixedStepFixtureSimulation fixture = new FixedStepFixtureSimulation(
                applicationQueue::addLast);

        var update = fixture.simulation().update(0.025f);
        assertEquals(2, update.ticksCompleted());
        assertEquals(5_000_000L, update.accumulatorRemainderNanos());

        fixture.runtime().controls().control(true, "pause", Duration.ofSeconds(1));
        applicationQueue.removeFirst().run();
        fixture.runtime().inputs().inject("boost", "boost-input", RuntimeValues.object(),
                OptionalLong.empty(), Duration.ofSeconds(1));
        applicationQueue.removeFirst().run();
        fixture.runtime().controls().advanceFixed(
                "fixed-advance", 2, Duration.ofSeconds(1));
        applicationQueue.removeFirst().run();

        assertEquals(4, fixture.completedTicks());
        assertTrue(fixture.controlledTickObservedInput());
        assertEquals(5_000_000L,
                fixture.runtime().fixedStepSimulation().state().accumulatorRemainderNanos());
        assertEquals(4, fixture.runtime().simulation().state().completedEpochTicks());
        assertEquals(4, fixture.runtime().latestFrame().orElseThrow().frameId().value());
        fixture.runtime().close();
    }

    @Test
    void fixedStepReportsAndExactAdvanceCrossProtocolAndMcp() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        FixedStepFixtureSimulation fixture = new FixedStepFixtureSimulation(
                applicationQueue::addLast);
        fixture.simulation().update(0.025f);
        fixture.runtime().controls().control(true, "pause", Duration.ofSeconds(1));
        applicationQueue.removeFirst().run();
        RuntimeRegistry registry = new RuntimeRegistry();

        try (PublishedRuntime publication = registry.publish(fixture.runtime());
                RuntimeToolHandler handler = new RuntimeToolHandler(
                        new RuntimeProtocolService(registry))) {
            assertEquals(fixture.runtime().sessionId(), publication.sessionId());
            RuntimeResponse.Result.FixedStepUpdates updates = assertInstanceOf(
                    RuntimeResponse.Result.FixedStepUpdates.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            new RuntimeProtocolService(registry).execute(new RuntimeRequest(
                                    ProtocolVersion.V2_2, "fixture-updates",
                                    FixedStepFixtureSimulation.SESSION_ID.value(),
                                    new RuntimeCommand.FixedStepUpdates(1, 1, 8))))
                            .result());
            assertEquals(2, updates.page().reports().getFirst().ticksCompleted());

            McpSchema.CallToolResult state = handler.handle(call(
                    "runtime_fixed_step", Map.of("sessionId",
                            FixedStepFixtureSimulation.SESSION_ID.value())))
                    .block(Duration.ofSeconds(5));
            assertFalse(state.isError());
            assertTrue(state.structuredContent().toString().contains("5000000"));

            Map<String, Object> advance = Map.of(
                    "sessionId", FixedStepFixtureSimulation.SESSION_ID.value(),
                    "controlRequestId", "fixture-fixed-advance", "ticks", 2,
                    "timeoutNanos", Duration.ofSeconds(1).toNanos());
            assertFalse(handler.handle(call("runtime_simulation_advance", advance))
                    .block(Duration.ofSeconds(5)).isError());
            applicationQueue.removeFirst().run();
            assertFalse(handler.handle(call("runtime_simulation_advance", advance))
                    .block(Duration.ofSeconds(5)).isError());
            assertEquals(4, fixture.completedTicks());
            assertEquals(5_000_000L,
                    fixture.runtime().fixedStepSimulation().state().accumulatorRemainderNanos());
        }
        fixture.runtime().close();
    }

    private static McpSchema.CallToolRequest call(
            String name, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
    }
}
