package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Application-owned simulation tick boundary and bounded immutable timeline. */
public final class SimulationTimelineRegistry {
    private final AgentRuntime runtime;
    private final SimulationTimelineLimits limits;
    private final ArrayDeque<SimulationTick> history = new ArrayDeque<>();
    private final LinkedHashMap<ExecutionEpochId, EpochSummary> epochs = new LinkedHashMap<>();
    private SimulationTimelineSpec spec;
    private ExecutionEpochId currentEpoch = new ExecutionEpochId(0);
    private long nextSimulationTickId;
    private long attemptedEpochTicks;
    private long completedEpochTicks;
    private long epochSimulationTimeNanos;
    private Optional<SimulationTickId> latestTickId = Optional.empty();
    private OptionalLong lastRuntimeSuppliedDeltaNanos = OptionalLong.empty();
    private OptionalLong lastExecutedDeltaNanos = OptionalLong.empty();

    SimulationTimelineRegistry(AgentRuntime runtime, SimulationTimelineLimits limits) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.limits = Objects.requireNonNull(limits, "limits");
        epochs.put(currentEpoch, new EpochSummary());
    }

    /** Registers one fixed-step timing contract before simulation ticks begin. */
    public synchronized void register(SimulationTimelineSpec value) {
        runtime.requireSimulationTimelineRegistration();
        Objects.requireNonNull(value, "value");
        if (spec != null) {
            throw new IllegalStateException("simulation timeline timing is already registered");
        }
        if (value.fixedStepNanos() > limits.maximumDeltaNanos()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED,
                    "fixed simulation step exceeds the configured delta limit");
        }
        spec = value;
    }

    /** Returns configured hard timeline bounds. */
    public SimulationTimelineLimits limits() {
        return limits;
    }

    /** Returns immutable current timing and tick state. */
    public synchronized SimulationState state() {
        return new SimulationState(spec != null, fixedStep(), currentEpoch,
                attemptedEpochTicks, completedEpochTicks, epochSimulationTimeNanos,
                latestTickId, lastRuntimeSuppliedDeltaNanos, lastExecutedDeltaNanos,
                runtime.controls().paused(), limits);
    }

    /**
     * Executes one normal application-owned simulation tick and captures its resulting frame.
     *
     * <p>The callback must return the delta it actually executed. Disabled runtimes execute the
     * callback but retain no timeline or frame evidence.
     */
    public Optional<SimulationTick> tick(
            long runtimeSuppliedDeltaNanos, SimulationTickCallback callback) {
        TickExecution execution = tickObserved(runtimeSuppliedDeltaNanos, callback);
        rethrow(execution);
        return execution.tick();
    }

    TickExecution tickObserved(
            long runtimeSuppliedDeltaNanos, SimulationTickCallback callback) {
        Objects.requireNonNull(callback, "callback");
        if (!runtime.prepareSimulationTick()) {
            callback.simulate(runtimeSuppliedDeltaNanos);
            return new TickExecution(Optional.empty(), Optional.empty());
        }
        SimulationTickSource source = runtime.controls().paused()
                ? SimulationTickSource.PAUSED : SimulationTickSource.RUNNING;
        return execute(runtimeSuppliedDeltaNanos, source,
                OptionalLong.empty(), callback, true);
    }

    TickExecution tickUnacknowledgedObserved(
            long runtimeSuppliedDeltaNanos, java.util.function.LongConsumer callback) {
        Objects.requireNonNull(callback, "callback");
        if (!runtime.prepareSimulationTick()) {
            callback.accept(runtimeSuppliedDeltaNanos);
            return new TickExecution(Optional.empty(), Optional.empty());
        }
        SimulationTickSource source = runtime.controls().paused()
                ? SimulationTickSource.PAUSED : SimulationTickSource.RUNNING;
        return execute(runtimeSuppliedDeltaNanos, source,
                OptionalLong.empty(), supplied -> {
                    callback.accept(supplied);
                    return 0;
                }, false);
    }

    /** Returns one bounded epoch-relative timeline page. Safe for concurrent readers. */
    public synchronized SimulationTickPage ticks(SimulationTickQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.limit() > limits.queryPageSize()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED,
                    "simulation tick query limit exceeds the configured page size");
        }
        List<SimulationTick> matching = history.stream()
                .filter(tick -> tick.executionEpochId().equals(query.executionEpochId()))
                .filter(tick -> tick.epochTick() >= query.fromEpochTick()
                        && tick.epochTick() <= query.toEpochTick())
                .toList();
        boolean hasMore = matching.size() > query.limit();
        List<SimulationTick> page = matching.stream().limit(query.limit()).toList();
        EpochSummary summary = epochs.get(query.executionEpochId());
        SimulationTickRangeStatus status;
        if (summary == null
                && query.executionEpochId().compareTo(currentEpoch) > 0) {
            status = SimulationTickRangeStatus.NOT_YET_EXECUTED;
        } else if (summary == null || summary.evictedThrough >= query.fromEpochTick()) {
            status = SimulationTickRangeStatus.PARTIALLY_EVICTED;
        } else if (hasMore) {
            status = SimulationTickRangeStatus.PAGINATED;
        } else if (query.toEpochTick() > summary.attempted) {
            status = SimulationTickRangeStatus.NOT_YET_EXECUTED;
        } else {
            status = SimulationTickRangeStatus.COMPLETE;
        }
        return new SimulationTickPage(query, page, hasMore, status,
                Optional.ofNullable(history.peekFirst()).map(SimulationTick::simulationTickId),
                Optional.ofNullable(history.peekLast()).map(SimulationTick::simulationTickId));
    }

    SimulationTick tickControlled(long runtimeSuppliedDeltaNanos, long controlledTick,
            Optional<SimulationTickCallback> acknowledgedCallback,
            java.util.function.LongConsumer legacyCallback) {
        Objects.requireNonNull(acknowledgedCallback, "acknowledgedCallback");
        Objects.requireNonNull(legacyCallback, "legacyCallback");
        if (!runtime.prepareSimulationTick()) {
            legacyCallback.accept(runtimeSuppliedDeltaNanos);
            throw new IllegalStateException("disabled runtime cannot retain controlled tick evidence");
        }
        SimulationTickCallback callback = acknowledgedCallback.orElseGet(() -> supplied -> {
            legacyCallback.accept(supplied);
            return supplied;
        });
        TickExecution execution = execute(runtimeSuppliedDeltaNanos, SimulationTickSource.PAUSED,
                OptionalLong.of(controlledTick), callback, acknowledgedCallback.isPresent());
        rethrow(execution);
        return execution.tick().orElseThrow();
    }

    synchronized void startEpoch(ExecutionEpochId epochId) {
        currentEpoch = Objects.requireNonNull(epochId, "epochId");
        attemptedEpochTicks = 0;
        completedEpochTicks = 0;
        epochSimulationTimeNanos = 0;
        epochs.put(epochId, new EpochSummary());
        while (epochs.size() > limits.retainedTicks()) {
            ExecutionEpochId oldest = epochs.keySet().iterator().next();
            if (oldest.equals(currentEpoch)) {
                break;
            }
            epochs.remove(oldest);
        }
    }

    private TickExecution execute(long suppliedDeltaNanos, SimulationTickSource source,
            OptionalLong controlledTick, SimulationTickCallback callback, boolean acknowledged) {
        validateSuppliedDelta(suppliedDeltaNanos);
        Attempt attempt = beginAttempt();
        FrameId expected = runtime.latestFrame().map(frame -> incrementFrame(frame.frameId()))
                .orElse(new FrameId(0));
        long[] reported = {0};
        Throwable[] applicationFailure = {null};
        Throwable failure = null;
        try {
            runtime.frame(suppliedDeltaNanos, () -> {
                try {
                    if (controlledTick.isPresent()) {
                        runtime.inputs().executeTick(
                                controlledTick.orElseThrow(), attempt.executionEpochId());
                    }
                    reported[0] = callback.simulate(suppliedDeltaNanos);
                } catch (Throwable thrown) {
                    applicationFailure[0] = thrown;
                    throw thrown;
                }
            });
        } catch (Throwable thrown) {
            failure = thrown;
        }
        Optional<FrameId> resultingFrame = runtime.frame(expected).isPresent()
                ? Optional.of(expected) : Optional.empty();
        if (controlledTick.isPresent()) {
            if (resultingFrame.isPresent()) {
                runtime.inputs().completeTick(controlledTick.orElseThrow(), expected);
            } else {
                runtime.inputs().failTick(controlledTick.orElseThrow());
            }
        }

        Completion completion = completeAttempt(attempt, suppliedDeltaNanos, source,
                acknowledged, reported[0], resultingFrame, applicationFailure[0], failure);
        Throwable retainedFailure = failure != null ? failure : completion.postFailure;
        return new TickExecution(Optional.of(completion.tick),
                Optional.ofNullable(retainedFailure));
    }

    private synchronized Attempt beginAttempt() {
        long nextId;
        long nextEpochTick;
        try {
            nextId = Math.addExact(nextSimulationTickId, 1);
            nextEpochTick = Math.addExact(attemptedEpochTicks, 1);
        } catch (ArithmeticException failure) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "simulation tick identity overflowed");
        }
        nextSimulationTickId = nextId;
        attemptedEpochTicks = nextEpochTick;
        return new Attempt(new SimulationTickId(nextSimulationTickId),
                currentEpoch, attemptedEpochTicks);
    }

    private synchronized Completion completeAttempt(Attempt attempt, long suppliedDeltaNanos,
            SimulationTickSource source, boolean acknowledged, long reportedDeltaNanos,
            Optional<FrameId> resultingFrame, Throwable applicationFailure, Throwable failure) {
        EpochSummary epochSummary = epochs.computeIfAbsent(
                attempt.executionEpochId(), ignored -> new EpochSummary());
        epochSummary.attempted = Math.max(epochSummary.attempted, attempt.epochTick());
        SimulationTickOutcome outcome;
        SimulationMutationOutcome mutationOutcome;
        OptionalLong executed = OptionalLong.empty();
        Optional<String> diagnostic = Optional.empty();
        AgentRuntimeException postFailure = null;

        if (failure != null) {
            mutationOutcome = applicationFailure == null
                    ? SimulationMutationOutcome.KNOWN_COMPLETED
                    : SimulationMutationOutcome.UNKNOWN;
            outcome = applicationFailure == null
                    ? SimulationTickOutcome.CAPTURE_FAILED
                    : resultingFrame.isPresent() ? SimulationTickOutcome.CALLBACK_FAILED
                            : SimulationTickOutcome.CALLBACK_AND_CAPTURE_FAILED;
            diagnostic = Optional.of(bounded(outcome == SimulationTickOutcome.CAPTURE_FAILED
                    ? "runtime capture failed after the simulation callback"
                    : "application simulation callback failed; mutation outcome is unknown"));
            if (applicationFailure == null && acknowledged
                    && reportedDeltaNanos >= 0
                    && reportedDeltaNanos <= limits.maximumDeltaNanos()) {
                executed = OptionalLong.of(reportedDeltaNanos);
                try {
                    long accumulated = Math.addExact(
                            epochSimulationTimeNanos, reportedDeltaNanos);
                    if (accumulated <= limits.maximumEpochSimulationTimeNanos()) {
                        epochSimulationTimeNanos = accumulated;
                    }
                } catch (ArithmeticException overflow) {
                    // Preserve the last authoritative time while retaining the capture failure.
                }
            }
        } else if (!acknowledged) {
            mutationOutcome = SimulationMutationOutcome.KNOWN_COMPLETED;
            outcome = SimulationTickOutcome.UNACKNOWLEDGED;
            diagnostic = Optional.of(bounded(
                    "legacy simulation callback did not report its executed delta"));
            completed(attempt);
        } else if (reportedDeltaNanos < 0 || reportedDeltaNanos > limits.maximumDeltaNanos()) {
            mutationOutcome = SimulationMutationOutcome.KNOWN_COMPLETED;
            outcome = SimulationTickOutcome.REPORTED_DELTA_INVALID;
            diagnostic = Optional.of(bounded(
                    "application-reported executed delta is outside the configured limit"));
            completed(attempt);
            postFailure = new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "application-reported executed delta is outside the configured limit");
        } else {
            mutationOutcome = SimulationMutationOutcome.KNOWN_COMPLETED;
            executed = OptionalLong.of(reportedDeltaNanos);
            long accumulated = epochSimulationTimeNanos;
            boolean timeOverflow = false;
            try {
                accumulated = Math.addExact(epochSimulationTimeNanos, reportedDeltaNanos);
            } catch (ArithmeticException overflow) {
                timeOverflow = true;
            }
            if (timeOverflow || accumulated > limits.maximumEpochSimulationTimeNanos()) {
                outcome = SimulationTickOutcome.TIME_LIMIT_EXCEEDED;
                diagnostic = Optional.of(bounded(
                        "application-reported simulation time exceeds the configured epoch limit"));
                completed(attempt);
                postFailure = new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                        "simulation epoch time exceeds the configured limit");
            } else {
                epochSimulationTimeNanos = accumulated;
                completed(attempt);
                boolean mismatch = reportedDeltaNanos != suppliedDeltaNanos
                        || spec != null && reportedDeltaNanos != spec.fixedStepNanos();
                outcome = mismatch ? SimulationTickOutcome.DELTA_MISMATCH
                        : SimulationTickOutcome.COMPLETED;
                if (mismatch) {
                    diagnostic = Optional.of(bounded(
                            "application-reported executed delta differs from the runtime-supplied "
                                    + "delta or configured fixed step"));
                }
            }
        }

        SimulationTick tick = new SimulationTick(attempt.id(), attempt.executionEpochId(),
                attempt.epochTick(), fixedStep(), suppliedDeltaNanos, executed,
                epochSimulationTimeNanos, resultingFrame, source, outcome, mutationOutcome,
                diagnostic);
        retain(tick);
        latestTickId = Optional.of(tick.simulationTickId());
        lastRuntimeSuppliedDeltaNanos = OptionalLong.of(suppliedDeltaNanos);
        lastExecutedDeltaNanos = executed;
        return new Completion(tick, postFailure);
    }

    private void completed(Attempt attempt) {
        if (attempt.executionEpochId().equals(currentEpoch)) {
            completedEpochTicks++;
        }
    }

    private void retain(SimulationTick tick) {
        history.addLast(tick);
        while (history.size() > limits.retainedTicks()) {
            SimulationTick evicted = history.removeFirst();
            EpochSummary summary = epochs.get(evicted.executionEpochId());
            if (summary != null) {
                summary.evictedThrough = Math.max(summary.evictedThrough, evicted.epochTick());
            }
        }
    }

    private OptionalLong fixedStep() {
        return spec == null ? OptionalLong.empty() : OptionalLong.of(spec.fixedStepNanos());
    }

    private void validateSuppliedDelta(long deltaNanos) {
        if (deltaNanos < 0 || deltaNanos > limits.maximumDeltaNanos()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED,
                    "runtime-supplied simulation delta is outside the configured limit");
        }
    }

    private FrameId incrementFrame(FrameId frameId) {
        try {
            return new FrameId(Math.addExact(frameId.value(), 1));
        } catch (ArithmeticException failure) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "runtime frame identity overflowed");
        }
    }

    private String bounded(String value) {
        return value.length() <= limits.diagnosticLength()
                ? value : value.substring(0, limits.diagnosticLength());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }

    private static void rethrow(TickExecution execution) {
        if (execution.failure().isPresent()) {
            SimulationTimelineRegistry.<RuntimeException>throwUnchecked(
                    execution.failure().orElseThrow());
        }
    }

    record TickExecution(Optional<SimulationTick> tick, Optional<Throwable> failure) {
        TickExecution {
            tick = Objects.requireNonNull(tick, "tick");
            failure = Objects.requireNonNull(failure, "failure");
        }
    }

    private record Attempt(SimulationTickId id, ExecutionEpochId executionEpochId, long epochTick) {}

    private record Completion(SimulationTick tick, AgentRuntimeException postFailure) {}

    private static final class EpochSummary {
        private long attempted;
        private long evictedThrough;
    }
}
