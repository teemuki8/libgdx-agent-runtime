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
import java.util.concurrent.atomic.AtomicLong;
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
    void rejectsProvablyOverLimitSelectorsBeforeOriginMutation() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        int[] resets = {0};
        AgentRuntime runtime = runtimeBuilder(queue, position)
                .replayLimits(new ReplayLimits(8, 8, 8, 1, 1,
                        65_536, Duration.ofSeconds(1).toNanos()))
                .build();
        registerRuntimeCapabilities(runtime, position);
        runtime.entities().register(EntityId.of("other"), EntityType.of("state"),
                () -> "Other", inspector -> inspector.property("velocity", () -> 0));
        runtime.scenarios().register("ball-drop", context -> {
            resets[0]++;
            position[0] = 0;
        });
        runtime.start();
        pause(runtime, queue);
        RecordingSpec recording = scenarioSpec("over-limit", "ball-drop").recording();
        ReplayCaptureSpec tooManyEntities = new ReplayCaptureSpec(recording,
                new DeterminismProfile(new SnapshotComparisonScope(
                        List.of(EntityId.of("world"), EntityId.of("other")),
                        List.of("position"), List.of(), false, false), false),
                List.of(), List.of(), List.of());
        ReplayCaptureSpec tooManyFacts = new ReplayCaptureSpec(recording,
                new DeterminismProfile(new SnapshotComparisonScope(
                        List.of(EntityId.of("world")),
                        List.of("fixedStepNanos", "position"), List.of(), false, false), false),
                List.of(), List.of(), List.of());

        AgentRuntimeException entityLimit = assertThrows(AgentRuntimeException.class,
                () -> runtime.replays().start(
                        tooManyEntities, "start-over-limit-entities", TIMEOUT));
        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED, entityLimit.code());
        AgentRuntimeException factLimit = assertThrows(AgentRuntimeException.class,
                () -> runtime.replays().start(
                        tooManyFacts, "start-over-limit-facts", TIMEOUT));
        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED, factLimit.code());
        assertEquals(0, resets[0]);
        assertEquals(new ExecutionEpochId(0), runtime.currentEpoch());
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

        runtime.replays().execute(
                "redacted-input", "execute-redacted-input", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult result = runtime.replays().execute(
                "redacted-input", "execute-redacted-input", TIMEOUT)
                .result().orElseThrow();
        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        assertEquals(replay.incompleteReason().orElseThrow(), result.message());
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

        runtime.replays().execute("first-replay", "execute-evicted-replay", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult evicted = runtime.replays().execute(
                "first-replay", "execute-evicted-replay", TIMEOUT)
                .result().orElseThrow();
        assertEquals(DeterminismStatus.INCONCLUSIVE, evicted.status());
        assertTrue(evicted.message().contains("evicted"));
    }

    @Test
    void automaticallyTruncatedRecordingsCannotReportEqualReplay() {
        assertAutomaticStopIsInconclusive(new RecordingLimits(4, 8, 32, 32,
                        1, 65_536, 16, 128), 1,
                RecordingStopReason.DURATION_LIMIT, "duration");
        assertAutomaticStopIsInconclusive(new RecordingLimits(4, 8, 1, 32,
                        Duration.ofSeconds(1).toNanos(), 65_536, 16, 128), 1,
                RecordingStopReason.ITEM_LIMIT, "items");
        assertAutomaticStopIsInconclusive(new RecordingLimits(4, 8, 32, 1,
                        Duration.ofSeconds(1).toNanos(), 65_536, 16, 128), 2,
                RecordingStopReason.TICK_SPAN_LIMIT, "tick-span");
        assertAutomaticStopIsInconclusive(new RecordingLimits(4, 8, 32, 32,
                        Duration.ofSeconds(1).toNanos(), 512, 16, 128), 4,
                RecordingStopReason.ENCODED_SIZE_LIMIT, "encoded-size");
    }

    @Test
    void actionOrInputTriggeredAutoStopCannotFreezeCompleteReplay() {
        assertCommandTriggeredAutoStopIsInconclusive(true);
        assertCommandTriggeredAutoStopIsInconclusive(false);
    }

    @Test
    void epochTransitionDuringCaptureMakesReplayInconclusiveWithoutAnotherTick() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = runtime(queue, position);
        runtime.scenarios().register("origin", context -> position[0] = 0);
        runtime.scenarios().register("other", context -> position[0] = 99);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("epoch-change", "origin"),
                "start-epoch-change", TIMEOUT);
        queue.removeFirst().run();

        runtime.scenarios().reset("other", "reset-other", TIMEOUT);
        queue.removeFirst().run();
        runtime.recordings().stop("epoch-change", "stop-epoch-change", TIMEOUT);
        queue.removeFirst().run();

        runtime.replays().execute("epoch-change", "execute-epoch-change", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult result = runtime.replays().execute(
                "epoch-change", "execute-epoch-change", TIMEOUT).result().orElseThrow();

        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        assertTrue(result.message().contains("epoch"));
    }

    @Test
    void uiCorrelationEvictionDuringExecutionMakesReplayInconclusive() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        int[] resets = {0};
        AgentRuntime runtime = runtimeBuilder(queue, position)
                .uiCorrelationLimits(new UiCorrelationLimits(8, 8, 1, 64))
                .build();
        registerRuntimeCapabilities(runtime, position);
        runtime.scenarios().register("ui-origin", context -> {
            position[0] = 0;
            resets[0]++;
            runtime.uiCorrelations().recordFrame(new UiFrameCorrelation(
                    runtime.currentEpoch(), new FrameId(resets[0]), "ui",
                    Optional.of("ui-" + resets[0]), Optional.empty()));
        });
        runtime.start();
        pause(runtime, queue);
        ReplayCaptureSpec base = scenarioSpec("ui-eviction", "ui-origin");
        ReplayCaptureSpec spec = new ReplayCaptureSpec(base.recording(),
                new DeterminismProfile(base.profile().comparisonScope(), true),
                base.configurationRequirements(), base.evidenceRequirements(), base.eventTypes());
        runtime.replays().start(spec, "start-ui-eviction", TIMEOUT);
        queue.removeFirst().run();
        runtime.recordings().stop("ui-eviction", "stop-ui-eviction", TIMEOUT);
        queue.removeFirst().run();

        runtime.replays().execute("ui-eviction", "execute-ui-eviction", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult result = runtime.replays().execute(
                "ui-eviction", "execute-ui-eviction", TIMEOUT).result().orElseThrow();

        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        assertTrue(result.message().contains("UI correlation evidence was evicted"));
    }

    @Test
    void executesScenarioReplayWithExactInputsAndTicks() {
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
        runtime.replays().start(scenarioSpec("equal-replay", "ball-drop"),
                "start-equal-replay", TIMEOUT);
        queue.removeFirst().run();
        inject(runtime, queue, "equal-move", 5, 1);
        advance(runtime, queue, "equal-advance-one");
        advance(runtime, queue, "equal-advance-two");
        runtime.recordings().stop("equal-replay", "stop-equal-replay", TIMEOUT);
        queue.removeFirst().run();

        ReplayOperation submitted = runtime.replays().execute(
                "equal-replay", "execute-equal-replay", TIMEOUT);
        assertEquals(CommandState.QUEUED,
                submitted.command().status().orElseThrow().state());
        assertTrue(submitted.result().isEmpty());
        AgentRuntimeException pending = assertThrows(AgentRuntimeException.class,
                () -> runtime.replays().execute(
                        "equal-replay", "execute-equal-replay-again", TIMEOUT));
        assertEquals(RuntimeErrorCode.INVALID_LIFECYCLE, pending.code());
        assertThrows(IllegalArgumentException.class, () -> runtime.replays().execute(
                "different-recording", "execute-equal-replay", TIMEOUT));
        queue.removeFirst().run();
        ReplayOperation completed = runtime.replays().execute(
                "equal-replay", "execute-equal-replay", TIMEOUT);

        ReplayResult result = completed.result().orElseThrow();
        assertEquals(CommandState.SUCCEEDED,
                completed.command().status().orElseThrow().state());
        assertEquals(DeterminismStatus.EQUAL, result.status());
        assertTrue(result.profile().isPresent());
        assertTrue(result.divergence().isEmpty());
        assertEquals(2, result.bounds().requestedTicks());
        assertEquals(2, result.bounds().completedTicks());
        assertEquals(1, result.bounds().recordedInputs());
        assertEquals(new ExecutionEpochId(2), runtime.currentEpoch());
        assertEquals(7, position[0]);
        assertTrue(runtime.replays().retainedReplay("equal-replay").isPresent());
    }

    @Test
    void reportsBaselineDivergenceBeforeExecutingTicks() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        boolean[] divergentReset = {false};
        AgentRuntime runtime = runtime(queue, position);
        runtime.scenarios().register("changing-origin", context ->
                position[0] = divergentReset[0] ? 10 : 0);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("divergent-replay", "changing-origin"),
                "start-divergent-replay", TIMEOUT);
        queue.removeFirst().run();
        advance(runtime, queue, "divergent-source-tick");
        runtime.recordings().stop(
                "divergent-replay", "stop-divergent-replay", TIMEOUT);
        queue.removeFirst().run();
        divergentReset[0] = true;

        runtime.replays().execute(
                "divergent-replay", "execute-divergent-replay", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult result = runtime.replays().execute(
                "divergent-replay", "execute-divergent-replay", TIMEOUT)
                .result().orElseThrow();

        assertEquals(DeterminismStatus.DIVERGED, result.status());
        ReplayDivergence divergence = result.divergence().orElseThrow();
        assertEquals(ReplayPhase.BASELINE, divergence.phase());
        assertTrue(divergence.epochTick().isEmpty());
        assertEquals(0, result.bounds().completedTicks());
        assertEquals(10, position[0]);
    }

    @Test
    void ordinaryRecordingProducesTerminalInconclusiveReplayResult() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = runtime(queue, position);
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        RecordingSpec ordinary = scenarioSpec("ordinary-recording", "ball-drop").recording();
        runtime.recordings().start(ordinary, "start-ordinary-recording", TIMEOUT);
        queue.removeFirst().run();
        runtime.recordings().stop(
                "ordinary-recording", "stop-ordinary-recording", TIMEOUT);
        queue.removeFirst().run();

        runtime.replays().execute(
                "ordinary-recording", "execute-ordinary-recording", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult result = runtime.replays().execute(
                "ordinary-recording", "execute-ordinary-recording", TIMEOUT)
                .result().orElseThrow();

        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        assertTrue(result.profile().isEmpty());
        assertTrue(result.message().contains("replay evidence was not captured"));
        assertEquals(0, result.bounds().completedTicks());
    }

    @Test
    void stopsAtFirstTickDivergenceWithExactCorrelationEvidence() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        boolean[] drift = {false};
        AgentRuntime runtime = runtime(queue, position);
        runtime.inputs().register(InputSpec.builder("move")
                .requiredInteger("amount")
                .handler(parameters -> position[0] += parameters.requiredInteger("amount")
                        + (drift[0] ? 2 : 0))
                .build());
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("tick-divergence", "ball-drop"),
                "start-tick-divergence", TIMEOUT);
        queue.removeFirst().run();
        inject(runtime, queue, "divergent-move", 2, 2);
        advance(runtime, queue, "source-divergence-one");
        advance(runtime, queue, "source-divergence-two");
        runtime.recordings().stop(
                "tick-divergence", "stop-tick-divergence", TIMEOUT);
        queue.removeFirst().run();
        drift[0] = true;

        runtime.replays().execute(
                "tick-divergence", "execute-tick-divergence", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult result = runtime.replays().execute(
                "tick-divergence", "execute-tick-divergence", TIMEOUT)
                .result().orElseThrow();

        assertEquals(DeterminismStatus.DIVERGED, result.status());
        ReplayDivergence divergence = result.divergence().orElseThrow();
        assertEquals(ReplayPhase.SIMULATION_TICK, divergence.phase());
        assertEquals(2, divergence.epochTick().orElseThrow());
        assertTrue(divergence.referenceSimulationTickId().isPresent());
        assertTrue(divergence.replaySimulationTickId().isPresent());
        assertEquals(1, result.bounds().completedTicks());
        assertEquals(2, result.bounds().requestedTicks());
        assertEquals(6, position[0]);
    }

    @Test
    void mismatchedExecutedDeltaRetainsReplayInconclusiveEvidenceShape() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        boolean[] mismatch = {false};
        AgentRuntime runtime = runtimeBuilder(queue, position).build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP));
        runtime.entities().register(EntityId.of("world"), EntityType.of("state"),
                () -> "World", inspector -> inspector
                        .property("fixedStepNanos", () -> STEP)
                        .property("position", () -> position[0]));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).acknowledgedTick(delta -> {
                    position[0]++;
                    return mismatch[0] ? delta + 1 : delta;
                }).build());
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("delta-mismatch", "ball-drop"),
                "start-delta-mismatch", TIMEOUT);
        queue.removeFirst().run();
        advance(runtime, queue, "capture-delta-mismatch");
        runtime.recordings().stop(
                "delta-mismatch", "stop-delta-mismatch", TIMEOUT);
        queue.removeFirst().run();
        mismatch[0] = true;

        runtime.replays().execute(
                "delta-mismatch", "execute-delta-mismatch", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult result = runtime.replays().execute(
                "delta-mismatch", "execute-delta-mismatch", TIMEOUT)
                .result().orElseThrow();

        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        assertEquals("replay simulation tick evidence is incomplete or mismatched",
                result.message());
        assertTrue(result.applicationFailure().isEmpty());
    }

    @Test
    void executesCheckpointReplayThroughApplicationOwnedProvider() {
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
            public void dispose(CheckpointHandle handle) {}
        });
        runtime.start();
        runtime.checkpoints().create("replay-origin", "origin", "create-replay-origin", TIMEOUT);
        queue.removeFirst().run();
        pause(runtime, queue);
        runtime.replays().start(checkpointSpec("checkpoint-equal", "replay-origin"),
                "start-checkpoint-equal", TIMEOUT);
        queue.removeFirst().run();
        advance(runtime, queue, "checkpoint-source-tick");
        runtime.recordings().stop(
                "checkpoint-equal", "stop-checkpoint-equal", TIMEOUT);
        queue.removeFirst().run();

        runtime.replays().execute(
                "checkpoint-equal", "execute-checkpoint-equal", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult result = runtime.replays().execute(
                "checkpoint-equal", "execute-checkpoint-equal", TIMEOUT)
                .result().orElseThrow();

        assertEquals(DeterminismStatus.EQUAL, result.status());
        assertEquals(2, restores[0]);
        assertEquals(6, position[0]);
    }

    @Test
    void unknownRecordingAndRunningSimulationRejectBeforeDispatch() {
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

        AgentRuntimeException unknown = assertThrows(AgentRuntimeException.class,
                () -> runtime.replays().execute("missing", "execute-missing", TIMEOUT));
        assertEquals(RuntimeErrorCode.INVALID_QUERY, unknown.code());
        assertTrue(queue.isEmpty());

        captureAndStop(runtime, queue, "paused-source");
        inject(runtime, queue, "queued-before-replay", 1,
                runtime.controls().currentTick() + 1);
        AgentRuntimeException queued = assertThrows(AgentRuntimeException.class,
                () -> runtime.replays().execute(
                        "paused-source", "execute-with-queued-input", TIMEOUT));
        assertEquals(RuntimeErrorCode.INVALID_LIFECYCLE, queued.code());
        advance(runtime, queue, "clear-queued-before-replay");

        RecordingSpec active = scenarioSpec("active-ordinary", "ball-drop").recording();
        runtime.recordings().start(active, "start-active-ordinary", TIMEOUT);
        queue.removeFirst().run();
        AgentRuntimeException recording = assertThrows(AgentRuntimeException.class,
                () -> runtime.replays().execute(
                        "paused-source", "execute-with-active-recording", TIMEOUT));
        assertEquals(RuntimeErrorCode.INVALID_LIFECYCLE, recording.code());
        runtime.recordings().stop("active-ordinary", "stop-active-ordinary", TIMEOUT);
        queue.removeFirst().run();

        runtime.controls().control(false, "resume-before-replay", TIMEOUT);
        queue.removeFirst().run();
        AgentRuntimeException running = assertThrows(AgentRuntimeException.class,
                () -> runtime.replays().execute(
                        "paused-source", "execute-while-running", TIMEOUT));
        assertEquals(RuntimeErrorCode.INVALID_LIFECYCLE, running.code());
        assertTrue(queue.isEmpty());
    }

    @Test
    void applicationFailurePreservesPriorTickCountAndReleasesInputMode() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        boolean[] fail = {false};
        AgentRuntime runtime = runtime(queue, position);
        runtime.inputs().register(InputSpec.builder("move")
                .requiredInteger("amount")
                .handler(parameters -> {
                    if (fail[0]) {
                        throw new IllegalStateException("private replay input token");
                    }
                    position[0] += parameters.requiredInteger("amount");
                })
                .build());
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("failed-execution", "ball-drop"),
                "start-failed-execution", TIMEOUT);
        queue.removeFirst().run();
        advance(runtime, queue, "failure-source-one");
        inject(runtime, queue, "failure-source-input", 2, 2);
        advance(runtime, queue, "failure-source-two");
        runtime.recordings().stop(
                "failed-execution", "stop-failed-execution", TIMEOUT);
        queue.removeFirst().run();
        fail[0] = true;

        runtime.replays().execute(
                "failed-execution", "execute-failed-execution", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult result = runtime.replays().execute(
                "failed-execution", "execute-failed-execution", TIMEOUT)
                .result().orElseThrow();

        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        assertEquals(1, result.bounds().completedTicks());
        assertTrue(result.applicationFailure().isPresent());
        assertFalse(result.message().contains("private replay input token"));

        fail[0] = false;
        runtime.inputs().inject("move", "after-replay-failure", RuntimeValues.object(
                        RuntimeValues.field("amount", RuntimeValues.integer(1))),
                OptionalLong.of(runtime.controls().currentTick() + 1), TIMEOUT);
        queue.removeFirst().run();
    }

    @Test
    void deadlineAfterBaselineStopsBeforeTheFirstTick() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        long[] now = {1};
        boolean[] expireOnReset = {false};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("replay-deadline"))
                .clock(() -> now[0])
                .commandDispatcher(queue::addLast)
                .build();
        registerRuntimeCapabilities(runtime, position);
        runtime.scenarios().register("deadline-origin", context -> {
            position[0] = 0;
            if (expireOnReset[0]) {
                now[0] = Duration.ofSeconds(2).toNanos();
            }
        });
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("deadline-replay", "deadline-origin"),
                "start-deadline-replay", TIMEOUT);
        queue.removeFirst().run();
        advance(runtime, queue, "deadline-source-tick");
        runtime.recordings().stop(
                "deadline-replay", "stop-deadline-replay", TIMEOUT);
        queue.removeFirst().run();
        expireOnReset[0] = true;

        runtime.replays().execute(
                "deadline-replay", "execute-deadline-replay", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult result = runtime.replays().execute(
                "deadline-replay", "execute-deadline-replay", TIMEOUT)
                .result().orElseThrow();

        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        assertTrue(result.message().contains("deadline"));
        assertEquals(0, result.bounds().completedTicks());
        assertEquals(TIMEOUT.toNanos(), result.bounds().executionDeadlineNanos());
        assertEquals(0, position[0]);

        expireOnReset[0] = false;
        long epochBeforeTimeout = runtime.currentEpoch().value();
        runtime.replays().execute(
                "deadline-replay", "execute-before-reset-timeout", TIMEOUT);
        now[0] = Duration.ofSeconds(4).toNanos();
        ReplayOperation timedOut = runtime.replays().execute(
                "deadline-replay", "execute-before-reset-timeout", TIMEOUT);
        assertEquals(CommandState.TIMED_OUT,
                timedOut.command().status().orElseThrow().state());
        assertEquals(DeterminismStatus.INCONCLUSIVE,
                timedOut.result().orElseThrow().status());
        assertTrue(timedOut.result().orElseThrow().message().contains("before execution"));
        queue.removeFirst().run();
        assertEquals(epochBeforeTimeout, runtime.currentEpoch().value());
    }

    @Test
    void deadlineAfterZeroTickBaselineCannotReportEqualOrDiverged() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        long[] now = {1};
        boolean[] expire = {false};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("zero-tick-replay-deadline"))
                .clock(() -> now[0])
                .commandDispatcher(queue::addLast)
                .build();
        registerRuntimeCapabilities(runtime, position);
        runtime.scenarios().register("deadline-origin", context -> {
            position[0] = expire[0] ? 99 : 0;
            if (expire[0]) {
                now[0] = Duration.ofSeconds(2).toNanos();
            }
        });
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("zero-tick-deadline", "deadline-origin"),
                "start-zero-tick-deadline", TIMEOUT);
        queue.removeFirst().run();
        runtime.recordings().stop(
                "zero-tick-deadline", "stop-zero-tick-deadline", TIMEOUT);
        queue.removeFirst().run();
        expire[0] = true;

        runtime.replays().execute(
                "zero-tick-deadline", "execute-zero-tick-deadline", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult result = runtime.replays().execute(
                "zero-tick-deadline", "execute-zero-tick-deadline", TIMEOUT)
                .result().orElseThrow();

        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        assertTrue(result.message().contains("deadline"));
        assertEquals(0, result.bounds().requestedTicks());
    }

    @Test
    void deadlineCrossedInsideFinalTickCannotReportEqual() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        long[] now = {1};
        boolean[] expireOnTick = {false};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("tick-replay-deadline"))
                .clock(() -> now[0])
                .commandDispatcher(queue::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP));
        runtime.entities().register(EntityId.of("world"), EntityType.of("state"),
                () -> "World", inspector -> inspector
                        .property("fixedStepNanos", () -> STEP)
                        .property("position", () -> position[0]));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).acknowledgedTick(delta -> {
                    position[0]++;
                    if (expireOnTick[0]) {
                        now[0] = Duration.ofSeconds(2).toNanos();
                    }
                    return delta;
                }).build());
        runtime.scenarios().register("deadline-origin", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("tick-deadline", "deadline-origin"),
                "start-tick-deadline", TIMEOUT);
        queue.removeFirst().run();
        advance(runtime, queue, "capture-tick-deadline");
        runtime.recordings().stop("tick-deadline", "stop-tick-deadline", TIMEOUT);
        queue.removeFirst().run();
        expireOnTick[0] = true;

        runtime.replays().execute("tick-deadline", "execute-tick-deadline", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult result = runtime.replays().execute(
                "tick-deadline", "execute-tick-deadline", TIMEOUT).result().orElseThrow();

        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        assertTrue(result.message().contains("deadline"));
        assertEquals(0, result.bounds().completedTicks());
    }

    @Test
    void recordsAndReplaysACompletedInputTimelineAsNormalInputAndTickEvidence() {
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
        runtime.replays().start(scenarioSpec("timeline-recording", "ball-drop"),
                "start-timeline-recording", TIMEOUT);
        queue.removeFirst().run();

        InputTimelineSpec timeline = new InputTimelineSpec(3, List.of(
                new InputTimelineTransition("timeline-move", 1, "move",
                        RuntimeValues.object(RuntimeValues.field(
                                "amount", RuntimeValues.integer(2)))),
                new InputTimelineTransition("timeline-stop", 3, "move",
                        RuntimeValues.object(RuntimeValues.field(
                                "amount", RuntimeValues.integer(0))))));
        runtime.inputs().executeTimeline(timeline, "execute-timeline", TIMEOUT);
        queue.removeFirst().run();
        InputTimelineResult timelineResult = runtime.inputs().executeTimeline(
                timeline, "execute-timeline", TIMEOUT).result().orElseThrow();
        assertEquals(InputTimelineStopReason.COMPLETED, timelineResult.stopReason());

        runtime.recordings().stop("timeline-recording", "stop-timeline-recording", TIMEOUT);
        queue.removeFirst().run();

        RecordingChunk recording = runtime.recordings().get("timeline-recording", 0, 64);
        List<RecordingInputEntry> inputs = recording.entries().stream()
                .filter(RecordingInputEntry.class::isInstance)
                .map(RecordingInputEntry.class::cast)
                .toList();
        List<RecordingTickEntry> ticks = recording.entries().stream()
                .filter(RecordingTickEntry.class::isInstance)
                .map(RecordingTickEntry.class::cast)
                .toList();
        assertEquals(2, inputs.size());
        assertEquals(3, ticks.size());
        assertEquals(List.of("timeline-move", "timeline-stop"), inputs.stream()
                .map(value -> value.injection().requestId()).toList());
        assertTrue(inputs.stream().allMatch(value ->
                value.injection().state() == InputInjectionState.EXECUTED
                        && value.injection().resultingFrameId().isPresent()));

        runtime.replays().execute("timeline-recording", "replay-timeline", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult replay = runtime.replays().execute(
                "timeline-recording", "replay-timeline", TIMEOUT).result().orElseThrow();
        assertEquals(DeterminismStatus.EQUAL, replay.status());
        assertEquals(3, replay.bounds().completedTicks());
        assertEquals(2, replay.bounds().recordedInputs());
        assertEquals(5, position[0]);
    }

    @Test
    void timedOutInputTimelineMarksReplayCaptureInconclusive() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AtomicLong clock = new AtomicLong(1);
        AgentRuntime runtime = runtimeBuilder(queue, position).clock(clock::get).build();
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
                    clock.set(101);
                    return delta;
                })
                .build());
        runtime.inputs().register(InputSpec.builder("move")
                .requiredInteger("amount")
                .handler(parameters -> position[0] += parameters.requiredInteger("amount"))
                .build());
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("timeline-timeout-recording", "ball-drop"),
                "start-timeline-timeout-recording", TIMEOUT);
        queue.removeFirst().run();

        InputTimelineSpec timeline = new InputTimelineSpec(1, List.of(
                new InputTimelineTransition("timeline-timeout-move", 1, "move",
                        RuntimeValues.object(RuntimeValues.field(
                                "amount", RuntimeValues.integer(2))))));
        runtime.inputs().executeTimeline(timeline, "execute-timeline", Duration.ofNanos(50));
        queue.removeFirst().run();
        InputTimelineResult result = runtime.inputs().executeTimeline(
                timeline, "execute-timeline", Duration.ofNanos(50)).result().orElseThrow();
        assertEquals(InputTimelineStopReason.TIMED_OUT, result.stopReason());
        assertEquals(1, result.bounds().completedTicks());

        runtime.recordings().stop("timeline-timeout-recording",
                "stop-timeline-timeout-recording", TIMEOUT);
        queue.removeFirst().run();

        runtime.replays().execute("timeline-timeout-recording",
                "execute-timeline-timeout-recording", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult replay = runtime.replays().execute("timeline-timeout-recording",
                "execute-timeline-timeout-recording", TIMEOUT).result().orElseThrow();
        assertEquals(DeterminismStatus.INCONCLUSIVE, replay.status());
        assertTrue(replay.message().contains("timeline"));
    }

    @Test
    void failedInputTimelineMarksReplayCaptureInconclusive() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = runtime(queue, position);
        runtime.inputs().register(InputSpec.builder("move")
                .requiredInteger("amount")
                .handler(parameters -> position[0] += parameters.requiredInteger("amount"))
                .build());
        runtime.inputs().register(InputSpec.builder("boom")
                .requiredBoolean("active")
                .handler(parameters -> {
                    throw new IllegalStateException("timeline handler failed");
                })
                .build());
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("timeline-failed-recording", "ball-drop"),
                "start-timeline-failed-recording", TIMEOUT);
        queue.removeFirst().run();

        InputTimelineSpec timeline = new InputTimelineSpec(2, List.of(
                new InputTimelineTransition("timeline-move", 1, "move",
                        RuntimeValues.object(RuntimeValues.field(
                                "amount", RuntimeValues.integer(2)))),
                new InputTimelineTransition("timeline-boom", 2, "boom",
                        RuntimeValues.object(RuntimeValues.field(
                                "active", RuntimeValues.bool(true))))));
        runtime.inputs().executeTimeline(timeline, "execute-timeline", TIMEOUT);
        queue.removeFirst().run();
        InputTimelineResult result = runtime.inputs().executeTimeline(
                timeline, "execute-timeline", TIMEOUT).result().orElseThrow();
        assertEquals(InputTimelineStopReason.INPUT_FAILED, result.stopReason());

        runtime.recordings().stop("timeline-failed-recording",
                "stop-timeline-failed-recording", TIMEOUT);
        queue.removeFirst().run();

        runtime.replays().execute("timeline-failed-recording",
                "execute-timeline-failed-recording", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult replay = runtime.replays().execute("timeline-failed-recording",
                "execute-timeline-failed-recording", TIMEOUT).result().orElseThrow();
        assertEquals(DeterminismStatus.INCONCLUSIVE, replay.status());
    }

    @Test
    void lifecycleInvalidatedInputTimelineMarksReplayCaptureInconclusive() {
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
        runtime.replays().start(scenarioSpec("timeline-invalid-recording", "ball-drop"),
                "start-timeline-invalid-recording", TIMEOUT);
        queue.removeFirst().run();

        InputTimelineSpec timeline = new InputTimelineSpec(1, List.of(
                new InputTimelineTransition("timeline-invalid-move", 1, "move",
                        RuntimeValues.object(RuntimeValues.field(
                                "amount", RuntimeValues.integer(2))))));
        runtime.controls().control(false, "resume-before-timeline", TIMEOUT);
        runtime.inputs().executeTimeline(timeline, "execute-timeline", TIMEOUT);
        queue.removeFirst().run();
        queue.removeFirst().run();
        InputTimelineResult result = runtime.inputs().executeTimeline(
                timeline, "execute-timeline", TIMEOUT).result().orElseThrow();
        assertEquals(InputTimelineStopReason.LIFECYCLE_CHANGED, result.stopReason());

        runtime.recordings().stop("timeline-invalid-recording",
                "stop-timeline-invalid-recording", TIMEOUT);
        queue.removeFirst().run();
        runtime.controls().control(true, "repause", TIMEOUT);
        queue.removeFirst().run();

        runtime.replays().execute("timeline-invalid-recording",
                "execute-timeline-invalid-recording", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult replay = runtime.replays().execute("timeline-invalid-recording",
                "execute-timeline-invalid-recording", TIMEOUT).result().orElseThrow();
        assertEquals(DeterminismStatus.INCONCLUSIVE, replay.status());
        assertTrue(replay.message().contains("timeline"));
    }

    @Test
    void redactedInputTimelineMarksReplayCaptureInconclusive() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = runtime(queue, position);
        runtime.inputs().register(InputSpec.builder("secret")
                .requiredString("value")
                .redaction(InputRedactionPolicy.OMIT_PARAMETERS)
                .handler(parameters -> position[0]++)
                .build());
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        runtime.replays().start(scenarioSpec("timeline-redacted-recording", "ball-drop"),
                "start-timeline-redacted-recording", TIMEOUT);
        queue.removeFirst().run();

        InputTimelineSpec timeline = new InputTimelineSpec(1, List.of(
                new InputTimelineTransition("timeline-secret", 1, "secret",
                        RuntimeValues.object(RuntimeValues.field(
                                "value", RuntimeValues.string("hidden"))))));
        runtime.inputs().executeTimeline(timeline, "execute-timeline", TIMEOUT);
        queue.removeFirst().run();
        InputTimelineResult result = runtime.inputs().executeTimeline(
                timeline, "execute-timeline", TIMEOUT).result().orElseThrow();
        assertEquals(InputTimelineStopReason.COMPLETED, result.stopReason());

        runtime.recordings().stop("timeline-redacted-recording",
                "stop-timeline-redacted-recording", TIMEOUT);
        queue.removeFirst().run();

        runtime.replays().execute("timeline-redacted-recording",
                "execute-timeline-redacted-recording", TIMEOUT);
        queue.removeFirst().run();
        ReplayResult replay = runtime.replays().execute("timeline-redacted-recording",
                "execute-timeline-redacted-recording", TIMEOUT).result().orElseThrow();
        assertEquals(DeterminismStatus.INCONCLUSIVE, replay.status());
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

    private static void assertAutomaticStopIsInconclusive(RecordingLimits limits,
            int advances, RecordingStopReason expectedReason, String suffix) {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = runtimeBuilder(queue, position).recordingLimits(limits).build();
        registerRuntimeCapabilities(runtime, position);
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        String recordingId = "auto-" + suffix;
        runtime.replays().start(scenarioSpec(recordingId, "ball-drop"),
                "start-" + recordingId, TIMEOUT);
        queue.removeFirst().run();
        for (int index = 0; index < advances; index++) {
            advance(runtime, queue, "advance-" + suffix + '-' + index);
        }

        RecordingMetadata metadata = runtime.recordings().get(recordingId, 0, 16).metadata();
        assertEquals(expectedReason, metadata.stopReason());
        assertFalse(metadata.reproductionEvidenceComplete());
        runtime.replays().execute(recordingId, "execute-" + recordingId, TIMEOUT);
        queue.removeFirst().run();
        ReplayResult result = runtime.replays().execute(
                recordingId, "execute-" + recordingId, TIMEOUT).result().orElseThrow();
        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        assertTrue(result.message().contains("recording"));
    }

    private static void assertCommandTriggeredAutoStopIsInconclusive(boolean action) {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = runtimeBuilder(queue, position)
                .recordingLimits(new RecordingLimits(4, 8, 32, 32,
                        Duration.ofSeconds(1).toNanos(), 512, 16, 128))
                .build();
        registerRuntimeCapabilities(runtime, position);
        if (action) {
            runtime.actions().register(ActionSpec.builder("large-action")
                    .requiredString("value").handler(parameters -> {}).build());
        } else {
            runtime.inputs().register(InputSpec.builder("large-input")
                    .requiredString("value").handler(parameters -> {}).build());
        }
        runtime.scenarios().register("ball-drop", context -> position[0] = 0);
        runtime.start();
        pause(runtime, queue);
        String kind = action ? "action" : "input";
        String recordingId = kind + "-auto-stop";
        runtime.replays().start(scenarioSpec(recordingId, "ball-drop"),
                "start-" + recordingId, TIMEOUT);
        queue.removeFirst().run();
        RuntimeValue.ObjectValue parameters = RuntimeValues.object(
                RuntimeValues.field("value", RuntimeValues.string("x".repeat(128))));
        if (action) {
            runtime.actions().invoke("large-action", "large-action-request",
                    parameters, Optional.empty(), TIMEOUT);
        } else {
            runtime.inputs().inject("large-input", "large-input-request", parameters,
                    OptionalLong.of(runtime.controls().currentTick() + 1), TIMEOUT);
        }
        queue.removeFirst().run();
        RecordingMetadata metadata = runtime.recordings().get(recordingId, 0, 16).metadata();
        assertEquals(RecordingStopReason.ENCODED_SIZE_LIMIT, metadata.stopReason());
        if (!action) {
            advance(runtime, queue, "clear-large-input");
        }
        runtime.replays().execute(recordingId, "execute-" + recordingId, TIMEOUT);
        queue.removeFirst().run();
        ReplayResult result = runtime.replays().execute(
                recordingId, "execute-" + recordingId, TIMEOUT).result().orElseThrow();
        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        assertTrue(result.message().contains("recording"));
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
