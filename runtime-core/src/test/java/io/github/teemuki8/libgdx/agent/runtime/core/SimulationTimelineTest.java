package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class SimulationTimelineTest {
    private static final long STEP = 16_666_667L;

    @Test
    void baselineIsNotATickAndFirstAcknowledgedTickCorrelatesItsFrame() {
        AgentRuntime runtime = runtime("timeline-first");
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP));
        runtime.start();

        assertEquals(Optional.empty(), runtime.simulation().state().latestTickId());
        assertEquals(0, runtime.simulation().state().attemptedEpochTicks());

        SimulationTick tick = runtime.simulation().tick(STEP, supplied -> supplied)
                .orElseThrow();

        assertEquals(new SimulationTickId(1), tick.simulationTickId());
        assertEquals(new ExecutionEpochId(0), tick.executionEpochId());
        assertEquals(1, tick.epochTick());
        assertEquals(OptionalLong.of(STEP), tick.configuredFixedStepNanos());
        assertEquals(STEP, tick.runtimeSuppliedDeltaNanos());
        assertEquals(OptionalLong.of(STEP), tick.executedDeltaNanos());
        assertEquals(STEP, tick.epochSimulationTimeNanos());
        assertEquals(Optional.of(new FrameId(1)), tick.resultingFrameId());
        assertEquals(SimulationTickSource.RUNNING, tick.source());
        assertEquals(SimulationTickOutcome.COMPLETED, tick.outcome());
        assertEquals(SimulationMutationOutcome.KNOWN_COMPLETED, tick.mutationOutcome());
        assertTrue(tick.diagnostic().isEmpty());
        assertEquals(new FrameId(1), runtime.latestFrame().orElseThrow().frameId());
        assertEquals(STEP, runtime.simulation().state().epochSimulationTimeNanos());
    }

    @Test
    void activeTickContextExistsOnlyOnCaptureThreadDuringItsRuntimeFrame() {
        AgentRuntime runtime = runtime("timeline-active-context");
        runtime.start();
        assertEquals(Optional.empty(), runtime.simulation().activeTick());

        AtomicReference<Optional<ActiveSimulationTick>> otherThread = new AtomicReference<>();
        runtime.simulation().tick(STEP, supplied -> {
            assertEquals(Optional.of(new ActiveSimulationTick(
                    new SimulationTickId(1), new ExecutionEpochId(0), 1, STEP,
                    SimulationTickSource.RUNNING, new FrameId(1))),
                    runtime.simulation().activeTick());
            Thread reader = new Thread(
                    () -> otherThread.set(runtime.simulation().activeTick()));
            reader.start();
            try {
                reader.join();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return supplied;
        });

        assertEquals(Optional.empty(), otherThread.get());
        assertEquals(Optional.empty(), runtime.simulation().activeTick());
    }

    @Test
    void controlledTickExposesPausedContextAndFailuresAlwaysClearIt() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicReference<ActiveSimulationTick> controlled = new AtomicReference<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-active-controlled"))
                .clock(new IncrementingClock())
                .commandDispatcher(queue::addLast)
                .build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(supplied -> {
                    controlled.set(runtime.simulation().activeTick().orElseThrow());
                    return supplied;
                })
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        queue.removeFirst().run();
        runtime.controls().advance("advance", 1, STEP, Duration.ofSeconds(1));
        queue.removeFirst().run();

        assertEquals(new ActiveSimulationTick(
                new SimulationTickId(1), new ExecutionEpochId(0), 1, STEP,
                SimulationTickSource.PAUSED, new FrameId(1)), controlled.get());
        assertEquals(Optional.empty(), runtime.simulation().activeTick());

        assertThrows(IllegalStateException.class,
                () -> runtime.simulation().tick(STEP, supplied -> {
                    assertTrue(runtime.simulation().activeTick().isPresent());
                    throw new IllegalStateException("expected");
                }));
        assertEquals(Optional.empty(), runtime.simulation().activeTick());
    }

    @Test
    void captureFailureClearsActiveTickContext() {
        long[] clockCalls = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-active-capture-failure"))
                .clock(() -> clockCalls[0]++ == 0 ? 1 : -1)
                .build();
        runtime.start();

        assertThrows(IllegalStateException.class,
                () -> runtime.simulation().tick(4, supplied -> {
                    assertEquals(new FrameId(1), runtime.simulation().activeTick()
                            .orElseThrow().runtimeFrameId());
                    return supplied;
                }));

        assertEquals(Optional.empty(), runtime.simulation().activeTick());
    }

    @Test
    void activeTickContextRemainsVisibleThroughEntityCapture() {
        AgentRuntime runtime = runtime("timeline-active-capture");
        runtime.entities().register(EntityId.of("tick.probe"), EntityType.of("probe"),
                () -> "probe", inspector -> inspector.property("tick", () ->
                        runtime.simulation().activeTick()
                                .<RuntimeValue>map(value -> RuntimeValues.integer(
                                        value.simulationTickId().value()))
                                .orElseGet(RuntimeValues::nullValue)));
        runtime.start();

        runtime.simulation().tick(STEP, supplied -> supplied);

        EntitySnapshot probe = runtime.latestFrame().orElseThrow().entities().stream()
                .filter(entity -> entity.id().equals(EntityId.of("tick.probe")))
                .findFirst().orElseThrow();
        assertEquals(RuntimeValues.integer(1), probe.properties().getFirst().value());
    }

    @Test
    void activeTickContextRejectsInvalidTransientEvidence() {
        assertThrows(NullPointerException.class, () -> new ActiveSimulationTick(
                null, new ExecutionEpochId(0), 1, 0,
                SimulationTickSource.RUNNING, new FrameId(1)));
        assertThrows(IllegalArgumentException.class, () -> new ActiveSimulationTick(
                new SimulationTickId(1), new ExecutionEpochId(0), 0, 0,
                SimulationTickSource.RUNNING, new FrameId(1)));
        assertThrows(IllegalArgumentException.class, () -> new ActiveSimulationTick(
                new SimulationTickId(1), new ExecutionEpochId(0), 1, -1,
                SimulationTickSource.RUNNING, new FrameId(1)));
    }

    @Test
    void executedDeltaMismatchIsTypedAndNeverClaimsFixedStepCompletion() {
        AgentRuntime runtime = runtime("timeline-mismatch");
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP));
        runtime.start();

        SimulationTick tick = runtime.simulation().tick(STEP, supplied -> supplied * 2)
                .orElseThrow();

        assertEquals(SimulationTickOutcome.DELTA_MISMATCH, tick.outcome());
        assertEquals(OptionalLong.of(STEP * 2), tick.executedDeltaNanos());
        assertEquals(STEP * 2, tick.epochSimulationTimeNanos());
        assertEquals(SimulationMutationOutcome.KNOWN_COMPLETED, tick.mutationOutcome());
        assertTrue(tick.diagnostic().orElseThrow().contains("configured fixed step"));
        assertEquals(OptionalLong.of(STEP), runtime.simulation().state().configuredFixedStepNanos());
        assertEquals(OptionalLong.of(STEP * 2), runtime.simulation().state().lastExecutedDeltaNanos());
    }

    @Test
    void callbackFailureRetainsAttemptAndCompletedCaptureWithUnknownMutationOutcome() {
        AgentRuntime runtime = runtime("timeline-failure");
        runtime.start();

        assertThrows(IllegalStateException.class, () -> runtime.simulation().tick(STEP, supplied -> {
            throw new IllegalStateException("secret application detail");
        }));

        SimulationTick retained = runtime.simulation().ticks(
                new SimulationTickQuery(new ExecutionEpochId(0), 1, 1, 10))
                .ticks().getFirst();
        assertEquals(SimulationTickOutcome.CALLBACK_FAILED, retained.outcome());
        assertEquals(SimulationMutationOutcome.UNKNOWN, retained.mutationOutcome());
        assertEquals(OptionalLong.empty(), retained.executedDeltaNanos());
        assertEquals(Optional.of(new FrameId(1)), retained.resultingFrameId());
        assertEquals(0, retained.epochSimulationTimeNanos());
        assertTrue(retained.diagnostic().orElseThrow().contains("callback failed"));
        assertEquals(false, retained.diagnostic().orElseThrow().contains("secret"));
    }

    @Test
    void captureFailureRetainsAcknowledgedDeltaWithoutInventingAFrame() {
        long[] clockCalls = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-capture-failure"))
                .clock(() -> clockCalls[0]++ == 0 ? 1 : -1)
                .build();
        runtime.start();

        assertThrows(IllegalStateException.class,
                () -> runtime.simulation().tick(4, supplied -> supplied));

        SimulationTick retained = runtime.simulation().ticks(
                new SimulationTickQuery(new ExecutionEpochId(0), 1, 1, 1))
                .ticks().getFirst();
        assertEquals(SimulationTickOutcome.CAPTURE_FAILED, retained.outcome());
        assertEquals(SimulationMutationOutcome.KNOWN_COMPLETED, retained.mutationOutcome());
        assertEquals(OptionalLong.of(4), retained.executedDeltaNanos());
        assertEquals(4, retained.epochSimulationTimeNanos());
        assertEquals(Optional.empty(), retained.resultingFrameId());
    }

    @Test
    void epochBaselineResetsRelativeTickAndTimeWithoutReusingSessionTickIds() {
        AgentRuntime runtime = runtime("timeline-epoch");
        runtime.start();
        SimulationTick first = runtime.simulation().tick(3, supplied -> supplied).orElseThrow();

        FrameId baseline = runtime.startEpoch(BaselineKind.SCENARIO_RESET);
        SimulationTick second = runtime.simulation().tick(5, supplied -> supplied).orElseThrow();

        assertEquals(new FrameId(2), baseline);
        assertEquals(new SimulationTickId(1), first.simulationTickId());
        assertEquals(new SimulationTickId(2), second.simulationTickId());
        assertEquals(new ExecutionEpochId(1), second.executionEpochId());
        assertEquals(1, second.epochTick());
        assertEquals(5, second.epochSimulationTimeNanos());
        assertEquals(Optional.of(new FrameId(3)), second.resultingFrameId());
    }

    @Test
    void boundedPagesDistinguishPaginationEvictionAndNotYetExecutedRanges() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-bounds"))
                .clock(new IncrementingClock())
                .simulationTimelineLimits(new SimulationTimelineLimits(2, 2, 10, 100, 128))
                .build();
        runtime.start();
        runtime.simulation().tick(1, supplied -> supplied);
        runtime.simulation().tick(1, supplied -> supplied);
        runtime.simulation().tick(1, supplied -> supplied);

        SimulationTickPage paginated = runtime.simulation().ticks(
                new SimulationTickQuery(new ExecutionEpochId(0), 2, 3, 1));
        assertEquals(SimulationTickRangeStatus.PAGINATED, paginated.rangeStatus());
        assertEquals(1, paginated.ticks().size());
        assertTrue(paginated.hasMore());

        SimulationTickPage evicted = runtime.simulation().ticks(
                new SimulationTickQuery(new ExecutionEpochId(0), 1, 3, 2));
        assertEquals(SimulationTickRangeStatus.PARTIALLY_EVICTED, evicted.rangeStatus());
        assertEquals(List.of(2L, 3L),
                evicted.ticks().stream().map(tick -> tick.simulationTickId().value()).toList());

        SimulationTickPage future = runtime.simulation().ticks(
                new SimulationTickQuery(new ExecutionEpochId(0), 2, 4, 2));
        assertEquals(SimulationTickRangeStatus.NOT_YET_EXECUTED, future.rangeStatus());
        assertEquals(false, future.complete());

        SimulationTickPage futureEpoch = runtime.simulation().ticks(
                new SimulationTickQuery(new ExecutionEpochId(1), 1, 1, 1));
        assertEquals(SimulationTickRangeStatus.NOT_YET_EXECUTED, futureEpoch.rangeStatus());
    }

    @Test
    void disabledRuntimeExecutesCallbackWithoutEvidenceAndCloseRetainsCompletedEvidence() {
        long[] callbacks = {0};
        AgentRuntime disabled = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-disabled"))
                .configuration(RuntimeConfiguration.disabled())
                .build();
        disabled.start();
        assertEquals(Optional.empty(), disabled.simulation().tick(4, supplied -> {
            callbacks[0]++;
            return supplied;
        }));
        assertEquals(1, callbacks[0]);
        assertEquals(Optional.empty(), disabled.simulation().state().latestTickId());

        AgentRuntime runtime = runtime("timeline-close");
        runtime.start();
        runtime.simulation().tick(1, supplied -> supplied);
        runtime.close();

        assertEquals(1, runtime.simulation().ticks(
                new SimulationTickQuery(new ExecutionEpochId(0), 1, 1, 1)).ticks().size());
        AgentRuntimeException closed = assertThrows(AgentRuntimeException.class,
                () -> runtime.simulation().tick(1, supplied -> supplied));
        assertEquals(RuntimeErrorCode.RUNTIME_CLOSED, closed.code());
    }

    @Test
    void controlledAndNormalTicksShareTimelineWithoutChangingLegacyControlledCounter() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-controlled"))
                .clock(new IncrementingClock())
                .commandDispatcher(queue::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(supplied -> supplied)
                .build());
        runtime.start();

        runtime.simulation().tick(STEP, supplied -> supplied);
        assertEquals(0, runtime.controls().currentTick());
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        queue.removeFirst().run();
        runtime.controls().advance("advance", 1, STEP, Duration.ofSeconds(1));
        queue.removeFirst().run();

        assertEquals(1, runtime.controls().currentTick());
        SimulationTickPage page = runtime.simulation().ticks(
                new SimulationTickQuery(new ExecutionEpochId(0), 1, 2, 2));
        assertEquals(List.of(1L, 2L), page.ticks().stream()
                .map(tick -> tick.simulationTickId().value()).toList());
        assertEquals(SimulationTickSource.RUNNING, page.ticks().get(0).source());
        assertEquals(SimulationTickSource.PAUSED, page.ticks().get(1).source());
        assertEquals(SimulationTickOutcome.COMPLETED, page.ticks().get(1).outcome());
        assertEquals(Optional.of(new FrameId(2)), page.ticks().get(1).resultingFrameId());
    }

    @Test
    void legacyControlledCallbackIsExplicitlyUnacknowledged() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-legacy-control"))
                .clock(new IncrementingClock())
                .commandDispatcher(queue::addLast)
                .build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .tick(supplied -> {})
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        queue.removeFirst().run();

        runtime.controls().advance("advance", 1, STEP, Duration.ofSeconds(1));
        queue.removeFirst().run();

        SimulationTick tick = runtime.simulation().ticks(
                new SimulationTickQuery(new ExecutionEpochId(0), 1, 1, 1))
                .ticks().getFirst();
        assertEquals(SimulationTickOutcome.UNACKNOWLEDGED, tick.outcome());
        assertEquals(OptionalLong.empty(), tick.executedDeltaNanos());
        assertEquals(0, tick.epochSimulationTimeNanos());
        assertEquals(1, runtime.controls().currentTick());
    }

    @Test
    void acknowledgedControlPreservesControlledTickRecordingIdentity() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-controlled-recording"))
                .clock(new IncrementingClock())
                .commandDispatcher(Runnable::run)
                .build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(supplied -> supplied)
                .build());
        runtime.start();
        runtime.simulation().tick(STEP, supplied -> supplied);
        RecordingSpec spec = new RecordingSpec("controlled", "2.1", List.of(),
                Optional.empty(), Optional.empty(), OptionalLong.empty(),
                RuntimeValues.object(), false);
        runtime.recordings().start(spec, "recording-start", Duration.ofSeconds(1));

        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        runtime.controls().advance("advance", 1, STEP, Duration.ofSeconds(1));
        runtime.recordings().stop("controlled", "recording-stop", Duration.ofSeconds(1));

        RecordingTickEntry tick = runtime.recordings().get("controlled", 0, 8).entries().stream()
                .filter(RecordingTickEntry.class::isInstance)
                .map(RecordingTickEntry.class::cast)
                .findFirst().orElseThrow();
        assertEquals(1, tick.tick());
        assertEquals(new FrameId(2), tick.resultingFrameId());
        assertEquals(2, runtime.simulation().state().latestTickId().orElseThrow().value());
    }

    @Test
    void lifecycleThreadAndTimingBoundsFailWithoutDishonestCompletion() throws Exception {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-limits"))
                .clock(new IncrementingClock())
                .simulationTimelineLimits(new SimulationTimelineLimits(10, 10, 5, 6, 64))
                .build();
        runtime.start();
        assertThrows(AgentRuntimeException.class,
                () -> runtime.simulation().register(SimulationTimelineSpec.fixedStep(1)));
        assertThrows(AgentRuntimeException.class,
                () -> runtime.simulation().tick(6, supplied -> supplied));
        assertEquals(0, runtime.simulation().state().attemptedEpochTicks());

        AtomicReference<Throwable> wrongThread = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                runtime.simulation().tick(1, supplied -> supplied);
            } catch (Throwable failure) {
                wrongThread.set(failure);
            }
        });
        thread.start();
        thread.join();
        assertEquals(RuntimeErrorCode.WRONG_THREAD,
                ((AgentRuntimeException) wrongThread.get()).code());

        AgentRuntimeException invalidReport = assertThrows(AgentRuntimeException.class,
                () -> runtime.simulation().tick(1, supplied -> 6));
        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED, invalidReport.code());
        assertEquals(SimulationTickOutcome.REPORTED_DELTA_INVALID,
                runtime.simulation().ticks(new SimulationTickQuery(
                        new ExecutionEpochId(0), 1, 1, 1)).ticks().getFirst().outcome());

        runtime.simulation().tick(4, supplied -> supplied);
        AgentRuntimeException timeLimit = assertThrows(AgentRuntimeException.class,
                () -> runtime.simulation().tick(3, supplied -> supplied));
        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED, timeLimit.code());
        SimulationTick limited = runtime.simulation().ticks(new SimulationTickQuery(
                new ExecutionEpochId(0), 3, 3, 1)).ticks().getFirst();
        assertEquals(SimulationTickOutcome.TIME_LIMIT_EXCEEDED, limited.outcome());
        assertEquals(4, limited.epochSimulationTimeNanos());
    }

    @Test
    void simulationTimeArithmeticOverflowIsReportedWithoutChangingAuthoritativeTime() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-overflow"))
                .clock(new IncrementingClock())
                .simulationTimelineLimits(new SimulationTimelineLimits(
                        10, 10, Long.MAX_VALUE, Long.MAX_VALUE, 64))
                .build();
        runtime.start();

        runtime.simulation().tick(Long.MAX_VALUE, supplied -> supplied);
        AgentRuntimeException overflow = assertThrows(AgentRuntimeException.class,
                () -> runtime.simulation().tick(1, supplied -> supplied));

        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED, overflow.code());
        SimulationTick retained = runtime.simulation().ticks(new SimulationTickQuery(
                new ExecutionEpochId(0), 2, 2, 1)).ticks().getFirst();
        assertEquals(SimulationTickOutcome.TIME_LIMIT_EXCEEDED, retained.outcome());
        assertEquals(Long.MAX_VALUE, retained.epochSimulationTimeNanos());
    }

    @Test
    void inFlightAttemptCannotMakeAnUnretainedRangeAppearComplete() {
        AgentRuntime runtime = runtime("timeline-in-flight");
        runtime.start();

        runtime.simulation().tick(1, supplied -> {
            SimulationTickPage duringCallback = runtime.simulation().ticks(
                    new SimulationTickQuery(new ExecutionEpochId(0), 1, 1, 1));
            assertEquals(SimulationTickRangeStatus.NOT_YET_EXECUTED,
                    duringCallback.rangeStatus());
            assertEquals(List.of(), duringCallback.ticks());
            return supplied;
        });

        assertTrue(runtime.simulation().ticks(
                new SimulationTickQuery(new ExecutionEpochId(0), 1, 1, 1)).complete());
    }

    @Test
    void publicTimelineRecordsRejectInternallyInconsistentEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new SimulationTick(
                new SimulationTickId(1), new ExecutionEpochId(0), 1,
                OptionalLong.of(1), 1, OptionalLong.empty(), 0,
                Optional.of(new FrameId(1)), SimulationTickSource.RUNNING,
                SimulationTickOutcome.COMPLETED, SimulationMutationOutcome.KNOWN_COMPLETED,
                Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new SimulationState(
                false, OptionalLong.empty(), new ExecutionEpochId(0), 0, 0, 0,
                Optional.empty(), OptionalLong.of(-1), OptionalLong.empty(), false,
                SimulationTimelineLimits.developmentDefaults()));

        SimulationTick tick = new SimulationTick(
                new SimulationTickId(1), new ExecutionEpochId(1), 1,
                OptionalLong.empty(), 1, OptionalLong.of(1), 1,
                Optional.of(new FrameId(1)), SimulationTickSource.RUNNING,
                SimulationTickOutcome.COMPLETED, SimulationMutationOutcome.KNOWN_COMPLETED,
                Optional.empty());
        SimulationTickQuery query = new SimulationTickQuery(
                new ExecutionEpochId(0), 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> new SimulationTickPage(
                query, List.of(tick), false, SimulationTickRangeStatus.COMPLETE,
                Optional.of(new SimulationTickId(1)), Optional.of(new SimulationTickId(1))));
    }

    private static AgentRuntime runtime(String sessionId) {
        return AgentRuntime.builder()
                .sessionId(SessionId.of(sessionId))
                .clock(new IncrementingClock())
                .build();
    }

    private static final class IncrementingClock implements MonotonicClock {
        private long value;

        @Override
        public long nanoTime() {
            return ++value;
        }
    }

}
