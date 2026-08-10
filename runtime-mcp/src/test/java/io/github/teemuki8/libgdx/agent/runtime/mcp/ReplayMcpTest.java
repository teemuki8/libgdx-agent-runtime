package io.github.teemuki8.libgdx.agent.runtime.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointHandle;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointProvider;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.ReplayCaptureSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationControllerSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTimelineSpec;
import io.github.teemuki8.libgdx.agent.runtime.protocol.PublishedRuntime;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

final class ReplayMcpTest {
    @Test
    void exposesClosedReplayToolsAndSerializesReplayOutcomes() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        long[] scenarioPosition = {0};
        boolean[] failScenario = {false};
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("replay-mcp"))
                .clock(() -> 1).commandDispatcher(queue::addLast).build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(16));
        runtime.entities().register(EntityId.of("world"), EntityType.of("state"),
                () -> "world", inspector -> inspector
                        .property("step", () -> 16L)
                        .property("position", () -> position[0]));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).acknowledgedTick(delta -> delta).build());
        runtime.scenarios().register("origin", context -> {
            if (failScenario[0]) {
                throw new IllegalStateException("private replay reset token");
            }
            position[0] = scenarioPosition[0];
        });
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override public CheckpointHandle create() {
                return new PositionHandle(position[0]);
            }

            @Override public void restore(CheckpointHandle handle) {
                position[0] = ((PositionHandle) handle).position();
            }

            @Override public void dispose(CheckpointHandle handle) {
                // The test provider owns no native resource.
            }
        });
        runtime.start();
        runtime.checkpoints().create(
                "origin-checkpoint", "replay origin", "create-origin", Duration.ofSeconds(1));
        queue.removeFirst().run();
        runtime.controls().control(true, "pause-replay-mcp", Duration.ofSeconds(1));
        queue.removeFirst().run();

        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeToolCatalog catalog = new RuntimeToolCatalog(
                    new RuntimeProtocolService(registry).toolNames());
            McpSchema.Tool startTool = catalog.tool("runtime_replay_recording_start");
            McpSchema.Tool replayTool = catalog.tool("runtime_replay");
            assertEquals(false, startTool.inputSchema().get("additionalProperties"));
            assertEquals(false, replayTool.inputSchema().get("additionalProperties"));
            assertEquals(List.of(
                    "sessionId", "recordingId", "replayRequestId", "timeoutNanos"),
                    replayTool.inputSchema().get("required"));
            Map<?, ?> startProperties = (Map<?, ?>) startTool.inputSchema().get("properties");
            assertEquals(List.of("scenario", "checkpoint"),
                    ((Map<?, ?>) startProperties.get("originKind")).get("enum"));
            assertEquals(List.of(
                    "sessionId", "recordingId", "replayRequestId", "originKind", "originId",
                    "configuration", "profile", "configurationRequirements",
                    "evidenceRequirements", "eventTypes", "timeoutNanos"),
                    startTool.inputSchema().get("required"));
            assertClosedObject(startProperties.get("profile"));
            assertClosedObject(((Map<?, ?>) startProperties.get("profile"))
                    .get("properties") instanceof Map<?, ?> profileProperties
                            ? profileProperties.get("comparisonScope") : null);
            assertClosedArrayItems(startProperties.get("configuration"));
            Map<?, ?> configurationItems = (Map<?, ?>) ((Map<?, ?>)
                    startProperties.get("configuration")).get("items");
            Map<?, ?> configurationProperties = (Map<?, ?>) configurationItems.get("properties");
            assertTrue(((Map<?, ?>) configurationProperties.get("value"))
                    .containsKey("anyOf"));
            assertFalse(configurationProperties.get("value").toString().contains("array"));
            assertFalse(configurationProperties.get("value").toString().contains("object"));
            assertClosedArrayItems(startProperties.get("configurationRequirements"));
            assertClosedArrayItems(startProperties.get("evidenceRequirements"));
            Map<?, ?> definitions = (Map<?, ?>) startTool.inputSchema().get("$defs");
            assertEquals(16, ((Map<?, ?>) definitions.get("replayValue"))
                    .get("x-runtime-maxDepth"));
            assertEquals(1_024, ((Map<?, ?>) definitions.get("replayValue"))
                    .get("x-runtime-maxNodes"));

            Map<String, Object> start = startRequest();
            capture(handler, runtime, queue, start, "recording", "stop-recording");
            assertEquals("EQUAL", replayStatus(
                    handler, queue, "recording", "execute-recording"));

            Map<String, Object> checkpointStart = with(start,
                    "recordingId", "checkpoint-recording",
                    "replayRequestId", "start-checkpoint-recording",
                    "originKind", "checkpoint",
                    "originId", "origin-checkpoint");
            capture(handler, runtime, queue, checkpointStart,
                    "checkpoint-recording", "stop-checkpoint-recording");
            assertEquals("EQUAL", replayStatus(
                    handler, queue, "checkpoint-recording", "execute-checkpoint-recording"));

            Map<String, Object> divergentStart = with(start,
                    "recordingId", "divergent-recording",
                    "replayRequestId", "start-divergent-recording");
            capture(handler, runtime, queue, divergentStart,
                    "divergent-recording", "stop-divergent-recording");
            scenarioPosition[0] = 1;
            assertEquals("DIVERGED", replayStatus(
                    handler, queue, "divergent-recording", "execute-divergent-recording"));
            scenarioPosition[0] = 0;

            RecordingSpec ordinary = new RecordingSpec(
                    "ordinary-recording", "2.5", List.of(), Optional.of("origin"),
                    Optional.empty(), OptionalLong.empty(), RuntimeValues.object(), false);
            runtime.recordings().start(
                    ordinary, "start-ordinary-recording", Duration.ofSeconds(1));
            queue.removeFirst().run();
            runtime.recordings().stop(
                    "ordinary-recording", "stop-ordinary-recording", Duration.ofSeconds(1));
            queue.removeFirst().run();
            assertEquals("INCONCLUSIVE", replayStatus(
                    handler, queue, "ordinary-recording", "execute-ordinary-recording"));

            Map<String, Object> failedStart = with(start,
                    "recordingId", "failed-recording",
                    "replayRequestId", "start-failed-recording");
            capture(handler, runtime, queue, failedStart,
                    "failed-recording", "stop-failed-recording");
            failScenario[0] = true;
            McpSchema.CallToolResult failed = replay(
                    handler, queue, "failed-recording", "execute-failed-recording");
            Map<?, ?> failedResult = replayResult(failed);
            assertEquals("INCONCLUSIVE", failedResult.get("status"));
            assertTrue(failedResult.containsKey("applicationFailure"));
            assertFalse(failed.toString().contains("private replay reset token"));

            LinkedHashMap<String, Object> invalid = new LinkedHashMap<>(start);
            invalid.put("originKind", "filesystem");
            assertTrue(handler.handle(call("runtime_replay_recording_start", invalid))
                    .block(Duration.ofSeconds(5)).isError());
            invalid = new LinkedHashMap<>(start);
            invalid.put("unknown", true);
            assertTrue(handler.handle(call("runtime_replay_recording_start", invalid))
                    .block(Duration.ofSeconds(5)).isError());
            invalid = new LinkedHashMap<>(start);
            invalid.put("profile", Map.of(
                    "comparisonScope", ((Map<?, ?>) start.get("profile"))
                            .get("comparisonScope"),
                    "includeUiCorrelations", false,
                    "unknown", true));
            assertTrue(handler.handle(call("runtime_replay_recording_start", invalid))
                    .block(Duration.ofSeconds(5)).isError());
            invalid = new LinkedHashMap<>(start);
            invalid.put("configuration", List.of(Map.of(
                    "name", "nested", "value", List.of(1))));
            assertTrue(handler.handle(call("runtime_replay_recording_start", invalid))
                    .block(Duration.ofSeconds(5)).isError());
            invalid = new LinkedHashMap<>(start);
            invalid.put("configurationRequirements", Collections.nCopies(
                    ReplayCaptureSpec.MAX_CONFIGURATION_REQUIREMENTS + 1,
                    Map.of("entityId", "world", "property", "step", "expected", 16)));
            assertTrue(handler.handle(call("runtime_replay_recording_start", invalid))
                    .block(Duration.ofSeconds(5)).isError());
            invalid = new LinkedHashMap<>(start);
            invalid.put("recordingId", "unknown-origin-recording");
            invalid.put("replayRequestId", "start-unknown-origin-recording");
            invalid.put("originId", "missing-origin");
            assertTrue(handler.handle(call("runtime_replay_recording_start", invalid))
                    .block(Duration.ofSeconds(5)).isError());
            assertTrue(queue.isEmpty());
        }
    }

    @Test
    void omitsReplayToolsWithoutAnyCapableSessionAndRejectsAnUnavailableTarget() {
        ArrayDeque<Runnable> unavailableQueue = new ArrayDeque<>();
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime unavailable = AgentRuntime.builder()
                .sessionId(SessionId.of("replay-unavailable"))
                .commandDispatcher(unavailableQueue::addLast).build();
        unavailable.start();
        try (PublishedRuntime unavailablePublication = registry.publish(unavailable)) {
            assertEquals(unavailable.sessionId(), unavailablePublication.sessionId());
            RuntimeProtocolService protocol = new RuntimeProtocolService(registry);
            assertFalse(new RuntimeToolCatalog(protocol.toolNames()).toolNames()
                    .contains("runtime_replay"));

            AgentRuntime available = AgentRuntime.builder()
                    .sessionId(SessionId.of("replay-available"))
                    .commandDispatcher(command -> {}).build();
            available.simulation().register(SimulationTimelineSpec.fixedStep(16));
            available.controls().register(SimulationControllerSpec.builder()
                    .pause(() -> {}).resume(() -> {}).acknowledgedTick(delta -> delta).build());
            available.scenarios().register("origin", context -> {});
            available.start();
            try (PublishedRuntime availablePublication = registry.publish(available);
                    RuntimeToolHandler handler = new RuntimeToolHandler(protocol)) {
                assertEquals(available.sessionId(), availablePublication.sessionId());
                assertTrue(new RuntimeToolCatalog(protocol.toolNames()).toolNames()
                        .containsAll(List.of(
                                "runtime_replay_recording_start", "runtime_replay")));
                Map<String, Object> request = with(startRequest(),
                        "sessionId", "replay-unavailable");
                McpSchema.CallToolResult rejected = handler.handle(call(
                        "runtime_replay_recording_start", request))
                        .block(Duration.ofSeconds(5));
                assertTrue(rejected.isError());
                assertEquals("CAPABILITY_UNAVAILABLE",
                        ((Map<?, ?>) rejected.structuredContent()).get("code"));
                assertTrue(unavailableQueue.isEmpty());
            }
        }
    }

    private static void capture(RuntimeToolHandler handler, AgentRuntime runtime,
            ArrayDeque<Runnable> queue, Map<String, Object> request,
            String recordingId, String stopRequestId) {
        McpSchema.CallToolResult queued = handler.handle(call(
                "runtime_replay_recording_start", request)).block(Duration.ofSeconds(5));
        assertFalse(queued.isError());
        queue.removeFirst().run();
        runtime.recordings().stop(recordingId, stopRequestId, Duration.ofSeconds(1));
        queue.removeFirst().run();
    }

    private static String replayStatus(RuntimeToolHandler handler, ArrayDeque<Runnable> queue,
            String recordingId, String requestId) {
        return (String) replayResult(replay(handler, queue, recordingId, requestId)).get("status");
    }

    private static McpSchema.CallToolResult replay(RuntimeToolHandler handler,
            ArrayDeque<Runnable> queue, String recordingId, String requestId) {
        Map<String, Object> request = Map.of(
                "sessionId", "replay-mcp", "recordingId", recordingId,
                "replayRequestId", requestId,
                "timeoutNanos", Duration.ofSeconds(1).toNanos());
        McpSchema.CallToolResult queued = handler.handle(call("runtime_replay", request))
                .block(Duration.ofSeconds(5));
        assertFalse(queued.isError());
        Map<?, ?> queuedContent = (Map<?, ?>) queued.structuredContent();
        assertEquals("replay", queuedContent.get("type"));
        Map<?, ?> queuedOperation = (Map<?, ?>) queuedContent.get("operation");
        Map<?, ?> queuedCommand = (Map<?, ?>) queuedOperation.get("command");
        assertEquals("QUEUED", ((Map<?, ?>) queuedCommand.get("status")).get("state"));
        assertNull(queuedOperation.get("result"));
        queue.removeFirst().run();
        McpSchema.CallToolResult completed = handler.handle(call("runtime_replay", request))
                .block(Duration.ofSeconds(5));
        assertFalse(completed.isError());
        return completed;
    }

    private static Map<?, ?> replayResult(McpSchema.CallToolResult response) {
        Map<?, ?> content = (Map<?, ?>) response.structuredContent();
        assertEquals("replay", content.get("type"));
        Map<?, ?> operation = (Map<?, ?>) content.get("operation");
        return (Map<?, ?>) operation.get("result");
    }

    private static void assertClosedObject(Object raw) {
        assertEquals(false, ((Map<?, ?>) raw).get("additionalProperties"));
    }

    private static void assertClosedArrayItems(Object raw) {
        assertClosedObject(((Map<?, ?>) raw).get("items"));
    }

    private static Map<String, Object> with(Map<String, Object> source, Object... changes) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(source);
        for (int index = 0; index < changes.length; index += 2) {
            result.put((String) changes[index], changes[index + 1]);
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> startRequest() {
        LinkedHashMap<String, Object> request = new LinkedHashMap<>();
        request.put("sessionId", "replay-mcp");
        request.put("recordingId", "recording");
        request.put("replayRequestId", "start-recording");
        request.put("originKind", "scenario");
        request.put("originId", "origin");
        request.put("randomSeed", 7);
        request.put("configuration", List.of(
                Map.of("name", "enabled", "value", true),
                Map.of("name", "iterations", "value", 8),
                Map.of("name", "scale", "value", 1.5),
                Map.of("name", "quality", "value", "high")));
        request.put("profile", Map.of(
                "comparisonScope", Map.of(
                        "entityIds", List.of("world"),
                        "properties", List.of("position"),
                        "excludedProperties", List.of(),
                        "includeEvents", false,
                        "includeDecisions", false),
                "includeUiCorrelations", false));
        request.put("configurationRequirements", List.of(Map.of(
                "entityId", "world", "property", "step", "expected", 16)));
        request.put("evidenceRequirements", List.of());
        request.put("eventTypes", List.of());
        request.put("timeoutNanos", Duration.ofSeconds(1).toNanos());
        return Map.copyOf(request);
    }

    private static McpSchema.CallToolRequest call(
            String name, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
    }

    private record PositionHandle(long position) implements CheckpointHandle {}
}
