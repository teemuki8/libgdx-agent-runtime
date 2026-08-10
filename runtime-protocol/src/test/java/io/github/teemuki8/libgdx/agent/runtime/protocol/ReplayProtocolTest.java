package io.github.teemuki8.libgdx.agent.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismProfile;
import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationConfigurationRequirement;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationControllerSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTimelineSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SnapshotComparisonScope;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ReplayProtocolTest {
    @Test
    void protocolTwoFiveRoundTripsStartsAndExecutesReplay() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("replay-protocol"))
                .clock(() -> 1).commandDispatcher(queue::addLast).build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(16));
        runtime.entities().register(EntityId.of("world"), EntityType.of("state"),
                () -> "world", inspector -> inspector
                        .property("step", () -> 16L)
                        .property("position", () -> position[0]));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).acknowledgedTick(delta -> delta).build());
        runtime.scenarios().register("origin", context -> position[0] = 0);
        runtime.start();
        runtime.controls().control(true, "pause-replay-protocol", Duration.ofSeconds(1));
        queue.removeFirst().run();
        RuntimeRegistry registry = new RuntimeRegistry();
        registry.publish(runtime);
        RuntimeProtocolService service = new RuntimeProtocolService(registry);

        RuntimeResponse.Result.Capabilities capabilities = assertInstanceOf(
                RuntimeResponse.Result.Capabilities.class,
                success(service.execute(new RuntimeRequest(ProtocolVersion.V2_5, "capabilities",
                        "replay-protocol", new RuntimeCommand.Capabilities()))).result());
        assertTrue(capabilities.supportedTools().contains("runtime_replay"));
        RuntimeCapability replayCapability = capabilities.capabilityReport().orElseThrow()
                .capabilities().stream().filter(value -> value.id().equals("replay-execution"))
                .findFirst().orElseThrow();
        assertEquals(ProtocolVersion.V2_5, replayCapability.capabilityVersion());
        assertTrue(replayCapability.modes().contains("exact-fixed-tick"));

        RuntimeCommand.ReplayRecordingStart start = startCommand();
        RuntimeRequest startRequest = new RuntimeRequest(
                ProtocolVersion.V2_5, "start", "replay-protocol", start);
        assertEquals(startRequest,
                ProtocolJson.decodeRequest(ProtocolJson.encode(startRequest)));
        assertInstanceOf(RuntimeResponse.Result.ReplayCapture.class,
                success(service.execute(startRequest)).result());
        queue.removeFirst().run();
        runtime.recordings().stop("recording", "stop-recording", Duration.ofSeconds(1));
        queue.removeFirst().run();

        RuntimeCommand.Replay replay = new RuntimeCommand.Replay(
                "recording", "execute-recording", Duration.ofSeconds(1).toNanos());
        assertInstanceOf(RuntimeResponse.Result.Replay.class,
                success(service.execute(new RuntimeRequest(
                        ProtocolVersion.V2_5, "execute", "replay-protocol", replay))).result());
        queue.removeFirst().run();
        RuntimeResponse response = service.execute(new RuntimeRequest(
                ProtocolVersion.V2_5, "poll", "replay-protocol", replay));
        RuntimeResponse decoded = ProtocolJson.decodeResponse(ProtocolJson.encode(response));
        RuntimeResponse.Result.Replay result = assertInstanceOf(
                RuntimeResponse.Result.Replay.class,
                assertInstanceOf(RuntimeResponse.Success.class, decoded).result());
        assertEquals(DeterminismStatus.EQUAL,
                result.operation().result().orElseThrow().status());
    }

    @Test
    void protocolTwoFourAndUnknownReplayFieldsAreRejected() {
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("replay-version")).build();
        runtime.start();
        registry.publish(runtime);
        RuntimeResponse.Failure old = assertInstanceOf(RuntimeResponse.Failure.class,
                new RuntimeProtocolService(registry).execute(new RuntimeRequest(
                        ProtocolVersion.V2_4, "old", "replay-version", startCommand())));
        assertEquals(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED, old.error().code());
        assertEquals("command requires protocol version 2.5", old.error().message());

        String encoded = new String(ProtocolJson.encode(new RuntimeRequest(
                ProtocolVersion.V2_5, "closed", "replay-version", startCommand())),
                StandardCharsets.UTF_8);
        String unknown = encoded.replace("\"timeoutNanos\":1000000000",
                "\"timeoutNanos\":1000000000,\"unknown\":true");
        assertThrows(ProtocolJson.ProtocolJsonException.class,
                () -> ProtocolJson.decodeRequest(unknown.getBytes(StandardCharsets.UTF_8)));

        RuntimeCommand.ReplayRecordingStart valid = startCommand();
        assertThrows(IllegalArgumentException.class, () -> new RuntimeCommand.ReplayRecordingStart(
                valid.recordingId(), valid.replayRequestId(), null, null, valid.randomSeed(),
                valid.configuration(), valid.profile(), valid.configurationRequirements(),
                valid.evidenceRequirements(), valid.eventTypes(), valid.timeoutNanos()));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeCommand.ReplayRecordingStart(
                valid.recordingId(), valid.replayRequestId(), "origin", "checkpoint",
                valid.randomSeed(), valid.configuration(), valid.profile(),
                valid.configurationRequirements(), valid.evidenceRequirements(),
                valid.eventTypes(), valid.timeoutNanos()));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeCommand.ReplayRecordingStart(
                valid.recordingId(), valid.replayRequestId(), "origin", null,
                valid.randomSeed(), RuntimeValues.object(RuntimeValues.field(
                        "nested", RuntimeValues.list(RuntimeValues.integer(1)))),
                valid.profile(), valid.configurationRequirements(), valid.evidenceRequirements(),
                valid.eventTypes(), valid.timeoutNanos()));
        List<SimulationConfigurationRequirement> oversized = new java.util.AbstractList<>() {
            @Override public SimulationConfigurationRequirement get(int index) {
                throw new AssertionError("oversized selector list must not be traversed");
            }

            @Override public int size() {
                return io.github.teemuki8.libgdx.agent.runtime.core.ReplayCaptureSpec
                        .MAX_CONFIGURATION_REQUIREMENTS + 1;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new RuntimeCommand.ReplayRecordingStart(
                valid.recordingId(), valid.replayRequestId(), "origin", null,
                valid.randomSeed(), valid.configuration(), valid.profile(), oversized,
                valid.evidenceRequirements(), valid.eventTypes(), valid.timeoutNanos()));

        RuntimeCommand.ReplayRecordingStart checkpoint = new RuntimeCommand.ReplayRecordingStart(
                "checkpoint-recording", "checkpoint-start", null, "checkpoint", null,
                valid.configuration(), valid.profile(), valid.configurationRequirements(),
                valid.evidenceRequirements(), valid.eventTypes(), valid.timeoutNanos());
        RuntimeRequest checkpointRequest = new RuntimeRequest(
                ProtocolVersion.V2_5, "checkpoint", "replay-version", checkpoint);
        assertEquals(checkpointRequest,
                ProtocolJson.decodeRequest(ProtocolJson.encode(checkpointRequest)));
    }

    private static RuntimeCommand.ReplayRecordingStart startCommand() {
        return new RuntimeCommand.ReplayRecordingStart("recording", "start-recording",
                "origin", null, 7L, RuntimeValues.object(),
                new DeterminismProfile(new SnapshotComparisonScope(
                        List.of(EntityId.of("world")), List.of("position"), List.of(),
                        false, false), false),
                List.of(new SimulationConfigurationRequirement(
                        EntityId.of("world"), "step", RuntimeValues.integer(16))),
                List.of(), List.of(), Duration.ofSeconds(1).toNanos());
    }

    private static RuntimeResponse.Success success(RuntimeResponse response) {
        return assertInstanceOf(RuntimeResponse.Success.class, response);
    }
}
