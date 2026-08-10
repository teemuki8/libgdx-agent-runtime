package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

final class ReplayRegistryTest {
    private static final long STEP = 16_666_667L;
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @Test
    void startsScenarioReplayCaptureFromExactSeedConfigurationAndFixedStep() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {99};
        ScenarioResetContext[] observedContext = {null};
        int[] resets = {0};
        AgentRuntime runtime = runtime(queue, position);
        runtime.scenarios().register("ball-drop", context -> {
            observedContext[0] = context;
            resets[0]++;
            position[0] = context.randomSeed().orElseThrow();
        });
        runtime.start();
        pause(runtime, queue);
        ReplayCaptureSpec spec = scenarioSpec("scenario-recording", "ball-drop");

        ReplayCaptureOperation submitted = runtime.replays().start(
                spec, "replay-capture-scenario", TIMEOUT);

        assertEquals(CommandState.QUEUED,
                submitted.command().status().orElseThrow().state());
        assertTrue(submitted.baselineExecutionEpochId().isEmpty());
        queue.removeFirst().run();
        ReplayCaptureOperation completed = runtime.replays().start(
                spec, "replay-capture-scenario", TIMEOUT);

        assertEquals(CommandState.SUCCEEDED,
                completed.command().status().orElseThrow().state());
        assertEquals(new ExecutionEpochId(1),
                completed.baselineExecutionEpochId().orElseThrow());
        assertEquals(new FrameId(1), completed.baselineFrameId().orElseThrow());
        assertEquals(OptionalLong.of(7), observedContext[0].randomSeed());
        assertEquals(spec.recording().configuration(), observedContext[0].configuration());
        assertEquals(7, position[0]);
        assertEquals(0, runtime.simulation().state().attemptedEpochTicks());
        assertEquals(STEP,
                runtime.simulation().state().configuredFixedStepNanos().orElseThrow());
        assertEquals(1, resets[0]);
        assertEquals(ReplayLimits.developmentDefaults(), runtime.replays().limits());

        ReplayCaptureOperation duplicate = runtime.replays().start(
                spec, "replay-capture-scenario", TIMEOUT);
        assertEquals(CommandState.SUCCEEDED,
                duplicate.command().status().orElseThrow().state());
        assertEquals(1, resets[0]);
        assertTrue(queue.isEmpty());

        runtime.recordings().stop("scenario-recording", "stop-scenario-recording", TIMEOUT);
        queue.removeFirst().run();
        RecordingMetadata metadata = runtime.recordings()
                .get("scenario-recording", 0, 8).metadata();
        assertEquals(1, metadata.schemaVersion());
        assertEquals(new ExecutionEpochId(1), metadata.startedExecutionEpochId());
        assertEquals(Optional.of("ball-drop"), metadata.scenarioId());
        assertTrue(metadata.replayGuaranteed());
    }

    @Test
    void startsCheckpointReplayCaptureThroughRetainedOpaqueProvider() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {5};
        int[] restores = {0};
        AgentRuntime runtime = runtime(queue, position);
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override
            public CheckpointHandle create() {
                return new PositionHandle(position[0]);
            }

            @Override
            public void restore(CheckpointHandle handle) {
                restores[0]++;
                position[0] = ((PositionHandle) handle).position();
            }

            @Override
            public void dispose(CheckpointHandle handle) {
                // The test provider owns no native resource.
            }
        });
        runtime.start();
        runtime.checkpoints().create(
                "before-drop", "before drop", "checkpoint-create", TIMEOUT);
        queue.removeFirst().run();
        position[0] = 99;
        pause(runtime, queue);
        ReplayCaptureSpec spec = checkpointSpec("checkpoint-recording", "before-drop");

        runtime.replays().start(spec, "replay-capture-checkpoint", TIMEOUT);
        queue.removeFirst().run();
        ReplayCaptureOperation completed = runtime.replays().start(
                spec, "replay-capture-checkpoint", TIMEOUT);

        assertEquals(CommandState.SUCCEEDED,
                completed.command().status().orElseThrow().state());
        assertEquals(new ExecutionEpochId(1),
                completed.baselineExecutionEpochId().orElseThrow());
        assertEquals(BaselineKind.CHECKPOINT_RESTORE, runtime.latestFrame().orElseThrow()
                .baselineKind().orElseThrow());
        assertEquals(5, position[0]);
        assertEquals(1, restores[0]);

        runtime.recordings().stop("checkpoint-recording", "stop-checkpoint-recording", TIMEOUT);
        queue.removeFirst().run();
        RecordingMetadata metadata = runtime.recordings()
                .get("checkpoint-recording", 0, 8).metadata();
        assertEquals(Optional.of("before-drop"), metadata.checkpointId());
        assertEquals(OptionalLong.of(7), metadata.randomSeed());
        assertEquals(spec.recording().configuration(), metadata.configuration());
    }

    @Test
    void rejectsUnpausedOrQueuedInputBeforeRetainingReplayCommand() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = runtime(queue, position);
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.inputs().register(InputSpec.builder("move")
                .requiredInteger("amount")
                .handler(parameters -> position[0] += parameters.requiredInteger("amount"))
                .build());
        runtime.start();
        ReplayCaptureSpec spec = scenarioSpec("rejected-recording", "ball-drop");

        AgentRuntimeException running = assertThrows(AgentRuntimeException.class,
                () -> runtime.replays().start(spec, "replay-running", TIMEOUT));
        assertEquals(RuntimeErrorCode.INVALID_LIFECYCLE, running.code());
        assertTrue(queue.isEmpty());

        pause(runtime, queue);
        runtime.inputs().inject("move", "queued-move", RuntimeValues.object(
                        RuntimeValues.field("amount", RuntimeValues.integer(1))),
                OptionalLong.empty(), TIMEOUT);
        queue.removeFirst().run();
        AgentRuntimeException queuedInput = assertThrows(AgentRuntimeException.class,
                () -> runtime.replays().start(spec, "replay-queued-input", TIMEOUT));
        assertEquals(RuntimeErrorCode.INVALID_LIFECYCLE, queuedInput.code());
        assertTrue(queue.isEmpty());
    }

    @Test
    void failedOriginResetIsRetainedOnceWithoutActivatingRecording() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        int[] resets = {0};
        AgentRuntime runtime = runtime(queue, position);
        runtime.scenarios().register("failed", context -> {
            resets[0]++;
            throw new IllegalStateException("private reset token");
        });
        runtime.start();
        pause(runtime, queue);
        ReplayCaptureSpec spec = scenarioSpec("failed-recording", "failed");

        runtime.replays().start(spec, "replay-failed-origin", TIMEOUT);
        queue.removeFirst().run();
        ReplayCaptureOperation failed = runtime.replays().start(
                spec, "replay-failed-origin", TIMEOUT);

        assertEquals(CommandState.FAILED,
                failed.command().status().orElseThrow().state());
        assertTrue(failed.baselineFrameId().isEmpty());
        assertEquals(1, resets[0]);
        assertFalse(failed.command().status().orElseThrow().diagnostic()
                .orElseThrow().contains("private reset token"));
        assertTrue(failed.command().status().orElseThrow().applicationFailure().isPresent());
        assertThrows(AgentRuntimeException.class,
                () -> runtime.recordings().get("failed-recording", 0, 8));

        ReplayCaptureOperation duplicate = runtime.replays().start(
                spec, "replay-failed-origin", TIMEOUT);
        assertEquals(CommandState.FAILED,
                duplicate.command().status().orElseThrow().state());
        assertEquals(1, resets[0]);
        assertTrue(queue.isEmpty());
    }

    @Test
    void successfulOriginWithIncompleteBaselineStillStartsAnIncompleteReplayCapture() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        boolean[] broken = {false};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("replay-incomplete-baseline"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP));
        runtime.entities().register(EntityId.of("world"), EntityType.of("state"),
                () -> "World", inspector -> inspector
                        .property("fixedStepNanos", () -> STEP)
                        .property("position", (java.util.function.LongSupplier) () -> {
                            if (broken[0]) {
                                throw new IllegalStateException("baseline unavailable");
                            }
                            return 0;
                        }));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).acknowledgedTick(delta -> delta).build());
        runtime.scenarios().register("broken-baseline", context -> broken[0] = true);
        runtime.start();
        pause(runtime, queue);
        ReplayCaptureSpec spec = scenarioSpec(
                "incomplete-baseline-recording", "broken-baseline");

        runtime.replays().start(spec, "replay-incomplete-baseline", TIMEOUT);
        queue.removeFirst().run();
        ReplayCaptureOperation completed = runtime.replays().start(
                spec, "replay-incomplete-baseline", TIMEOUT);

        assertEquals(CommandState.SUCCEEDED,
                completed.command().status().orElseThrow().state());
        assertTrue(completed.baselineFrameId().isPresent());
        runtime.recordings().stop(
                "incomplete-baseline-recording", "stop-incomplete-baseline", TIMEOUT);
        queue.removeFirst().run();
        assertEquals("incomplete-baseline-recording", runtime.recordings()
                .get("incomplete-baseline-recording", 0, 8).metadata().recordingId());
    }

    private static AgentRuntime runtime(ArrayDeque<Runnable> queue, long[] position) {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("replay-test"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP));
        runtime.entities().register(EntityId.of("world"), EntityType.of("state"),
                () -> "World", inspector -> inspector
                        .property("fixedStepNanos", () -> STEP)
                        .property("position", () -> position[0]));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(delta -> {
                    position[0]++;
                    return delta;
                })
                .build());
        return runtime;
    }

    private static void pause(AgentRuntime runtime, ArrayDeque<Runnable> queue) {
        runtime.controls().control(true, "pause-" + runtime.currentEpoch().value(), TIMEOUT);
        queue.removeFirst().run();
        assertTrue(runtime.controls().paused());
    }

    private static ReplayCaptureSpec scenarioSpec(String recordingId, String scenarioId) {
        return spec(recordingId, Optional.of(scenarioId), Optional.empty());
    }

    private static ReplayCaptureSpec checkpointSpec(String recordingId, String checkpointId) {
        return spec(recordingId, Optional.empty(), Optional.of(checkpointId));
    }

    private static ReplayCaptureSpec spec(String recordingId, Optional<String> scenarioId,
            Optional<String> checkpointId) {
        RecordingSpec recording = new RecordingSpec(recordingId, "2.5", List.of(),
                scenarioId, checkpointId, OptionalLong.of(7), RuntimeValues.object(
                        RuntimeValues.field("difficulty", RuntimeValues.integer(3))), true);
        DeterminismProfile profile = new DeterminismProfile(new SnapshotComparisonScope(
                List.of(EntityId.of("world")), List.of("position"), List.of(), false, false),
                false);
        return new ReplayCaptureSpec(recording, profile, List.of(
                new SimulationConfigurationRequirement(EntityId.of("world"),
                        "fixedStepNanos", RuntimeValues.integer(STEP))), List.of(), List.of());
    }

    private record PositionHandle(long position) implements CheckpointHandle {}
}
