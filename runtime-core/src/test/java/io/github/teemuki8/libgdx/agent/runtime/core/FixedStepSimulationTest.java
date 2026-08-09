package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class FixedStepSimulationTest {
    @Test
    void updateUsesIntegerAccumulatorAndCorrelatesEveryCompletedTick() {
        AgentRuntime runtime = runtime("fixed-step-update");
        runtime.fixedStepSimulation().register(
                configuration(10, 25, 30, 2, 4), supplied -> supplied);
        runtime.start();

        FixedStepUpdateReport first = runtime.fixedStepSimulation().update(5);
        FixedStepUpdateReport second = runtime.fixedStepSimulation().update(20);

        assertEquals(0, first.ticksCompleted());
        assertEquals(5, first.accumulatorRemainderNanos());
        assertEquals(2, second.ticksAttempted());
        assertEquals(2, second.ticksCompleted());
        assertEquals(5, second.accumulatorRemainderNanos());
        assertEquals(0.5, second.interpolationAlpha());
        assertEquals(Optional.of(new SimulationTickId(1)), second.firstSimulationTickId());
        assertEquals(Optional.of(new SimulationTickId(2)), second.finalSimulationTickId());
        assertEquals(Optional.of(new FrameId(1)), second.firstRuntimeFrameId());
        assertEquals(Optional.of(new FrameId(2)), second.finalRuntimeFrameId());
        assertEquals(List.of(), second.diagnostics());
        assertEquals(2, runtime.simulation().state().completedEpochTicks());
    }

    @Test
    void clampAccumulatorAndCatchUpDropsAreExplicitAndKeepOnlyRemainder() {
        AgentRuntime runtime = runtime("fixed-step-drops");
        runtime.fixedStepSimulation().register(
                configuration(10, 25, 30, 2, 4), supplied -> supplied);
        runtime.start();
        runtime.fixedStepSimulation().update(9);

        FixedStepUpdateReport report = runtime.fixedStepSimulation().update(26);

        assertEquals(25, report.acceptedRenderDeltaNanos());
        assertEquals(1, report.clampedRenderTimeNanos());
        assertEquals(4, report.accumulatorLimitDroppedTimeNanos());
        assertEquals(10, report.catchUpDroppedTimeNanos());
        assertEquals(1, report.droppedTicks());
        assertEquals(0, report.accumulatorRemainderNanos());
        assertEquals(List.of(FixedStepUpdateDiagnostic.RENDER_DELTA_CLAMPED,
                FixedStepUpdateDiagnostic.ACCUMULATOR_TIME_DROPPED,
                FixedStepUpdateDiagnostic.CATCH_UP_TICKS_DROPPED), report.diagnostics());

        FixedStepUpdatePage page = runtime.fixedStepSimulation().updates(
                new FixedStepUpdateQuery(1, 2, 2));
        assertEquals(List.of(1L, 2L), page.reports().stream()
                .map(FixedStepUpdateReport::updateSequence).toList());
        assertTrue(!page.partiallyEvicted());
    }

    @Test
    void pauseFreezesRemainderAndResumeContinuesNormalAccumulation() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("fixed-step-pause"))
                .clock(new IncrementingClock())
                .commandDispatcher(Runnable::run)
                .build();
        runtime.fixedStepSimulation().register(
                configuration(10, 20, 20, 2, 4), supplied -> supplied);
        runtime.start();
        runtime.fixedStepSimulation().update(5);

        runtime.controls().control(true, "pause", java.time.Duration.ofSeconds(1));
        FixedStepUpdateReport paused = runtime.fixedStepSimulation().update(20);
        runtime.controls().control(false, "resume", java.time.Duration.ofSeconds(1));
        FixedStepUpdateReport resumed = runtime.fixedStepSimulation().update(5);

        assertEquals(20, paused.pausedIgnoredTimeNanos());
        assertEquals(5, paused.accumulatorRemainderNanos());
        assertEquals(List.of(FixedStepUpdateDiagnostic.PAUSED_RENDER_TIME_IGNORED),
                paused.diagnostics());
        assertEquals(1, resumed.ticksCompleted());
        assertEquals(0, resumed.accumulatorRemainderNanos());
    }

    @Test
    void configuredStepAdvanceBypassesAndPreservesRenderAccumulator() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("fixed-step-control"))
                .clock(new IncrementingClock())
                .commandDispatcher(Runnable::run)
                .build();
        runtime.fixedStepSimulation().register(
                configuration(10, 20, 20, 2, 4), supplied -> supplied);
        runtime.start();
        runtime.fixedStepSimulation().update(5);
        runtime.controls().control(true, "pause", java.time.Duration.ofSeconds(1));

        ControlOperation operation = runtime.controls().advanceFixed(
                "fixed-advance", 2, java.time.Duration.ofSeconds(1));

        assertEquals(2, operation.completedTicks());
        assertEquals(ControlStopReason.COMPLETED, operation.stopReason());
        assertEquals(5, runtime.fixedStepSimulation().state().accumulatorRemainderNanos());
        assertEquals(2, runtime.simulation().state().completedEpochTicks());
        assertEquals(2, runtime.controls().currentTick());
    }

    @Test
    void callbackFailureConsumesAttemptAndDropsRemainingWholeTimeBeforeRethrow() {
        AgentRuntime runtime = runtime("fixed-step-failure");
        runtime.fixedStepSimulation().register(
                configuration(10, 30, 30, 3, 4), supplied -> {
                    throw new IllegalStateException("private application failure");
                });
        runtime.start();

        assertThrows(IllegalStateException.class,
                () -> runtime.fixedStepSimulation().update(25));

        FixedStepUpdateReport report = runtime.fixedStepSimulation().updates(
                new FixedStepUpdateQuery(1, 1, 1)).reports().getFirst();
        assertEquals(1, report.ticksAttempted());
        assertEquals(0, report.ticksCompleted());
        assertEquals(10, report.catchUpDroppedTimeNanos());
        assertEquals(5, report.accumulatorRemainderNanos());
        assertEquals(Optional.of(new SimulationTickId(1)), report.firstSimulationTickId());
        assertEquals(Optional.of(new FrameId(1)), report.firstRuntimeFrameId());
        assertEquals(List.of(FixedStepUpdateDiagnostic.APPLICATION_CALLBACK_FAILED,
                FixedStepUpdateDiagnostic.TICK_FAILED,
                FixedStepUpdateDiagnostic.CATCH_UP_TICKS_DROPPED), report.diagnostics());
        assertTrue(report.diagnostics().stream()
                .noneMatch(value -> value.name().contains("private")));
    }

    @Test
    void nestedUpdateIsRejectedAndRetainedAsTickFailure() {
        AgentRuntime runtime = runtime("fixed-step-reentrant");
        runtime.fixedStepSimulation().register(
                configuration(10, 10, 10, 1, 4), supplied -> {
                    runtime.fixedStepSimulation().update(0);
                    return supplied;
                });
        runtime.start();

        assertThrows(IllegalStateException.class,
                () -> runtime.fixedStepSimulation().update(10));
        assertEquals(List.of(FixedStepUpdateDiagnostic.APPLICATION_CALLBACK_FAILED,
                        FixedStepUpdateDiagnostic.TICK_FAILED),
                runtime.fixedStepSimulation().updates(
                        new FixedStepUpdateQuery(1, 1, 1)).reports().getFirst().diagnostics());
    }

    @Test
    void captureAndPostValidationFailuresRetainTypedTickCorrelation() {
        long[] clockCalls = {0};
        AgentRuntime captureFailure = AgentRuntime.builder()
                .sessionId(SessionId.of("fixed-step-capture-failure"))
                .clock(() -> clockCalls[0]++ == 0 ? 1 : -1)
                .build();
        captureFailure.fixedStepSimulation().register(
                configuration(10, 10, 10, 1, 2), supplied -> supplied);
        captureFailure.start();
        assertThrows(IllegalStateException.class,
                () -> captureFailure.fixedStepSimulation().update(10));
        FixedStepUpdateReport captured = captureFailure.fixedStepSimulation().updates(
                new FixedStepUpdateQuery(1, 1, 1)).reports().getFirst();
        assertEquals(Optional.of(new SimulationTickId(1)), captured.firstSimulationTickId());
        assertEquals(Optional.empty(), captured.firstRuntimeFrameId());
        assertEquals(List.of(FixedStepUpdateDiagnostic.RUNTIME_CAPTURE_FAILED,
                FixedStepUpdateDiagnostic.TICK_FAILED), captured.diagnostics());

        AgentRuntime invalidDelta = runtime("fixed-step-invalid-report");
        invalidDelta.fixedStepSimulation().register(
                configuration(10, 10, 10, 1, 2), supplied -> -1);
        invalidDelta.start();
        assertThrows(AgentRuntimeException.class,
                () -> invalidDelta.fixedStepSimulation().update(10));
        FixedStepUpdateReport invalid = invalidDelta.fixedStepSimulation().updates(
                new FixedStepUpdateQuery(1, 1, 1)).reports().getFirst();
        assertEquals(1, invalid.ticksCompleted());
        assertEquals(Optional.of(new FrameId(1)), invalid.firstRuntimeFrameId());
        assertEquals(List.of(FixedStepUpdateDiagnostic.EXECUTED_DELTA_INVALID,
                FixedStepUpdateDiagnostic.TICK_FAILED), invalid.diagnostics());

        AgentRuntime oversizedDelta = runtime("fixed-step-oversized-report");
        oversizedDelta.fixedStepSimulation().register(
                configuration(10, 10, 10, 1, 2), supplied -> Long.MAX_VALUE);
        oversizedDelta.start();
        assertThrows(AgentRuntimeException.class,
                () -> oversizedDelta.fixedStepSimulation().update(10));
        assertEquals(List.of(FixedStepUpdateDiagnostic.EXECUTED_DELTA_INVALID,
                        FixedStepUpdateDiagnostic.TICK_FAILED),
                oversizedDelta.fixedStepSimulation().updates(
                        new FixedStepUpdateQuery(1, 1, 1)).reports().getFirst().diagnostics());

        AgentRuntime timeLimit = AgentRuntime.builder()
                .sessionId(SessionId.of("fixed-step-time-limit"))
                .clock(new IncrementingClock())
                .simulationTimelineLimits(new SimulationTimelineLimits(4, 4, 10, 15, 64))
                .build();
        timeLimit.fixedStepSimulation().register(
                configuration(10, 20, 20, 2, 2), supplied -> supplied);
        timeLimit.start();
        assertThrows(AgentRuntimeException.class,
                () -> timeLimit.fixedStepSimulation().update(20));
        FixedStepUpdateReport limited = timeLimit.fixedStepSimulation().updates(
                new FixedStepUpdateQuery(1, 1, 1)).reports().getFirst();
        assertEquals(2, limited.ticksAttempted());
        assertEquals(2, limited.ticksCompleted());
        assertEquals(Optional.of(new SimulationTickId(1)), limited.firstSimulationTickId());
        assertEquals(Optional.of(new SimulationTickId(2)), limited.finalSimulationTickId());
        assertEquals(List.of(FixedStepUpdateDiagnostic.SIMULATION_TIME_LIMIT_EXCEEDED,
                FixedStepUpdateDiagnostic.TICK_FAILED), limited.diagnostics());
    }

    @Test
    void legacyCallbackMismatchAndDisabledModeRemainHonest() {
        AgentRuntime legacy = runtime("fixed-step-legacy");
        legacy.fixedStepSimulation().registerUnacknowledged(
                new FixedStepSimulationConfiguration(10, 10, 10, 1, 2,
                        FixedStepDropPolicy.DROP_WHOLE_TICKS_KEEP_REMAINDER, false),
                supplied -> {});
        legacy.start();
        assertEquals(List.of(FixedStepUpdateDiagnostic.EXECUTED_DELTA_UNACKNOWLEDGED),
                legacy.fixedStepSimulation().update(10).diagnostics());
        assertEquals(SimulationTickOutcome.UNACKNOWLEDGED,
                legacy.simulation().ticks(new SimulationTickQuery(
                        new ExecutionEpochId(0), 1, 1, 1)).ticks().getFirst().outcome());

        AgentRuntime mismatch = runtime("fixed-step-mismatch");
        mismatch.fixedStepSimulation().register(
                configuration(10, 10, 10, 1, 2), supplied -> supplied - 1);
        mismatch.start();
        assertEquals(List.of(FixedStepUpdateDiagnostic.EXECUTED_DELTA_MISMATCH),
                mismatch.fixedStepSimulation().update(10).diagnostics());

        int[] callbacks = {0};
        AgentRuntime disabled = AgentRuntime.builder()
                .sessionId(SessionId.of("fixed-step-disabled"))
                .configuration(RuntimeConfiguration.disabled())
                .build();
        disabled.fixedStepSimulation().register(
                configuration(10, 10, 10, 1, 2), supplied -> {
                    callbacks[0]++;
                    return supplied;
                });
        disabled.start();
        assertEquals(1, disabled.fixedStepSimulation().update(10).ticksCompleted());
        assertEquals(1, callbacks[0]);
        assertEquals(List.of(), disabled.fixedStepSimulation().updates(
                new FixedStepUpdateQuery(1, 1, 1)).reports());
    }

    @Test
    void lifecycleThreadRestoreAndEvictionAreBoundedAndExplicit() throws Exception {
        AgentRuntime runtime = runtime("fixed-step-lifecycle");
        runtime.fixedStepSimulation().register(
                configuration(10, 10, 10, 1, 2), supplied -> supplied);
        assertThrows(AgentRuntimeException.class,
                () -> runtime.fixedStepSimulation().update(0));
        runtime.start();
        runtime.fixedStepSimulation().restoreAccumulator(4);
        assertEquals(0.4, runtime.fixedStepSimulation().interpolationAlpha());
        assertThrows(IllegalArgumentException.class,
                () -> runtime.fixedStepSimulation().restoreAccumulator(10));

        AtomicReference<Throwable> wrongThread = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                runtime.fixedStepSimulation().update(0);
            } catch (Throwable failure) {
                wrongThread.set(failure);
            }
        });
        thread.start();
        thread.join();
        assertEquals(RuntimeErrorCode.WRONG_THREAD,
                ((AgentRuntimeException) wrongThread.get()).code());

        runtime.fixedStepSimulation().clearAccumulator();
        runtime.fixedStepSimulation().update(0);
        runtime.fixedStepSimulation().update(0);
        runtime.fixedStepSimulation().update(0);
        FixedStepUpdatePage page = runtime.fixedStepSimulation().updates(
                new FixedStepUpdateQuery(1, 3, 2));
        assertTrue(page.partiallyEvicted());
        assertEquals(List.of(2L, 3L), page.reports().stream()
                .map(FixedStepUpdateReport::updateSequence).toList());

        runtime.close();
        assertEquals(2, runtime.fixedStepSimulation().updates(
                new FixedStepUpdateQuery(2, 3, 2)).reports().size());
        assertThrows(AgentRuntimeException.class,
                () -> runtime.fixedStepSimulation().update(0));
    }

    @Test
    void configurationRejectsInvalidAndOverflowProneAccumulatorSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> configuration(0, 20, 20, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> configuration(10, 9, 20, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> configuration(10, 20, 9, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> configuration(10, 20, 20, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> configuration(10, 20, 20, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> configuration(10, Long.MAX_VALUE, Long.MAX_VALUE, 1, 1));

        FixedStepSimulationConfiguration valid = configuration(10, 20, 30, 2, 4);
        assertEquals(10, valid.fixedStepNanos());
        assertEquals(FixedStepDropPolicy.DROP_WHOLE_TICKS_KEEP_REMAINDER,
                valid.dropPolicy());
    }

    @Test
    void registrationRejectsStepOutsideControlledAdvanceLimit() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("fixed-step-control-limit"))
                .controlLimits(new ControlLimits(4, 4, 4, 9))
                .build();

        AgentRuntimeException failure = assertThrows(AgentRuntimeException.class,
                () -> runtime.fixedStepSimulation().register(
                        configuration(10, 10, 10, 1, 2), supplied -> supplied));

        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED, failure.code());
        assertFalse(runtime.simulation().state().configured());
        assertFalse(runtime.controls().available());
    }

    @Test
    void publicReportAndPageDefensivelyCopyAndValidateEvidence() {
        ArrayList<FixedStepUpdateDiagnostic> diagnostics = new ArrayList<>(List.of(
                FixedStepUpdateDiagnostic.RENDER_DELTA_CLAMPED));
        FixedStepUpdateReport report = report(diagnostics);
        diagnostics.clear();
        assertEquals(List.of(FixedStepUpdateDiagnostic.RENDER_DELTA_CLAMPED),
                report.diagnostics());

        FixedStepUpdateQuery query = new FixedStepUpdateQuery(1, 1, 1);
        ArrayList<FixedStepUpdateReport> reports = new ArrayList<>(List.of(report));
        FixedStepUpdatePage page = new FixedStepUpdatePage(query, reports, false, false,
                OptionalLong.of(1), OptionalLong.of(1));
        reports.clear();
        assertEquals(1, page.reports().size());

        assertThrows(IllegalArgumentException.class, () -> new FixedStepUpdateQuery(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FixedStepUpdatePage(
                query, List.of(report, report), false, false,
                OptionalLong.of(1), OptionalLong.of(1)));

        FixedStepSimulationConfiguration configuration = configuration(10, 10, 10, 1, 2);
        assertThrows(IllegalArgumentException.class, () -> new FixedStepSimulationState(
                true, Optional.of(configuration), 10, 0.0, false,
                OptionalLong.empty(), 0));
        assertThrows(IllegalArgumentException.class, () -> new FixedStepSimulationState(
                true, Optional.of(configuration), 0, 0.0, false,
                OptionalLong.empty(), 1));
        assertThrows(IllegalArgumentException.class, () -> new FixedStepSimulationState(
                true, Optional.of(configuration), 0, 0.0, false,
                OptionalLong.of(1), 3));
        assertThrows(IllegalArgumentException.class, () -> new FixedStepSimulationState(
                true, Optional.of(configuration), 5, 0.4, false,
                OptionalLong.of(1), 1));
        assertThrows(IllegalArgumentException.class, () -> new FixedStepSimulationState(
                true, Optional.of(configuration), 0, 0.0, false,
                OptionalLong.of(0), 1));
        assertThrows(IllegalArgumentException.class, () -> new FixedStepUpdateReport(
                1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0,
                Optional.of(new SimulationTickId(1)), Optional.of(new SimulationTickId(1)),
                Optional.empty(), Optional.empty(), false, List.of()));
    }

    private static FixedStepSimulationConfiguration configuration(long fixedStep,
            long maximumRenderDelta, long maximumAccumulatedTime, int maximumCatchUpTicks,
            int retainedReports) {
        return new FixedStepSimulationConfiguration(fixedStep, maximumRenderDelta,
                maximumAccumulatedTime, maximumCatchUpTicks, retainedReports,
                FixedStepDropPolicy.DROP_WHOLE_TICKS_KEEP_REMAINDER, true);
    }

    private static FixedStepUpdateReport report(List<FixedStepUpdateDiagnostic> diagnostics) {
        return new FixedStepUpdateReport(1, 20, 10, 10, 0, 0, 0, 0,
                1, 1, 0, 0.0, Optional.of(new SimulationTickId(1)),
                Optional.of(new SimulationTickId(1)), Optional.of(new FrameId(1)),
                Optional.of(new FrameId(1)), false, diagnostics);
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
