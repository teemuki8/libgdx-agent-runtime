package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

final class CallbackOwnedSimulationTest {
    private static final long STEP = 16_666_667L;

    @Test
    void controlledCallbackCanCaptureItsOwnGameplayFrame() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("callback-owned"))
                .commandDispatcher(Runnable::run)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP),
                SimulationFrameOwnership.CALLBACK);
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(delta -> {
                    runtime.beginFrame(delta);
                    runtime.endFrame();
                    return delta;
                }).build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        runtime.controls().advance("advance", 1, STEP, Duration.ofSeconds(1));

        SimulationTick tick = runtime.simulation().ticks(new SimulationTickQuery(
                new ExecutionEpochId(0), 1, 1, 1)).ticks().getFirst();
        assertEquals(SimulationTickOutcome.COMPLETED, tick.outcome());
        assertEquals(Optional.of(new FrameId(1)), tick.resultingFrameId());
        runtime.close();
    }

    @Test
    void normalCallbackCorrelatesItsFrameAndActiveContext() {
        try (AgentRuntime runtime = callbackRuntime("normal")) {
            SimulationTick tick = runtime.simulation().tick(STEP, delta -> {
                assertTrue(runtime.simulation().activeTick().isEmpty());
                runtime.beginFrame(delta);
                assertEquals(new FrameId(1), runtime.simulation().activeTick()
                        .orElseThrow().runtimeFrameId());
                runtime.emit(EventSpec.type("gameplay.tick"));
                runtime.endFrame();
                assertTrue(runtime.simulation().activeTick().isEmpty());
                return delta;
            }).orElseThrow();
            assertEquals(SimulationTickOutcome.COMPLETED, tick.outcome());
            assertEquals(Optional.of(new FrameId(1)), tick.resultingFrameId());
            assertEquals(1, runtime.latestFrame().orElseThrow().events().size());
        }
    }

    @Test
    void missingFrameCannotClaimCompletion() {
        try (AgentRuntime runtime = callbackRuntime("missing")) {
            assertThrows(AgentRuntimeException.class,
                    () -> runtime.simulation().tick(STEP, delta -> delta));
            assertEquals(SimulationTickOutcome.CAPTURE_FAILED, latestTick(runtime).outcome());
            assertEquals(Optional.empty(), latestTick(runtime).resultingFrameId());
            assertEquals(new FrameId(0), runtime.latestFrame().orElseThrow().frameId());
        }
    }

    @Test
    void secondFrameIsRejectedEvenWhenCallbackCatchesViolation() {
        try (AgentRuntime runtime = callbackRuntime("multiple")) {
            assertThrows(AgentRuntimeException.class, () -> runtime.simulation().tick(STEP, delta -> {
                runtime.frame(delta, () -> {});
                assertThrows(AgentRuntimeException.class, () -> runtime.beginFrame(delta));
                return delta;
            }));
            assertEquals(SimulationTickOutcome.CAPTURE_FAILED, latestTick(runtime).outcome());
            assertEquals(new FrameId(1), runtime.latestFrame().orElseThrow().frameId());
        }
    }

    @Test
    void caughtNestedFrameViolationStillFailsCallbackOwnedTick() {
        try (AgentRuntime runtime = callbackRuntime("nested-open")) {
            assertThrows(AgentRuntimeException.class, () -> runtime.simulation().tick(STEP, delta -> {
                runtime.beginFrame(delta);
                assertThrows(AgentRuntimeException.class, () -> runtime.beginFrame(delta));
                runtime.endFrame();
                return delta;
            }));
            assertEquals(SimulationTickOutcome.CAPTURE_FAILED, latestTick(runtime).outcome());
            assertEquals(new FrameId(1), runtime.latestFrame().orElseThrow().frameId());
        }
    }

    @Test
    void caughtFrameCallbackFailureCannotClaimCompletion() {
        try (AgentRuntime runtime = callbackRuntime("caught-frame-failure")) {
            assertThrows(AgentRuntimeException.class, () -> runtime.simulation().tick(STEP, delta -> {
                assertThrows(IllegalStateException.class, () -> runtime.frame(delta, () -> {
                    throw new IllegalStateException("application failure");
                }));
                return delta;
            }));
            assertEquals(SimulationTickOutcome.CAPTURE_FAILED, latestTick(runtime).outcome());
        }
    }

    @Test
    void unfinishedFrameFailsAndReleasesCaptureForNextTick() {
        try (AgentRuntime runtime = callbackRuntime("unfinished")) {
            assertThrows(AgentRuntimeException.class, () -> runtime.simulation().tick(STEP, delta -> {
                runtime.beginFrame(delta);
                return delta;
            }));
            assertEquals(SimulationTickOutcome.CAPTURE_FAILED, latestTick(runtime).outcome());
            assertEquals(SimulationTickOutcome.COMPLETED, runtime.simulation().tick(STEP, delta -> {
                runtime.frame(delta, () -> {});
                return delta;
            }).orElseThrow().outcome());
        }
    }

    @Test
    void callbackFailureAfterOpeningFrameRetainsFailedAttemptAndCleansUp() {
        try (AgentRuntime runtime = callbackRuntime("throwing")) {
            IllegalStateException expected = new IllegalStateException("private detail");
            assertEquals(expected, assertThrows(IllegalStateException.class,
                    () -> runtime.simulation().tick(STEP, delta -> {
                        runtime.beginFrame(delta);
                        throw expected;
                    })));
            assertEquals(SimulationTickOutcome.CALLBACK_FAILED, latestTick(runtime).outcome());
            assertEquals(SimulationMutationOutcome.UNKNOWN, latestTick(runtime).mutationOutcome());
            assertTrue(runtime.simulation().activeTick().isEmpty());
            runtime.frame(0, () -> {});
        }
    }

    @Test
    void wrongDeltaAndEpochResetCannotBecomeTickEvidence() {
        try (AgentRuntime runtime = callbackRuntime("wrong-delta")) {
            assertThrows(AgentRuntimeException.class, () -> runtime.simulation().tick(STEP, delta -> {
                runtime.frame(delta + 1, () -> {});
                return delta;
            }));
            assertEquals(Optional.empty(), latestTick(runtime).resultingFrameId());
            assertThrows(AgentRuntimeException.class, () -> runtime.simulation().tick(STEP, delta -> {
                runtime.startEpoch(BaselineKind.SCENARIO_RESET);
                return delta;
            }));
            assertEquals(new ExecutionEpochId(0), runtime.currentEpoch());
        }
    }

    @Test
    void cannotCloseOrNestTicksBetweenCallbackFrames() {
        try (AgentRuntime runtime = callbackRuntime("lifecycle")) {
            assertThrows(AgentRuntimeException.class, () -> runtime.simulation().tick(STEP, delta -> {
                assertThrows(AgentRuntimeException.class, runtime::close);
                runtime.frame(delta, () -> {});
                assertThrows(AgentRuntimeException.class,
                        () -> runtime.simulation().tick(delta, nested -> nested));
                return delta;
            }));
            assertEquals(RuntimeStatus.RUNNING, runtime.status());
            assertEquals(1, runtime.simulation().state().attemptedEpochTicks());
        }
    }

    @Test
    void defaultOwnershipStillRejectsSelfCapture() {
        try (AgentRuntime runtime = AgentRuntime.builder().build()) {
            runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP));
            runtime.start();
            assertThrows(AgentRuntimeException.class, () -> runtime.simulation().tick(STEP, delta -> {
                runtime.frame(delta, () -> {});
                return delta;
            }));
            assertEquals(SimulationTickOutcome.CALLBACK_FAILED, latestTick(runtime).outcome());
        }
    }

    @Test
    void cancelledControlledAdvanceDoesNotRunCallbackOrCapture() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        try (AgentRuntime runtime = AgentRuntime.builder().commandDispatcher(queue::addLast).build()) {
            runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP),
                    SimulationFrameOwnership.CALLBACK);
            runtime.controls().register(SimulationControllerSpec.builder()
                    .pause(() -> {}).resume(() -> {}).acknowledgedTick(delta -> {
                        runtime.frame(delta, () -> {});
                        return delta;
                    }).build());
            runtime.start();
            runtime.controls().control(true, "pause", Duration.ofSeconds(1));
            queue.removeFirst().run();
            runtime.controls().advanceFixed("cancelled", 2, Duration.ofSeconds(1));
            assertTrue(runtime.commands().orElseThrow().cancel("cancelled").accepted());
            queue.removeFirst().run();
            assertEquals(new FrameId(0), runtime.latestFrame().orElseThrow().frameId());
            assertEquals(0, runtime.simulation().state().attemptedEpochTicks());
        }
    }

    @Test
    void replayResetsEpochAndRepeatsCallbackOwnedInputTicks() {
        long[] position = {0};
        long[] intent = {0};
        try (AgentRuntime runtime = AgentRuntime.builder()
                .commandDispatcher(Runnable::run).build()) {
            runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP),
                    SimulationFrameOwnership.CALLBACK);
            runtime.entities().register(EntityId.of("world"), EntityType.of("world"),
                    () -> "World", inspector -> inspector
                            .property("position", () -> position[0])
                            .property("step", () -> STEP));
            runtime.inputs().register(InputSpec.builder("move").handler(parameters -> intent[0] = 3)
                    .build());
            runtime.scenarios().register("origin", context -> {
                position[0] = 0;
                intent[0] = 0;
            });
            runtime.controls().register(SimulationControllerSpec.builder()
                    .pause(() -> {}).resume(() -> {}).acknowledgedTick(delta -> {
                        long drained = intent[0];
                        intent[0] = 0;
                        runtime.frame(delta, () -> position[0] += drained + 1);
                        return delta;
                    }).build());
            runtime.start();
            runtime.controls().control(true, "pause", Duration.ofSeconds(1));
            RecordingSpec recording = new RecordingSpec("recording", "2.5", List.of(),
                    Optional.of("origin"), Optional.empty(), OptionalLong.of(7),
                    RuntimeValues.object(), true);
            ReplayCaptureSpec capture = new ReplayCaptureSpec(recording,
                    new DeterminismProfile(new SnapshotComparisonScope(List.of(EntityId.of("world")),
                            List.of("position"), List.of(), false, false), false),
                    List.of(new SimulationConfigurationRequirement(EntityId.of("world"),
                            "step", RuntimeValues.integer(STEP))), List.of(), List.of());
            runtime.replays().start(capture, "capture", Duration.ofSeconds(1));
            runtime.inputs().executeTimeline(new InputTimelineSpec(2, List.of(
                    new InputTimelineTransition("move-once", 1, "move", RuntimeValues.object()))),
                    "timeline", Duration.ofSeconds(1));
            runtime.recordings().stop("recording", "stop", Duration.ofSeconds(1));
            assertEquals(5, position[0]);

            ReplayResult result = runtime.replays().execute(
                    "recording", "replay", Duration.ofSeconds(1)).result().orElseThrow();
            assertEquals(DeterminismStatus.EQUAL, result.status());
            assertEquals(2, result.bounds().completedTicks());
            assertEquals(5, position[0]);
            assertEquals(new ExecutionEpochId(2), runtime.currentEpoch());
        }
    }

    private static AgentRuntime callbackRuntime(String id) {
        AgentRuntime runtime = AgentRuntime.builder().sessionId(SessionId.of(id)).build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP),
                SimulationFrameOwnership.CALLBACK);
        runtime.start();
        return runtime;
    }

    @Test
    void inputTimelineAppliesIntentBeforeCallbackDrainsItsCommandQueue() {
        ArrayList<String> observed = new ArrayList<>();
        try (AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-before-capture"))
                .commandDispatcher(Runnable::run).build()) {
            runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP),
                    SimulationFrameOwnership.CALLBACK);
            runtime.inputs().register(InputSpec.builder("move")
                    .handler(parameters -> observed.add("input")).build());
            runtime.controls().register(SimulationControllerSpec.builder()
                    .pause(() -> {}).resume(() -> {}).acknowledgedTick(delta -> {
                        observed.add("drain");
                        runtime.frame(delta, () -> observed.add("step"));
                        return delta;
                    }).build());
            runtime.start();
            runtime.controls().control(true, "pause", Duration.ofSeconds(1));
            InputTimelineSpec spec = new InputTimelineSpec(2, List.of(
                    new InputTimelineTransition("move-one", 1, "move", RuntimeValues.object())));
            InputTimelineResult result = runtime.inputs().executeTimeline(
                    spec, "timeline", Duration.ofSeconds(1)).result().orElseThrow();
            assertEquals(InputTimelineStopReason.COMPLETED, result.stopReason());
            assertEquals(List.of("input", "drain", "step", "drain", "step"), observed);
            assertEquals(new FrameId(1), result.firstFrameId().orElseThrow());
            assertEquals(new FrameId(2), result.finalFrameId().orElseThrow());
        }
    }

    @Test
    void preTickInputCannotSubstituteItsFrameForCallbackCapture() {
        try (AgentRuntime runtime = AgentRuntime.builder()
                .commandDispatcher(Runnable::run).build()) {
            runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP),
                    SimulationFrameOwnership.CALLBACK);
            runtime.inputs().register(InputSpec.builder("capture")
                    .handler(parameters -> runtime.frame(STEP, () -> {})).build());
            runtime.controls().register(SimulationControllerSpec.builder()
                    .pause(() -> {}).resume(() -> {}).acknowledgedTick(delta -> delta).build());
            runtime.start();
            runtime.controls().control(true, "pause", Duration.ofSeconds(1));
            InputTimelineResult result = runtime.inputs().executeTimeline(new InputTimelineSpec(1,
                    List.of(new InputTimelineTransition("capture", 1, "capture", RuntimeValues.object()))),
                    "timeline", Duration.ofSeconds(1)).result().orElseThrow();
            assertEquals(0, result.bounds().completedTicks());
            assertEquals(new FrameId(0), runtime.latestFrame().orElseThrow().frameId());
        }
    }

    private static SimulationTick latestTick(AgentRuntime runtime) {
        long tick = runtime.simulation().state().attemptedEpochTicks();
        return runtime.simulation().ticks(new SimulationTickQuery(
                runtime.currentEpoch(), tick, tick, 1)).ticks().getFirst();
    }
}
