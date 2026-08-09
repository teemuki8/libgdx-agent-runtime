package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongConsumer;

/** Canonical application-owned fixed-step accumulator and bounded update evidence registry. */
public final class FixedStepSimulationRegistry {
    private final AgentRuntime runtime;
    private final ArrayDeque<FixedStepUpdateReport> reports = new ArrayDeque<>();
    private FixedStepSimulationConfiguration configuration;
    private SimulationTickCallback acknowledgedCallback;
    private LongConsumer unacknowledgedCallback;
    private long accumulatorNanos;
    private long nextUpdateSequence;
    private boolean paused;
    private boolean mutationActive;

    FixedStepSimulationRegistry(AgentRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** Registers one acknowledged application callback and canonical fixed-step configuration. */
    public synchronized void register(FixedStepSimulationConfiguration value,
            SimulationTickCallback callback) {
        registerInternal(value, Objects.requireNonNull(callback, "callback"), null);
    }

    /** Registers one legacy callback that cannot acknowledge its actually executed delta. */
    public synchronized void registerUnacknowledged(FixedStepSimulationConfiguration value,
            LongConsumer callback) {
        Objects.requireNonNull(value, "value");
        if (value.acknowledgementRequired()) {
            throw new IllegalArgumentException("fixed-step configuration requires acknowledgement");
        }
        registerInternal(value, null, Objects.requireNonNull(callback, "callback"));
    }

    /** Advances the normal accumulator using one non-negative render delta in nanoseconds. */
    public synchronized FixedStepUpdateReport update(long renderDeltaNanos) {
        if (mutationActive) {
            throw new IllegalStateException("fixed-step mutation is already active");
        }
        runtime.requireFixedStepMutation();
        requireConfigured();
        if (renderDeltaNanos < 0) {
            throw new IllegalArgumentException("render delta must be non-negative");
        }
        beginMutation();
        Throwable tickFailure = null;
        try {
            long sequence = nextSequence();
            if (paused) {
                FixedStepUpdateReport report = report(sequence, renderDeltaNanos, 0, 0,
                        renderDeltaNanos, 0, 0, 0, 0, 0,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        List.of(FixedStepUpdateDiagnostic.PAUSED_RENDER_TIME_IGNORED));
                retain(report);
                return report;
            }

            ArrayList<FixedStepUpdateDiagnostic> diagnostics = new ArrayList<>();
            long accepted = Math.min(renderDeltaNanos, configuration.maximumRenderDeltaNanos());
            long clamped = renderDeltaNanos - accepted;
            if (clamped > 0) {
                diagnostics.add(FixedStepUpdateDiagnostic.RENDER_DELTA_CLAMPED);
            }
            long accumulated = Math.addExact(accumulatorNanos, accepted);
            long accumulatorDrop = Math.max(
                    0, accumulated - configuration.maximumAccumulatedTimeNanos());
            accumulatorNanos = Math.min(
                    accumulated, configuration.maximumAccumulatedTimeNanos());
            if (accumulatorDrop > 0) {
                diagnostics.add(FixedStepUpdateDiagnostic.ACCUMULATOR_TIME_DROPPED);
            }

            int attempted = 0;
            int completed = 0;
            Optional<SimulationTickId> firstTick = Optional.empty();
            Optional<SimulationTickId> finalTick = Optional.empty();
            Optional<FrameId> firstFrame = Optional.empty();
            Optional<FrameId> finalFrame = Optional.empty();
            while (accumulatorNanos >= configuration.fixedStepNanos()
                    && attempted < configuration.maximumCatchUpTicks()) {
                accumulatorNanos -= configuration.fixedStepNanos();
                attempted++;
                try {
                    SimulationTimelineRegistry.TickExecution execution = executeNormalTick();
                    if (execution.tick().isPresent()) {
                        SimulationTick retained = execution.tick().orElseThrow();
                        if (completedOutcome(retained.outcome())) {
                            completed++;
                        }
                        firstTick = firstTick.isPresent()
                                ? firstTick : Optional.of(retained.simulationTickId());
                        finalTick = Optional.of(retained.simulationTickId());
                        if (retained.resultingFrameId().isPresent()) {
                            firstFrame = firstFrame.isPresent()
                                    ? firstFrame : retained.resultingFrameId();
                            finalFrame = retained.resultingFrameId();
                        }
                        addTimingDiagnostic(diagnostics, retained.outcome());
                    } else if (unacknowledgedCallback != null) {
                        completed++;
                        addDiagnostic(diagnostics,
                                FixedStepUpdateDiagnostic.EXECUTED_DELTA_UNACKNOWLEDGED);
                    } else {
                        completed++;
                    }
                    if (execution.failure().isPresent()) {
                        tickFailure = execution.failure().orElseThrow();
                        addDiagnostic(diagnostics, FixedStepUpdateDiagnostic.TICK_FAILED);
                        break;
                    }
                } catch (RuntimeException | Error failure) {
                    tickFailure = failure;
                    addDiagnostic(diagnostics, FixedStepUpdateDiagnostic.TICK_FAILED);
                    break;
                }
            }

            long droppedTicks = accumulatorNanos / configuration.fixedStepNanos();
            long catchUpDrop = Math.multiplyExact(
                    droppedTicks, configuration.fixedStepNanos());
            accumulatorNanos -= catchUpDrop;
            if (catchUpDrop > 0) {
                addDiagnostic(diagnostics, FixedStepUpdateDiagnostic.CATCH_UP_TICKS_DROPPED);
            }
            FixedStepUpdateReport report = report(sequence, renderDeltaNanos, accepted, clamped,
                    0, accumulatorDrop, catchUpDrop, droppedTicks, attempted, completed,
                    firstTick, finalTick, firstFrame, finalFrame, diagnostics);
            retain(report);
            if (tickFailure != null) {
                FixedStepSimulationRegistry.<RuntimeException>throwUnchecked(tickFailure);
            }
            return report;
        } finally {
            mutationActive = false;
        }
    }

    /** Clears the normal accumulator without creating a simulation tick. */
    public synchronized void clearAccumulator() {
        restoreAccumulator(0);
    }

    /** Restores one explicit sub-step accumulator remainder without creating a tick. */
    public synchronized void restoreAccumulator(long remainderNanos) {
        runtime.requireFixedStepMutation();
        requireConfigured();
        if (mutationActive) {
            throw new IllegalStateException("fixed-step mutation is already active");
        }
        if (remainderNanos < 0 || remainderNanos >= configuration.fixedStepNanos()) {
            throw new IllegalArgumentException("restored accumulator must be a sub-step remainder");
        }
        accumulatorNanos = remainderNanos;
    }

    /** Returns immutable current accumulator state. */
    public synchronized FixedStepSimulationState state() {
        if (configuration == null) {
            return new FixedStepSimulationState(false, Optional.empty(), 0, 0.0, false,
                    OptionalLong.empty(), 0);
        }
        return new FixedStepSimulationState(true, Optional.of(configuration), accumulatorNanos,
                interpolationAlpha(), paused,
                reports.isEmpty() ? OptionalLong.empty()
                        : OptionalLong.of(reports.peekLast().updateSequence()),
                reports.size());
    }

    /** Returns one bounded ordered update-report page. Safe for concurrent readers. */
    public synchronized FixedStepUpdatePage updates(FixedStepUpdateQuery query) {
        Objects.requireNonNull(query, "query");
        int maximum = configuration == null ? 0 : configuration.retainedUpdateReports();
        if (maximum == 0 || query.limit() > maximum) {
            if (configuration == null) {
                return new FixedStepUpdatePage(query, List.of(), false, false,
                        OptionalLong.empty(), OptionalLong.empty());
            }
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "fixed-step update query exceeds the configured report limit");
        }
        List<FixedStepUpdateReport> matching = reports.stream()
                .filter(report -> report.updateSequence() >= query.fromSequence()
                        && report.updateSequence() <= query.toSequence())
                .toList();
        boolean hasMore = matching.size() > query.limit();
        List<FixedStepUpdateReport> page = matching.stream().limit(query.limit()).toList();
        OptionalLong oldest = reports.isEmpty() ? OptionalLong.empty()
                : OptionalLong.of(reports.peekFirst().updateSequence());
        OptionalLong newest = reports.isEmpty() ? OptionalLong.empty()
                : OptionalLong.of(reports.peekLast().updateSequence());
        boolean partiallyEvicted = oldest.isPresent()
                && query.fromSequence() < oldest.orElseThrow();
        return new FixedStepUpdatePage(query, page, hasMore, partiallyEvicted, oldest, newest);
    }

    /** Returns current interpolation alpha without advancing authoritative state. */
    public synchronized double interpolationAlpha() {
        return configuration == null ? 0.0
                : (double) accumulatorNanos / configuration.fixedStepNanos();
    }

    synchronized void close() {
        acknowledgedCallback = null;
        unacknowledgedCallback = null;
        mutationActive = false;
        paused = false;
    }

    private void registerInternal(FixedStepSimulationConfiguration value,
            SimulationTickCallback acknowledged, LongConsumer unacknowledged) {
        runtime.requireFixedStepRegistration();
        Objects.requireNonNull(value, "value");
        if (configuration != null) {
            throw new IllegalStateException("fixed-step simulation is already registered");
        }
        if (value.fixedStepNanos() > runtime.controls().limits().maximumDeltaNanos()) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "fixed step exceeds the configured simulation-control delta limit");
        }
        if (runtime.simulation().state().configured() || runtime.controls().available()) {
            throw new IllegalStateException(
                    "fixed-step simulation requires unregistered timing and control");
        }
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(value.fixedStepNanos()));
        SimulationControllerSpec.Builder control = SimulationControllerSpec.builder()
                .pause(() -> setPaused(true))
                .resume(() -> setPaused(false));
        if (acknowledged != null) {
            control.acknowledgedTick(this::executeControlledAcknowledged);
        } else {
            control.tick(this::executeControlledUnacknowledged);
        }
        runtime.controls().register(control.build());
        configuration = value;
        acknowledgedCallback = acknowledged;
        unacknowledgedCallback = unacknowledged;
    }

    private SimulationTimelineRegistry.TickExecution executeNormalTick() {
        if (acknowledgedCallback != null) {
            return runtime.simulation().tickObserved(
                    configuration.fixedStepNanos(), acknowledgedCallback);
        }
        return runtime.simulation().tickUnacknowledgedObserved(
                configuration.fixedStepNanos(), unacknowledgedCallback);
    }

    private long executeControlledAcknowledged(long suppliedDeltaNanos) {
        beginMutation();
        try {
            return acknowledgedCallback.simulate(suppliedDeltaNanos);
        } finally {
            mutationActive = false;
        }
    }

    private void executeControlledUnacknowledged(long suppliedDeltaNanos) {
        beginMutation();
        try {
            unacknowledgedCallback.accept(suppliedDeltaNanos);
        } finally {
            mutationActive = false;
        }
    }

    private void beginMutation() {
        if (mutationActive) {
            throw new IllegalStateException("fixed-step mutation is already active");
        }
        mutationActive = true;
    }

    private void setPaused(boolean value) {
        synchronized (this) {
            paused = value;
        }
    }

    private long nextSequence() {
        try {
            nextUpdateSequence = Math.addExact(nextUpdateSequence, 1);
            return nextUpdateSequence;
        } catch (ArithmeticException failure) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "fixed-step update sequence overflowed");
        }
    }

    private FixedStepUpdateReport report(long sequence, long supplied, long accepted,
            long clamped, long pausedIgnored, long accumulatorDrop, long catchUpDrop,
            long droppedTicks, int attempted, int completed,
            Optional<SimulationTickId> firstTick, Optional<SimulationTickId> finalTick,
            Optional<FrameId> firstFrame, Optional<FrameId> finalFrame,
            List<FixedStepUpdateDiagnostic> diagnostics) {
        return new FixedStepUpdateReport(sequence, supplied, accepted, clamped, pausedIgnored,
                accumulatorDrop, catchUpDrop, droppedTicks, attempted, completed,
                accumulatorNanos, interpolationAlpha(), firstTick, finalTick,
                firstFrame, finalFrame, paused, diagnostics);
    }

    private void retain(FixedStepUpdateReport report) {
        if (runtime.status() == RuntimeStatus.DISABLED) {
            return;
        }
        reports.addLast(report);
        while (reports.size() > configuration.retainedUpdateReports()) {
            reports.removeFirst();
        }
    }

    private static void addTimingDiagnostic(List<FixedStepUpdateDiagnostic> diagnostics,
            SimulationTickOutcome outcome) {
        switch (outcome) {
            case DELTA_MISMATCH -> addDiagnostic(
                    diagnostics, FixedStepUpdateDiagnostic.EXECUTED_DELTA_MISMATCH);
            case UNACKNOWLEDGED -> addDiagnostic(
                    diagnostics, FixedStepUpdateDiagnostic.EXECUTED_DELTA_UNACKNOWLEDGED);
            case REPORTED_DELTA_INVALID -> addDiagnostic(
                    diagnostics, FixedStepUpdateDiagnostic.EXECUTED_DELTA_INVALID);
            case TIME_LIMIT_EXCEEDED -> addDiagnostic(
                    diagnostics, FixedStepUpdateDiagnostic.SIMULATION_TIME_LIMIT_EXCEEDED);
            case CALLBACK_FAILED -> addDiagnostic(
                    diagnostics, FixedStepUpdateDiagnostic.APPLICATION_CALLBACK_FAILED);
            case CAPTURE_FAILED -> addDiagnostic(
                    diagnostics, FixedStepUpdateDiagnostic.RUNTIME_CAPTURE_FAILED);
            case CALLBACK_AND_CAPTURE_FAILED -> {
                addDiagnostic(diagnostics, FixedStepUpdateDiagnostic.APPLICATION_CALLBACK_FAILED);
                addDiagnostic(diagnostics, FixedStepUpdateDiagnostic.RUNTIME_CAPTURE_FAILED);
            }
            case COMPLETED -> {
                // No diagnostic.
            }
        }
    }

    private static boolean completedOutcome(SimulationTickOutcome outcome) {
        return switch (outcome) {
            case COMPLETED, DELTA_MISMATCH, UNACKNOWLEDGED,
                    REPORTED_DELTA_INVALID, TIME_LIMIT_EXCEEDED -> true;
            case CALLBACK_FAILED, CAPTURE_FAILED, CALLBACK_AND_CAPTURE_FAILED -> false;
        };
    }

    private static void addDiagnostic(List<FixedStepUpdateDiagnostic> diagnostics,
            FixedStepUpdateDiagnostic diagnostic) {
        if (!diagnostics.contains(diagnostic)) {
            diagnostics.add(diagnostic);
        }
    }

    private void requireConfigured() {
        if (configuration == null || acknowledgedCallback == null && unacknowledgedCallback == null) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.INVALID_LIFECYCLE, "fixed-step simulation is not configured");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }
}
