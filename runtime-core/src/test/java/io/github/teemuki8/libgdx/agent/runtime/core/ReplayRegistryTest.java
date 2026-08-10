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
        assertTrue(runtime.replays().retainedReplay("incomplete-baseline-recording")
                .orElseThrow().incompleteReason().isPresent());
    }

    @Test
    void freezesStableAcceptedInputOrderAgainstContiguousEpochTicks() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = runtime(queue, position);
        runtime.inputs().register(InputSpec.builder("move")
                .requiredInteger("amount")
                .handler(parameters -> position[0] += parameters.requiredInteger("amount"))
                .build());
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("ordered-inputs", "ball-drop"),
                "start-ordered-inputs", TIMEOUT);
        queue.removeFirst().run();

        inject(runtime, queue, "move-first", 2, 1);
        inject(runtime, queue, "move-second", 3, 1);
        advance(runtime, queue, "advance-one");
        advance(runtime, queue, "advance-two");
        inject(runtime, queue, "move-third", 5, 3);
        advance(runtime, queue, "advance-three");
        runtime.recordings().stop("ordered-inputs", "stop-ordered-inputs", TIMEOUT);
        queue.removeFirst().run();

        ReplayRegistry.RetainedReplay replay = runtime.replays()
                .retainedReplay("ordered-inputs").orElseThrow();
        assertTrue(replay.incompleteReason().isEmpty());
        assertEquals(3, replay.ticks().size());
        assertEquals(List.of(1L, 1L, 3L), replay.inputs().stream()
                .map(SimulationDeterminismInput::epochTick).toList());
        assertEquals(List.of(2L, 3L, 5L), replay.inputs().stream()
                .map(input -> ((RuntimeValue.IntegerValue) input.parameters()
                        .fields().get(0).value()).value()).toList());
        assertEquals(13, position[0]);
    }

    @Test
    void freezesFirstIncompleteReasonForActionAndRunningTick() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = runtime(queue, position);
        runtime.actions().register(ActionSpec.builder("teleport")
                .handler(parameters -> position[0] = 99)
                .build());
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("action-recording", "ball-drop"),
                "start-action-recording", TIMEOUT);
        queue.removeFirst().run();

        runtime.actions().invoke("teleport", "teleport-action", RuntimeValues.object(),
                Optional.empty(), TIMEOUT);
        queue.removeFirst().run();
        runtime.controls().control(false, "resume-action-recording", TIMEOUT);
        queue.removeFirst().run();
        runtime.simulation().tick(STEP, delta -> delta);
        runtime.recordings().stop("action-recording", "stop-action-recording", TIMEOUT);
        queue.removeFirst().run();

        ReplayRegistry.RetainedReplay replay = runtime.replays()
                .retainedReplay("action-recording").orElseThrow();
        assertEquals(Optional.of("semantic action is not replayable"),
                replay.incompleteReason());
        assertTrue(replay.ticks().isEmpty());
    }

    @Test
    void redactedInputNeverEntersReplayScript() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = runtime(queue, position);
        runtime.inputs().register(InputSpec.builder("secret-move")
                .requiredInteger("amount")
                .redaction(InputRedactionPolicy.OMIT_PARAMETERS)
                .handler(parameters -> position[0] += parameters.requiredInteger("amount"))
                .build());
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("redacted-input", "ball-drop"),
                "start-redacted-input", TIMEOUT);
        queue.removeFirst().run();

        runtime.inputs().inject("secret-move", "secret-request", RuntimeValues.object(
                        RuntimeValues.field("amount", RuntimeValues.integer(4))),
                OptionalLong.of(1), TIMEOUT);
        queue.removeFirst().run();
        advance(runtime, queue, "advance-redacted");
        runtime.recordings().stop("redacted-input", "stop-redacted-input", TIMEOUT);
        queue.removeFirst().run();

        ReplayRegistry.RetainedReplay replay = runtime.replays()
                .retainedReplay("redacted-input").orElseThrow();
        assertTrue(replay.inputs().isEmpty());
        assertEquals(Optional.of("input did not complete with replayable parameters"),
                replay.incompleteReason());
    }

    @Test
    void inputRejectedBeforeAcceptanceDoesNotPolluteReplayEvidence() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = runtime(queue, position);
        runtime.inputs().register(InputSpec.builder("move")
                .requiredInteger("amount")
                .handler(parameters -> position[0] += parameters.requiredInteger("amount"))
                .build());
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("rejected-input", "ball-drop"),
                "start-rejected-input", TIMEOUT);
        queue.removeFirst().run();

        assertThrows(IllegalArgumentException.class, () -> runtime.inputs().inject(
                "move", "invalid-input", RuntimeValues.object(
                        RuntimeValues.field("amount", RuntimeValues.string("invalid"))),
                OptionalLong.of(1), TIMEOUT));
        advance(runtime, queue, "advance-after-rejection");
        runtime.recordings().stop("rejected-input", "stop-rejected-input", TIMEOUT);
        queue.removeFirst().run();

        ReplayRegistry.RetainedReplay replay = runtime.replays()
                .retainedReplay("rejected-input").orElseThrow();
        assertTrue(replay.incompleteReason().isEmpty());
        assertTrue(replay.inputs().isEmpty());
        assertEquals(0, replay.observedInputs());
    }

    @Test
    void failedInputAndTickRemainExplicitlyIncomplete() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = runtime(queue, position);
        runtime.inputs().register(InputSpec.builder("explode")
                .handler(parameters -> {
                    throw new IllegalStateException("private input token");
                })
                .build());
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("failed-input", "ball-drop"),
                "start-failed-input", TIMEOUT);
        queue.removeFirst().run();

        runtime.inputs().inject("explode", "explode-input", RuntimeValues.object(),
                OptionalLong.of(1), TIMEOUT);
        queue.removeFirst().run();
        advance(runtime, queue, "advance-failed-input");
        runtime.recordings().stop("failed-input", "stop-failed-input", TIMEOUT);
        queue.removeFirst().run();

        ReplayRegistry.RetainedReplay replay = runtime.replays()
                .retainedReplay("failed-input").orElseThrow();
        assertTrue(replay.inputs().isEmpty());
        assertEquals(1, replay.observedInputs());
        assertEquals(1, replay.observedTicks());
        assertEquals(Optional.of(
                "simulation tick is not an acknowledged fixed-step completion"),
                replay.incompleteReason());
    }

    @Test
    void tickLimitRetainsBoundedPrefixAndExplicitIncompleteness() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = runtimeBuilder(queue, position)
                .replayLimits(new ReplayLimits(8, 8, 1, 32, 128,
                        65_536, Duration.ofSeconds(1).toNanos()))
                .build();
        registerRuntimeCapabilities(runtime, position);
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("tick-bounded", "ball-drop"),
                "start-tick-bounded", TIMEOUT);
        queue.removeFirst().run();

        advance(runtime, queue, "advance-bounded-one");
        advance(runtime, queue, "advance-bounded-two");
        runtime.recordings().stop("tick-bounded", "stop-tick-bounded", TIMEOUT);
        queue.removeFirst().run();

        ReplayRegistry.RetainedReplay replay = runtime.replays()
                .retainedReplay("tick-bounded").orElseThrow();
        assertEquals(1, replay.ticks().size());
        assertEquals(2, replay.observedTicks());
        assertEquals(Optional.of("replay tick limit exceeded"), replay.incompleteReason());
    }

    @Test
    void inputLimitRetainsFirstAcceptedInputWithoutGrowingTheCaptureMap() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = runtimeBuilder(queue, position)
                .replayLimits(new ReplayLimits(8, 1, 8, 32, 128,
                        65_536, Duration.ofSeconds(1).toNanos()))
                .build();
        registerRuntimeCapabilities(runtime, position);
        runtime.inputs().register(InputSpec.builder("move")
                .requiredInteger("amount")
                .handler(parameters -> position[0] += parameters.requiredInteger("amount"))
                .build());
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("input-bounded", "ball-drop"),
                "start-input-bounded", TIMEOUT);
        queue.removeFirst().run();

        inject(runtime, queue, "bounded-first", 2, 1);
        inject(runtime, queue, "bounded-second", 3, 1);
        advance(runtime, queue, "advance-input-bounded");
        runtime.recordings().stop("input-bounded", "stop-input-bounded", TIMEOUT);
        queue.removeFirst().run();

        ReplayRegistry.RetainedReplay replay = runtime.replays()
                .retainedReplay("input-bounded").orElseThrow();
        assertEquals(1, replay.inputs().size());
        assertEquals(2, replay.observedInputs());
        assertEquals(Optional.of("replay input limit exceeded"), replay.incompleteReason());
    }

    @Test
    void recordingEvictionEvictsReplaySidecarWithBoundedTestimony() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = runtimeBuilder(queue, position)
                .recordingLimits(new RecordingLimits(1, 8, 32, 32,
                        Duration.ofSeconds(1).toNanos(), 65_536, 16, 128))
                .build();
        registerRuntimeCapabilities(runtime, position);
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);

        captureAndStop(runtime, queue, "first-replay");
        captureAndStop(runtime, queue, "second-replay");

        assertTrue(runtime.replays().retainedReplay("first-replay").isEmpty());
        assertTrue(runtime.replays().replayEvicted("first-replay"));
        assertTrue(runtime.replays().retainedReplay("second-replay").isPresent());
        assertFalse(runtime.replays().replayEvicted("second-replay"));
    }

    private static AgentRuntime runtime(ArrayDeque<Runnable> queue, long[] position) {
        AgentRuntime runtime = runtimeBuilder(queue, position).build();
        registerRuntimeCapabilities(runtime, position);
        return runtime;
    }

    private static AgentRuntime.Builder runtimeBuilder(
            ArrayDeque<Runnable> queue, long[] position) {
        return AgentRuntime.builder()
                .sessionId(SessionId.of("replay-test"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast);
    }

    private static void registerRuntimeCapabilities(AgentRuntime runtime, long[] position) {
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
    }

    private static void captureAndStop(
            AgentRuntime runtime, ArrayDeque<Runnable> queue, String recordingId) {
        runtime.replays().start(scenarioSpec(recordingId, "ball-drop"),
                "start-" + recordingId, TIMEOUT);
        queue.removeFirst().run();
        runtime.recordings().stop(recordingId, "stop-" + recordingId, TIMEOUT);
        queue.removeFirst().run();
    }

    private static void pause(AgentRuntime runtime, ArrayDeque<Runnable> queue) {
        runtime.controls().control(true, "pause-" + runtime.currentEpoch().value(), TIMEOUT);
        queue.removeFirst().run();
        assertTrue(runtime.controls().paused());
    }

    private static void inject(AgentRuntime runtime, ArrayDeque<Runnable> queue,
            String requestId, long amount, long targetTick) {
        runtime.inputs().inject("move", requestId, RuntimeValues.object(
                        RuntimeValues.field("amount", RuntimeValues.integer(amount))),
                OptionalLong.of(targetTick), TIMEOUT);
        queue.removeFirst().run();
    }

    private static void advance(
            AgentRuntime runtime, ArrayDeque<Runnable> queue, String requestId) {
        runtime.controls().advanceFixed(requestId, 1, TIMEOUT);
        queue.removeFirst().run();
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
