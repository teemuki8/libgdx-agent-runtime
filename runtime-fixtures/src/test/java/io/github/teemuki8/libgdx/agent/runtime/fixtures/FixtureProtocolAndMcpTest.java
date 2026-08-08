package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.ApplicationFailureEvidence;
import io.github.teemuki8.libgdx.agent.runtime.core.CommandState;
import io.github.teemuki8.libgdx.agent.runtime.core.CommandDispatchLimits;
import io.github.teemuki8.libgdx.agent.runtime.core.BaselineKind;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameId;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeAssertion;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.mcp.RuntimeToolHandler;
import io.github.teemuki8.libgdx.agent.runtime.mcp.RuntimeMcpServer;
import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolJson;
import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolVersion;
import io.github.teemuki8.libgdx.agent.runtime.protocol.PublishedRuntime;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeCommand;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRequest;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeResponse;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class FixtureProtocolAndMcpTest {
    @Test
    void fixtureEvidenceRoundTripsThroughProtocol() {
        Fixture fixture = fixture();
        try (PublishedRuntime publication = fixture.registry.publish(fixture.runtime)) {
            assertEquals(fixture.runtime.sessionId(), publication.sessionId());
            RuntimeResponse response = new RuntimeProtocolService(fixture.registry).execute(
                    new RuntimeRequest(ProtocolVersion.V1, "fixture-events",
                            DeterministicSimulation.SESSION_ID.value(),
                            new RuntimeCommand.Events(
                                    0, 45, "projectile.hit", false,
                                    "enemy-2", "projectile-3", 10)));
            RuntimeResponse decoded = ProtocolJson.decodeResponse(ProtocolJson.encode(response));
            RuntimeResponse.Result.Events result = assertInstanceOf(
                    RuntimeResponse.Result.Events.class,
                    assertInstanceOf(RuntimeResponse.Success.class, decoded).result());
            assertEquals(1, result.page().items().size());
            assertEquals(25, ((io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue.IntegerValue)
                    result.page().items().getFirst().attributes().getFirst().value()).value());

            RuntimeResponse capabilityResponse = new RuntimeProtocolService(fixture.registry)
                    .execute(new RuntimeRequest(ProtocolVersion.V1_1, "fixture-capabilities",
                            DeterministicSimulation.SESSION_ID.value(),
                            new RuntimeCommand.Capabilities()));
            RuntimeResponse.Result.Capabilities capabilities = assertInstanceOf(
                    RuntimeResponse.Result.Capabilities.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            ProtocolJson.decodeResponse(ProtocolJson.encode(capabilityResponse)))
                            .result());
            assertEquals(List.of("changes", "decisions", "entities", "events", "frames"),
                    capabilities.capabilityReport().orElseThrow().capabilities().stream()
                            .map(capability -> capability.id()).toList());
        }
        fixture.runtime.close();
    }

    @Test
    void fixtureCorrelatesRuntimeAndUiSelectorsInBothDirections() {
        Fixture fixture = fixture();
        try (PublishedRuntime publication = fixture.registry.publish(fixture.runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(fixture.registry))) {
            assertEquals(fixture.runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService protocol = new RuntimeProtocolService(fixture.registry);
            RuntimeResponse.Result.UiBindings runtimeToUi = assertInstanceOf(
                    RuntimeResponse.Result.UiBindings.class,
                    assertInstanceOf(RuntimeResponse.Success.class, protocol.execute(
                            new RuntimeRequest(ProtocolVersion.V1_11, "runtime-to-ui",
                                    DeterministicSimulation.SESSION_ID.value(),
                                    new RuntimeCommand.UiBindings(
                                            "player-1", "state", null, null,
                                            0, 0, null, 8)))).result());
            assertEquals("player-state",
                    runtimeToUi.result().bindings().getFirst().uiControlId());

            RuntimeResponse.Result.UiBindings uiToRuntime = assertInstanceOf(
                    RuntimeResponse.Result.UiBindings.class,
                    assertInstanceOf(RuntimeResponse.Success.class, protocol.execute(
                            new RuntimeRequest(ProtocolVersion.V1_11, "ui-to-runtime",
                                    DeterministicSimulation.SESSION_ID.value(),
                                    new RuntimeCommand.UiBindings(
                                            null, null, "fixture-hud", "player-state",
                                            0, 0, null, 8)))).result());
            assertEquals(EntityId.of("player-1"),
                    uiToRuntime.result().bindings().getFirst().runtimeEntityId());

            McpSchema.CallToolResult frames = handler.handle(call("runtime_ui_frames", Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "correlationToken", "fixture-baseline", "limit", 8)))
                    .block(Duration.ofSeconds(5));
            assertFalse(frames.isError());
            assertTrue(frames.structuredContent().toString().contains("ui-baseline"));
        }
        fixture.runtime.close();
    }

    @Test
    void fixtureEvidenceIsAvailableThroughMcpToolCalls() {
        Fixture fixture = fixture();
        try (PublishedRuntime publication = fixture.registry.publish(fixture.runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(fixture.registry))) {
            assertEquals(fixture.runtime.sessionId(), publication.sessionId());
            McpSchema.CallToolResult snapshot = handler.handle(call("runtime_snapshot", Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "entityId", "enemy-1"))).block(Duration.ofSeconds(5));
            assertNotNull(snapshot);
            assertFalse(snapshot.isError());
            assertTrue(snapshot.structuredContent().toString().contains("enemy-1"));

            McpSchema.CallToolResult events = handler.handle(call("runtime_events", Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "eventType", "projectile.hit"))).block(Duration.ofSeconds(5));
            assertNotNull(events);
            assertFalse(events.isError());
            assertTrue(events.structuredContent().toString().contains("projectile.hit"));

            McpSchema.CallToolResult decisions = handler.handle(call("runtime_decisions", Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "reasonCode", "out-of-range"))).block(Duration.ofSeconds(5));
            assertNotNull(decisions);
            assertFalse(decisions.isError());
            assertTrue(decisions.structuredContent().toString().contains("out-of-range"));

            McpSchema.CallToolResult capabilities = handler.handle(call(
                    "runtime_capabilities", Map.of(
                            "sessionId", DeterministicSimulation.SESSION_ID.value(),
                            "protocolMinor", 1))).block(Duration.ofSeconds(5));
            assertNotNull(capabilities);
            assertFalse(capabilities.isError());
            assertTrue(capabilities.structuredContent().toString().contains("capabilityReport"));
        }
        fixture.runtime.close();
    }

    @Test
    void applicationOwnedFixtureQueueIsObservableAndCancellableThroughMcp() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        DeterministicSimulation simulation = new DeterministicSimulation();
        AgentRuntime runtime = simulation.startRuntime(applicationQueue::addLast);
        int[] executions = {0};
        runtime.commands().orElseThrow().submit(
                "fixture-command", Duration.ofSeconds(1), () -> executions[0]++);
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            McpSchema.CallToolResult status = handler.handle(call(
                    "runtime_command_status", Map.of(
                            "sessionId", DeterministicSimulation.SESSION_ID.value(),
                            "commandRequestId", "fixture-command")))
                    .block(Duration.ofSeconds(5));
            assertNotNull(status);
            assertFalse(status.isError());
            assertTrue(status.structuredContent().toString().contains(CommandState.QUEUED.name()));

            McpSchema.CallToolResult cancellation = handler.handle(call(
                    "runtime_command_cancel", Map.of(
                            "sessionId", DeterministicSimulation.SESSION_ID.value(),
                            "commandRequestId", "fixture-command")))
                    .block(Duration.ofSeconds(5));
            assertNotNull(cancellation);
            assertFalse(cancellation.isError());
            assertTrue(cancellation.structuredContent().toString().contains("accepted=true"));
        }
        applicationQueue.removeFirst().run();
        assertEquals(0, executions[0]);
        runtime.close();
    }

    @Test
    void fixtureSemanticActionMatchesMcpProtocolAttributionAndClosedSchema() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        DeterministicSimulation simulation = new DeterministicSimulation();
        AgentRuntime runtime = simulation.startRuntime(applicationQueue::addLast);
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            Map<String, Object> action = Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "action", "set-tower-state",
                    "actionRequestId", "fixture-action",
                    "correlationId", "fixture-action-1",
                    "parameters", Map.of("state", "ALERT"),
                    "timeoutNanos", 1_000_000_000);
            assertFalse(handler.handle(call("runtime_action", action))
                    .block(Duration.ofSeconds(5)).isError());
            applicationQueue.removeFirst().run();
            assertFalse(handler.handle(call("runtime_action", action))
                    .block(Duration.ofSeconds(5)).isError());
            RuntimeResponse.Result.Action protocolAction = assertInstanceOf(
                    RuntimeResponse.Result.Action.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            new RuntimeProtocolService(registry).execute(new RuntimeRequest(
                                    ProtocolVersion.V1_6, "fixture-action-protocol",
                                    DeterministicSimulation.SESSION_ID.value(),
                                    new RuntimeCommand.Action(
                                            "set-tower-state", "fixture-action",
                                            RuntimeValues.object(RuntimeValues.field(
                                                    "state", RuntimeValues.string("ALERT"))),
                                            "fixture-action-1", 1_000_000_000)))).result());
            assertEquals(CommandState.SUCCEEDED,
                    protocolAction.invocation().command().status().orElseThrow().state());
            RuntimeResponse.Result.Changes attributed = assertInstanceOf(
                    RuntimeResponse.Result.Changes.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            new RuntimeProtocolService(registry).execute(new RuntimeRequest(
                                    ProtocolVersion.V1_5, "fixture-attribution",
                                    DeterministicSimulation.SESSION_ID.value(),
                                    new RuntimeCommand.AttributedChanges(
                                            0, 1, "tower-1", null, "state",
                                            "fixture-action", "fixture-action-1", 8)))).result());
            assertEquals(1, attributed.page().items().size());
            assertEquals(1, simulation.actionExecutions());
            assertEquals(Thread.currentThread(), simulation.lastMutationThread());

            Map<String, Object> unknown = new java.util.LinkedHashMap<>(action);
            unknown.put("script", "run()");
            assertTrue(handler.handle(call("runtime_action", unknown))
                    .block(Duration.ofSeconds(5)).isError());
            assertEquals(1, simulation.actionExecutions());
        }
        runtime.close();
    }

    @Test
    void fixtureControlPausesNormalUpdatesAdvancesExactlyAndWaitsForCondition() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        DeterministicSimulation simulation = new DeterministicSimulation();
        AgentRuntime runtime = simulation.startRuntime(applicationQueue::addLast);
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            Map<String, Object> pause = Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "action", "PAUSE", "controlRequestId", "pause-fixture",
                    "timeoutNanos", 1_000_000_000);
            handler.handle(call("runtime_control", pause)).block(Duration.ofSeconds(5));
            applicationQueue.removeFirst().run();
            simulation.advance(runtime, 1);
            assertEquals(0, runtime.latestFrame().orElseThrow().frameId().value());


            Map<String, Object> input = Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "input", "set-player-state", "inputRequestId", "input-fixture",
                    "parameters", Map.of("state", "BOOSTED"),
                    "timeoutNanos", 1_000_000_000);
            handler.handle(call("runtime_input", input)).block(Duration.ofSeconds(5));
            applicationQueue.removeFirst().run();
            Map<String, Object> advance = Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "controlRequestId", "advance-fixture", "ticks", 2,
                    "deltaNanos", 16_000_000, "timeoutNanos", 1_000_000_000);
            handler.handle(call("runtime_advance", advance)).block(Duration.ofSeconds(5));
            applicationQueue.removeFirst().run();
            McpSchema.CallToolResult advanced =
                    handler.handle(call("runtime_advance", advance)).block(Duration.ofSeconds(5));
            assertFalse(advanced.isError());
            RuntimeResponse.Result.Control advancedProtocol = assertInstanceOf(
                    RuntimeResponse.Result.Control.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            new RuntimeProtocolService(registry).execute(new RuntimeRequest(
                                    ProtocolVersion.V1_8, "advance-evidence",
                                    DeterministicSimulation.SESSION_ID.value(),
                                    new RuntimeCommand.Advance(
                                            "advance-fixture", 2, 16_000_000,
                                            1_000_000_000)))).result());
            assertEquals(2, advancedProtocol.operation().orElseThrow().completedTicks());
            assertEquals(List.of(16_000_000L, 16_000_000L),
                    simulation.controlledDeltaNanos());
            assertEquals(2, runtime.latestFrame().orElseThrow().frameId().value());
            McpSchema.CallToolResult injected =
                    handler.handle(call("runtime_input", input)).block(Duration.ofSeconds(5));
            assertFalse(injected.isError());
            assertTrue(injected.structuredContent().toString().contains("EXECUTED"));
            assertEquals(RuntimeValues.enumValue("BOOSTED"),
                    runtime.frame(new io.github.teemuki8.libgdx.agent.runtime.core.FrameId(1))
                            .orElseThrow().entity(EntityId.of("player-1")).orElseThrow()
                            .property("state").orElseThrow());

            Map<String, Object> wait = Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "controlRequestId", "wait-fixture", "conditionId", "frame-48-complete",
                    "maximumTicks", 46, "deltaNanos", 16_000_000,
                    "evidenceLimit", 8, "timeoutNanos", 1_000_000_000);
            handler.handle(call("runtime_wait", wait)).block(Duration.ofSeconds(5));
            applicationQueue.removeFirst().run();
            McpSchema.CallToolResult waited =
                    handler.handle(call("runtime_wait", wait)).block(Duration.ofSeconds(5));
            assertFalse(waited.isError());
            RuntimeResponse.Result.Control waitedProtocol = assertInstanceOf(
                    RuntimeResponse.Result.Control.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            new RuntimeProtocolService(registry).execute(new RuntimeRequest(
                                    ProtocolVersion.V1_8, "wait-evidence",
                                    DeterministicSimulation.SESSION_ID.value(),
                                    new RuntimeCommand.Wait(
                                            "wait-fixture", "frame-48-complete", null,
                                            46, 16_000_000, 8, 1_000_000_000)))).result());
            assertEquals("CONDITION_SATISFIED",
                    waitedProtocol.operation().orElseThrow().stopReason().name());
            assertEquals(48, runtime.latestFrame().orElseThrow().frameId().value());

            Map<String, Object> resume = Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "action", "RESUME", "controlRequestId", "resume-fixture",
                    "timeoutNanos", 1_000_000_000);
            handler.handle(call("runtime_control", resume)).block(Duration.ofSeconds(5));
            applicationQueue.removeFirst().run();
            simulation.advance(runtime, 49);
            assertEquals(49, runtime.latestFrame().orElseThrow().frameId().value());
        }
        runtime.close();
    }

    @Test
    void fixtureCheckpointRestoresApplicationStateAndPublishesOneBaseline() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        DeterministicSimulation simulation = new DeterministicSimulation();
        AgentRuntime runtime = simulation.startRuntime(applicationQueue::addLast);
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            Map<String, Object> create = Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "checkpointId", "before-targeting",
                    "checkpointRequestId", "checkpoint-create",
                    "timeoutNanos", 1_000_000_000);
            handler.handle(call("runtime_checkpoint_create", create)).block(Duration.ofSeconds(5));
            applicationQueue.removeFirst().run();

            simulation.advance(runtime, 20);
            assertEquals(RuntimeValues.enumValue("TRACKING"),
                    runtime.latestFrame().orElseThrow().entity(EntityId.of("tower-1"))
                            .orElseThrow().property("state").orElseThrow());

            RuntimeCommand.CheckpointRestore restore = new RuntimeCommand.CheckpointRestore(
                    "before-targeting", "checkpoint-restore", 1_000_000_000);
            RuntimeProtocolService protocol = new RuntimeProtocolService(registry);
            protocol.execute(new RuntimeRequest(ProtocolVersion.V1_10, "restore-submit",
                    DeterministicSimulation.SESSION_ID.value(), restore));
            applicationQueue.removeFirst().run();
            RuntimeResponse.Result.Checkpoint restored = assertInstanceOf(
                    RuntimeResponse.Result.Checkpoint.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            protocol.execute(new RuntimeRequest(ProtocolVersion.V1_10,
                                    "restore-poll", DeterministicSimulation.SESSION_ID.value(),
                                    restore))).result());
            assertEquals(2, restored.operation().baselineFrameId().orElseThrow().value());
            assertEquals(BaselineKind.CHECKPOINT_RESTORE,
                    runtime.latestFrame().orElseThrow().baselineKind().orElseThrow());
            assertEquals(RuntimeValues.enumValue("IDLE"),
                    runtime.latestFrame().orElseThrow().entity(EntityId.of("tower-1"))
                            .orElseThrow().property("state").orElseThrow());
        }
        runtime.close();
    }

    @Test
    void fixtureExposesResetBaselineEpochThroughProtocolAndMcp() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        DeterministicSimulation simulation = new DeterministicSimulation();
        AgentRuntime runtime = simulation.startRuntime(applicationQueue::addLast);
        simulation.advance(runtime, 20);
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            Map<String, Object> reset = Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "scenarioId", "deterministic-fixture",
                    "resetRequestId", "fixture-reset",
                    "timeoutNanos", 1_000_000_000);
            assertFalse(handler.handle(call("runtime_reset", reset))
                    .block(Duration.ofSeconds(5)).isError());
            applicationQueue.removeFirst().run();
            McpSchema.CallToolResult resetResult = handler.handle(call("runtime_reset", reset))
                    .block(Duration.ofSeconds(5));
            assertFalse(resetResult.isError());
            assertTrue(resetResult.structuredContent().toString().contains("baselineFrameId"));
            assertEquals(BaselineKind.SCENARIO_RESET,
                    runtime.latestFrame().orElseThrow().baselineKind().orElseThrow());

            RuntimeResponse.Result.EpochFrames protocol = assertInstanceOf(
                    RuntimeResponse.Result.EpochFrames.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            new RuntimeProtocolService(registry).execute(new RuntimeRequest(
                                    ProtocolVersion.V1_3, "epoch-fixture",
                                    DeterministicSimulation.SESSION_ID.value(),
                                    new RuntimeCommand.EpochFrames(1, 10)))).result());
            assertEquals(0, protocol.page().items().getFirst().changeCount());
            McpSchema.CallToolResult mcp = handler.handle(call("runtime_epoch_frames", Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "executionEpochId", 1))).block(Duration.ofSeconds(5));
            assertNotNull(mcp);
            assertFalse(mcp.isError());
            assertTrue(mcp.structuredContent().toString().contains("SCENARIO_RESET"));
            simulation.advance(runtime, 1);
            assertEquals(3, runtime.latestFrame().orElseThrow().frameId().value());
        }
        runtime.close();
    }

    @Test
    void fixtureAssertionHasMatchingProtocolAndMcpSemantics() {
        Fixture fixture = fixture();
        try (PublishedRuntime publication = fixture.registry.publish(fixture.runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(fixture.registry))) {
            assertEquals(fixture.runtime.sessionId(), publication.sessionId());
            RuntimeResponse response = new RuntimeProtocolService(fixture.registry).execute(
                    new RuntimeRequest(ProtocolVersion.V1_7, "fixture-assert",
                            DeterministicSimulation.SESSION_ID.value(),
                            new RuntimeCommand.Assert(
                                    new RuntimeAssertion.EventOccurs(
                                            io.github.teemuki8.libgdx.agent.runtime.core.EventType
                                                    .of("projectile.hit")),
                                    0, 45, 0, 8)));
            RuntimeResponse.Result.Assertion protocol = assertInstanceOf(
                    RuntimeResponse.Result.Assertion.class,
                    assertInstanceOf(RuntimeResponse.Success.class, response).result());
            McpSchema.CallToolResult mcp = handler.handle(call("runtime_assert", Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "fromFrame", 0, "toFrame", 45, "executionEpochId", 0,
                    "evidenceLimit", 8, "assertion", Map.of(
                            "assertionType", "eventOccurs",
                            "eventType", "projectile.hit")))).block(Duration.ofSeconds(5));

            assertEquals(io.github.teemuki8.libgdx.agent.runtime.core.AssertionStatus.PASS,
                    protocol.result().status());
            assertNotNull(mcp);
            assertFalse(mcp.isError());
            assertTrue(mcp.structuredContent().toString().contains("PASS"));
        }
        fixture.runtime.close();
    }

    @Test
    void fixtureRemovedEntityHistoryIsQueryableUnderProtocolTwo() {
        Fixture fixture = fixture();
        try (PublishedRuntime publication = fixture.registry.publish(fixture.runtime)) {
            assertEquals(fixture.runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(fixture.registry);
            RuntimeResponse.Result.EntityHistory page = assertInstanceOf(
                    RuntimeResponse.Result.EntityHistory.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V2, "fixture-history",
                                    DeterministicSimulation.SESSION_ID.value(),
                                    new RuntimeCommand.EntityHistory(
                                            "enemy-2", 1, 45, 0, 10)))).result());
            assertTrue(page.page().current().isEmpty());
            assertEquals(RuntimeValues.enumValue("DEAD"), page.page().finalRetainedState()
                    .orElseThrow().property("state").orElseThrow());
            assertEquals(10, page.page().versions().size());
            assertEquals(10, page.page().nextVersionOffset());
            assertTrue(page.page().hasMoreVersions());
            assertFalse(page.page().requestedRangePartiallyEvicted());

            RuntimeResponse.Result.EntityHistory second = assertInstanceOf(
                    RuntimeResponse.Result.EntityHistory.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V2, "fixture-history-2",
                                    DeterministicSimulation.SESSION_ID.value(),
                                    new RuntimeCommand.EntityHistory(
                                            "enemy-2", 1, 45, 10, 100)))).result());
            assertEquals(29, second.page().versions().size());
            assertFalse(second.page().hasMoreVersions());
            assertEquals(39, second.page().versions().getLast().frameId().value());
            assertEquals(Optional.of(new FrameId(0)), second.page().oldestRetainedFrame());
        }
        fixture.runtime.close();
    }

    @Test
    void fixtureRemovedEntityHistoryIsQueryableThroughMcp() {
        Fixture fixture = fixture();
        try (PublishedRuntime publication = fixture.registry.publish(fixture.runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(fixture.registry))) {
            assertEquals(fixture.runtime.sessionId(), publication.sessionId());
            McpSchema.CallToolResult first = handler.handle(call(
                    "runtime_entity_history", Map.of(
                            "sessionId", DeterministicSimulation.SESSION_ID.value(),
                            "entityId", "enemy-2", "fromFrame", 1, "toFrame", 45,
                            "versionOffset", 0, "versionLimit", 8)))
                    .block(Duration.ofSeconds(5));
            assertNotNull(first);
            assertFalse(first.isError());
            Map<?, ?> content = assertInstanceOf(Map.class, first.structuredContent());
            Map<?, ?> page = assertInstanceOf(Map.class, content.get("page"));
            assertEquals(8, ((List<?>) page.get("versions")).size());
            assertEquals(8L, ((Number) page.get("nextVersionOffset")).longValue());
            assertEquals(true, page.get("hasMoreVersions"));
            assertTrue(page.containsKey("finalRetainedState"));
        }
        fixture.runtime.close();
    }

    private static McpSchema.CallToolRequest call(
            String name, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
    }

    @Test
    void fixtureRecordingCapturesDeterministicFrameAndManifestThroughMcp() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        DeterministicSimulation simulation = new DeterministicSimulation();
        AgentRuntime runtime = simulation.startRuntime(applicationQueue::addLast);
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            Map<String, Object> start = Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "recordingId", "fixture-run",
                    "recordingRequestId", "fixture-record-start",
                    "randomSeed", 99,
                    "configuration", List.of(
                            Map.of("name", "profile", "value", "deterministic")),
                    "replayGuaranteed", false,
                    "timeoutNanos", 1_000_000_000);
            assertFalse(handler.handle(call("runtime_recording_start", start))
                    .block(Duration.ofSeconds(5)).isError());
            applicationQueue.removeFirst().run();
            simulation.advance(runtime, 20);

            Map<String, Object> stop = Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "recordingId", "fixture-run",
                    "recordingRequestId", "fixture-record-stop",
                    "timeoutNanos", 1_000_000_000);
            handler.handle(call("runtime_recording_stop", stop)).block(Duration.ofSeconds(5));
            applicationQueue.removeFirst().run();
            McpSchema.CallToolResult result = handler.handle(call("runtime_recording_get", Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "recordingId", "fixture-run", "offset", 0, "limit", 16)))
                    .block(Duration.ofSeconds(5));
            assertFalse(result.isError());
            assertTrue(result.structuredContent().toString().contains("fixture-run"));
            assertTrue(result.structuredContent().toString().contains("deterministic"));
            assertTrue(result.structuredContent().toString().contains("frameId"));
        }
        runtime.close();
    }

    @Test
    void fixtureDeterminismRepeatsSeededScenarioThroughMcp() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        DeterministicSimulation simulation = new DeterministicSimulation();
        AgentRuntime runtime = simulation.startRuntime(applicationQueue::addLast);
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            Map<String, Object> comparisonScope = Map.of(
                    "entityIds", List.of(),
                    "properties", List.of(),
                    "excludedProperties", List.of(),
                    "includeEvents", true,
                    "includeDecisions", true);
            Map<String, Object> request = Map.of(
                    "sessionId", DeterministicSimulation.SESSION_ID.value(),
                    "determinismRequestId", "fixture-determinism",
                    "scenarioId", "deterministic-fixture",
                    "randomSeed", 99,
                    "configuration", List.of(
                            Map.of("name", "profile", "value", "deterministic")),
                    "repeatCount", 2,
                    "ticksPerRepeat", 45,
                    "deltaNanos", 16_000_000,
                    "profile", Map.of(
                            "comparisonScope", comparisonScope,
                            "includeUiCorrelations", false),
                    "timeoutNanos", 1_000_000_000);
            assertFalse(handler.handle(call("runtime_determinism_check", request))
                    .block(Duration.ofSeconds(5)).isError());
            applicationQueue.removeFirst().run();
            McpSchema.CallToolResult result =
                    handler.handle(call("runtime_determinism_check", request))
                            .block(Duration.ofSeconds(5));
            assertFalse(result.isError());
            assertTrue(result.structuredContent().toString().contains("EQUAL"));
            assertTrue(result.structuredContent().toString().contains("completedRepeats"));
        }
        runtime.close();
    }

    @Test
    void fixtureReportsBoundedFailureTruncationAndEvictionEvidenceThroughMcp() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        DeterministicSimulation simulation = new DeterministicSimulation();
        CommandDispatchLimits limits = new CommandDispatchLimits(
                8, 1, 4, Duration.ofSeconds(1).toNanos(),
                ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY);
        AgentRuntime runtime = simulation.startRuntime(applicationQueue::addLast, limits);
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime);
                RuntimeToolHandler handler =
                        new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            runtime.commands().orElseThrow().submit(
                    "fixture-failure", Duration.ofSeconds(1),
                    () -> {
                        throw new IllegalStateException("fixture callback rejected");
                    });
            applicationQueue.removeFirst().run();
            McpSchema.CallToolResult failed = handler.handle(call(
                    "runtime_command_status", Map.of(
                            "sessionId", DeterministicSimulation.SESSION_ID.value(),
                            "commandRequestId", "fixture-failure")))
                    .block(Duration.ofSeconds(5));
            assertFalse(failed.isError());
            Map<?, ?> failedContent = assertInstanceOf(Map.class, failed.structuredContent());
            Map<?, ?> failedCommand = assertInstanceOf(Map.class, failedContent.get("command"));
            Map<?, ?> failedStatus = assertInstanceOf(Map.class, failedCommand.get("status"));
            assertEquals(CommandState.FAILED.name(), failedStatus.get("state"));
            assertEquals("deterministic-fixture|failure-1|command.failed"
                    + "|java.lang.IllegalStateException", failedStatus.get("diagnostic"));
            String failedJson;
            try {
                failedJson = ProtocolJson.mapper().writeValueAsString(failed.structuredContent());
            } catch (com.fasterxml.jackson.core.JsonProcessingException serializationFailure) {
                throw new AssertionError("structured content must serialize", serializationFailure);
            }
            assertFalse(failedJson.contains("fixture callback rejected"), failedJson);

            runtime.commands().orElseThrow().submit("fixture-timeout", 0, () -> {});
            McpSchema.CallToolResult timedOut = handler.handle(call(
                    "runtime_command_status", Map.of(
                            "sessionId", DeterministicSimulation.SESSION_ID.value(),
                            "commandRequestId", "fixture-timeout")))
                    .block(Duration.ofSeconds(5));
            assertFalse(timedOut.isError());
            assertTrue(timedOut.structuredContent().toString().contains("TIMED_OUT"));

            McpSchema.CallToolResult truncated = handler.handle(call(
                    "runtime_snapshot", Map.of(
                            "sessionId", DeterministicSimulation.SESSION_ID.value(),
                            "frameId", 0,
                            "limit", 1))).block(Duration.ofSeconds(5));
            assertFalse(truncated.isError(), () -> truncated.toString());
            assertTrue(truncated.structuredContent().toString().contains("hasMore=true"));

            McpSchema.CallToolResult inconclusive = handler.handle(call(
                    "runtime_assert", Map.of(
                            "sessionId", DeterministicSimulation.SESSION_ID.value(),
                            "fromFrame", 0, "toFrame", 1, "executionEpochId", 0,
                            "evidenceLimit", 8, "assertion", Map.of(
                                    "assertionType", "eventDoesNotOccur",
                                    "eventType", "missing.fixture.event"))))
                    .block(Duration.ofSeconds(5));
            assertFalse(inconclusive.isError());
            assertTrue(inconclusive.structuredContent().toString().contains("INCONCLUSIVE"));

            runtime.commands().orElseThrow().submit(
                    "fixture-evicted", Duration.ofSeconds(1), () -> {});
            applicationQueue.removeFirst().run();
            runtime.commands().orElseThrow().submit(
                    "fixture-replacement", Duration.ofSeconds(1), () -> {});
            applicationQueue.removeFirst().run();
            McpSchema.CallToolResult evicted = handler.handle(call(
                    "runtime_command_status", Map.of(
                            "sessionId", DeterministicSimulation.SESSION_ID.value(),
                            "commandRequestId", "fixture-evicted")))
                    .block(Duration.ofSeconds(5));
            assertFalse(evicted.isError());
            assertTrue(evicted.structuredContent().toString().contains("EXPIRED"),
                    () -> evicted.toString());
        }
        runtime.close();
    }

    @Test
    @Timeout(10)
    void fixtureControlAndActionCrossTheRealStdioMcpBoundary() throws Exception {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        DeterministicSimulation simulation = new DeterministicSimulation();
        AgentRuntime runtime = simulation.startRuntime(applicationQueue::addLast);
        RuntimeRegistry registry = new RuntimeRegistry();
        Pipe requestPipe = Pipe.open();
        Pipe responsePipe = Pipe.open();
        try (PublishedRuntime publication = registry.publish(runtime);
                InputStream serverInput = Channels.newInputStream(requestPipe.source());
                OutputStream clientOutput = Channels.newOutputStream(requestPipe.sink());
                InputStream clientInput = Channels.newInputStream(responsePipe.source());
                OutputStream serverOutput = Channels.newOutputStream(responsePipe.sink());
                RuntimeMcpServer server = RuntimeMcpServer.open(
                        new RuntimeProtocolService(registry), serverInput, serverOutput);
                BufferedWriter requests = new BufferedWriter(new OutputStreamWriter(
                        clientOutput, StandardCharsets.UTF_8));
                BufferedReader responses = new BufferedReader(new InputStreamReader(
                        clientInput, StandardCharsets.UTF_8))) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            String initialized = exchange(requests, responses,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                            + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                            + "\"clientInfo\":{\"name\":\"fixture-test\",\"version\":\"1\"}}}");
            assertTrue(initialized.contains("\"result\""));
            requests.write(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
            requests.flush();

            String pauseRequest =
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                            + "\"name\":\"runtime_control\",\"arguments\":{"
                            + "\"sessionId\":\"deterministic-fixture\",\"action\":\"PAUSE\","
                            + "\"controlRequestId\":\"stdio-pause\","
                            + "\"timeoutNanos\":1000000000}}}";
            assertTrue(exchange(requests, responses, pauseRequest).contains("QUEUED"));
            applicationQueue.removeFirst().run();
            assertTrue(exchange(requests, responses,
                    pauseRequest.replace("\"id\":2", "\"id\":3")).contains("SUCCEEDED"));

            String actionRequest =
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{"
                            + "\"name\":\"runtime_action\",\"arguments\":{"
                            + "\"sessionId\":\"deterministic-fixture\","
                            + "\"action\":\"set-tower-state\","
                            + "\"actionRequestId\":\"stdio-action\","
                            + "\"correlationId\":\"fixture-action-1\","
                            + "\"parameters\":{\"state\":\"ALERT\"},"
                            + "\"timeoutNanos\":1000000000}}}";
            assertTrue(exchange(requests, responses, actionRequest).contains("QUEUED"));
            applicationQueue.removeFirst().run();
            assertTrue(exchange(requests, responses,
                    actionRequest.replace("\"id\":4", "\"id\":5")).contains("SUCCEEDED"));
            assertEquals(1, simulation.actionExecutions());
            assertEquals(Thread.currentThread(), simulation.lastMutationThread());
            requestPipe.sink().close();
            server.awaitTermination();
        }
        runtime.close();
    }

    private static String exchange(
            BufferedWriter requests, BufferedReader responses, String request) throws IOException {
        requests.write(request);
        requests.newLine();
        requests.flush();
        return responses.readLine();
    }

    private static Fixture fixture() {
        DeterministicSimulation simulation = new DeterministicSimulation();
        AgentRuntime runtime = simulation.startRuntime();
        IntStream.rangeClosed(1, 45).forEach(frame -> simulation.advance(runtime, frame));
        return new Fixture(runtime, new RuntimeRegistry());
    }

    private record Fixture(AgentRuntime runtime, RuntimeRegistry registry) {}
}
