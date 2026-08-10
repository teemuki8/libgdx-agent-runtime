package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded replay-ready recording capture and deterministic replay execution registry. */
public final class ReplayRegistry {
    private final AgentRuntime runtime;
    private final ReplayLimits limits;
    private final ObservableEvidenceComparator comparator;
    private final LinkedHashMap<String, CaptureEvidence> captureOperations =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, RetainedReplay> retainedReplays =
            new LinkedHashMap<>();
    private final ArrayDeque<String> evictedRecordingIds = new ArrayDeque<>();
    private MutableCapture activeCapture;

    ReplayRegistry(AgentRuntime runtime, ReplayLimits limits) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.limits = Objects.requireNonNull(limits, "limits");
        comparator = new ObservableEvidenceComparator(runtime);
    }

    /** Starts or polls one replay-ready recording capture from its explicit origin. */
    public ReplayCaptureOperation start(
            ReplayCaptureSpec spec, String requestId, Duration timeout) {
        runtime.requireSubmissionsOpen();
        Objects.requireNonNull(spec, "spec");
        IdentifierSupport.validate(requestId, "replay capture request id");
        CommandDispatch dispatch = runtime.commands().orElseThrow(() ->
                new IllegalStateException("replay capture requires application command dispatch"));
        requireValidTimeout(timeout, dispatch.limits().maximumTimeoutNanos());
        validateValues(spec);
        synchronized (this) {
            CaptureEvidence existing = captureOperations.get(requestId);
            if (existing != null) {
                if (!existing.spec.equals(spec)) {
                    throw new IllegalArgumentException(
                            "replay request id is bound to a different capture specification");
                }
                return snapshot(requestId, existing, dispatch.status(requestId));
            }
        }

        validateEnvironment(spec);
        CaptureEvidence evidence;
        synchronized (this) {
            runtime.requireSubmissionsOpen();
            CaptureEvidence concurrent = captureOperations.get(requestId);
            if (concurrent != null) {
                if (!concurrent.spec.equals(spec)) {
                    throw new IllegalArgumentException(
                            "replay request id is bound to a different capture specification");
                }
                return snapshot(requestId, concurrent, dispatch.status(requestId));
            }
            if (dispatch.status(requestId).kind() != CommandLookup.Kind.UNKNOWN) {
                throw new IllegalArgumentException(
                        "replay request correlation evidence is no longer retained");
            }
            makeRoom(dispatch);
            evidence = new CaptureEvidence(spec);
            captureOperations.put(requestId, evidence);
        }
        CaptureEvidence retained = evidence;
        CommandLookup lookup;
        try {
            lookup = dispatch.submit(requestId, timeout, () -> startNow(retained));
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                captureOperations.remove(requestId);
            }
            throw failure;
        }
        return snapshot(requestId, retained, lookup);
    }

    /** Returns configured hard replay bounds. */
    public ReplayLimits limits() {
        return limits;
    }

    synchronized Optional<RetainedReplay> retainedReplay(String recordingId) {
        return Optional.ofNullable(retainedReplays.get(recordingId));
    }

    synchronized boolean replayEvicted(String recordingId) {
        return evictedRecordingIds.contains(recordingId);
    }

    synchronized void recordAction(
            ActionInvocation invocation, RuntimeValue.ObjectValue parameters) {
        if (!runtime.onCaptureThread() || activeCapture == null) {
            return;
        }
        activeCapture.markIncomplete("semantic action is not replayable");
    }

    synchronized void recordInput(InputInjection injection) {
        if (!runtime.onCaptureThread() || activeCapture == null) {
            return;
        }
        if (!activeCapture.inputs.containsKey(injection.requestId())) {
            if (activeCapture.inputs.size() >= limits.maximumInputs()) {
                activeCapture.observedInputs = Math.min(
                        activeCapture.observedInputs + 1L, limits.maximumInputs() + 1L);
                activeCapture.markIncomplete("replay input limit exceeded");
                return;
            }
            activeCapture.observedInputs++;
        }
        activeCapture.inputs.put(injection.requestId(), injection);
    }

    synchronized void recordTick(SimulationTick tick) {
        if (!runtime.onCaptureThread() || activeCapture == null) {
            return;
        }
        try {
            captureTick(activeCapture, tick);
        } catch (RuntimeException failure) {
            activeCapture.markIncomplete("replay tick evidence capture failed");
        }
    }

    synchronized void freeze(String recordingId) {
        if (activeCapture == null) {
            return;
        }
        MutableCapture capture = activeCapture;
        if (!capture.spec.recording().id().equals(recordingId)) {
            capture.markIncomplete("replay recording lifecycle is inconsistent");
            return;
        }
        try {
            freezeInputs(capture);
        } catch (RuntimeException failure) {
            capture.markIncomplete("replay input evidence freeze failed");
        }
        RetainedReplay retained = capture.freeze();
        retainedReplays.put(recordingId, retained);
        activeCapture = null;
    }

    synchronized void recordingEvicted(String recordingId) {
        if (retainedReplays.remove(recordingId) != null) {
            evictedRecordingIds.addLast(recordingId);
            int limit = runtime.recordings().limits().retainedRecordings();
            while (evictedRecordingIds.size() > limit) {
                evictedRecordingIds.removeFirst();
            }
        }
    }

    private void startNow(CaptureEvidence operation) {
        ReplayCaptureSpec spec = operation.spec;
        validateEnvironment(spec);
        long fixedStepNanos = runtime.simulation().state()
                .configuredFixedStepNanos().orElseThrow();
        long metadataBytes = ReplayCanonicalSize.captureMetadata(spec, fixedStepNanos);
        int evidenceByteLimit = Math.toIntExact(
                limits.maximumEncodedEvidenceBytes() - metadataBytes);
        ObservableEvidenceComparator.Limits evidenceLimits =
                new ObservableEvidenceComparator.Limits(
                        limits.maximumEntitiesPerFrame(), limits.maximumFactsPerFrame(),
                        evidenceByteLimit);

        FrameId baselineFrame = restoreOrigin(spec.recording());
        ExecutionEpochId baselineEpoch = runtime.currentEpoch();
        FrameSnapshot baseline = runtime.frame(baselineFrame).orElseThrow(() ->
                new IllegalStateException("replay origin did not retain its baseline frame"));
        Optional<String> incompleteReason = baselineProblem(
                spec, baseline, baselineEpoch);
        ObservableEvidenceComparator.Counters counters =
                new ObservableEvidenceComparator.Counters();
        Optional<ObservableEvidenceComparator.FrameEvidence> baselineEvidence =
                comparator.capture(baseline, spec.profile(), spec.eventTypes(),
                        evidenceLimits, counters);
        if (incompleteReason.isEmpty()) {
            incompleteReason = counters.incompleteReason();
        }
        if (incompleteReason.isEmpty() && baselineEvidence.isEmpty()) {
            incompleteReason = Optional.of("replay baseline evidence is incomplete");
        }
        long encodedBytes = ReplayCanonicalSize.add(
                metadataBytes, counters.encodedEvidenceBytes());
        MutableCapture candidate = new MutableCapture(spec, fixedStepNanos,
                baselineEpoch, baselineFrame, baselineEvidence, incompleteReason,
                counters.observedEntities(), counters.observedFacts(), encodedBytes);

        runtime.recordings().startNowForReplay(spec.recording());
        synchronized (this) {
            activeCapture = candidate;
            operation.baselineEpoch = baselineEpoch;
            operation.baselineFrame = baselineFrame;
        }
    }

    private FrameId restoreOrigin(RecordingSpec recording) {
        if (recording.scenarioId().isPresent()) {
            return runtime.scenarios().resetForReplay(
                    recording.scenarioId().orElseThrow(),
                    new ScenarioResetContext(recording.randomSeed(), recording.configuration()));
        }
        return runtime.checkpoints().restoreForReplay(
                recording.checkpointId().orElseThrow());
    }

    private void captureTick(MutableCapture capture, SimulationTick tick) {
        capture.observedTicks = Math.min(
                capture.observedTicks + 1L, limits.maximumTicks() + 1L);
        if (!tick.executionEpochId().equals(capture.baselineEpoch)) {
            capture.markIncomplete("simulation execution epoch changed during replay capture");
            return;
        }
        long expectedTick = capture.ticks.size() + 1L;
        if (tick.epochTick() != expectedTick) {
            capture.markIncomplete("simulation epoch ticks are not contiguous");
            return;
        }
        if (tick.source() != SimulationTickSource.PAUSED) {
            capture.markIncomplete("simulation tick occurred while running");
            return;
        }
        if (tick.outcome() != SimulationTickOutcome.COMPLETED
                || tick.mutationOutcome() != SimulationMutationOutcome.KNOWN_COMPLETED
                || tick.configuredFixedStepNanos().orElse(-1) != capture.fixedStepNanos
                || tick.runtimeSuppliedDeltaNanos() != capture.fixedStepNanos
                || tick.executedDeltaNanos().orElse(-1) != capture.fixedStepNanos
                || tick.resultingFrameId().isEmpty()) {
            capture.markIncomplete("simulation tick is not an acknowledged fixed-step completion");
            return;
        }
        if (capture.observedTicks > limits.maximumTicks()) {
            capture.markIncomplete("replay tick limit exceeded");
            return;
        }
        FrameSnapshot frame = runtime.frame(tick.resultingFrameId().orElseThrow()).orElse(null);
        if (frame == null || !frame.executionEpochId().equals(capture.baselineEpoch)) {
            capture.markIncomplete("simulation tick frame evidence is unavailable");
            return;
        }
        Optional<String> problem = comparator.evidenceProblem(
                frame, capture.spec.evidenceRequirements());
        if (problem.isEmpty()) {
            problem = comparator.selectionProblem(
                    frame, capture.spec.profile().comparisonScope());
        }
        if (problem.isPresent()) {
            capture.markIncomplete(problem.orElseThrow());
            return;
        }
        long tickBytes = ReplayCanonicalSize.tick(tick);
        long remaining = limits.maximumEncodedEvidenceBytes() - capture.encodedBytes - tickBytes;
        if (remaining <= 0) {
            capture.markIncomplete("encoded replay evidence limit exceeded");
            return;
        }
        ObservableEvidenceComparator.Counters counters =
                new ObservableEvidenceComparator.Counters();
        Optional<ObservableEvidenceComparator.FrameEvidence> evidence = comparator.capture(
                frame, capture.spec.profile(), capture.spec.eventTypes(),
                new ObservableEvidenceComparator.Limits(
                        limits.maximumEntitiesPerFrame(), limits.maximumFactsPerFrame(),
                        Math.toIntExact(remaining)), counters);
        if (counters.incompleteReason().isPresent() || evidence.isEmpty()) {
            capture.markIncomplete(counters.incompleteReason()
                    .orElse("replay tick evidence is incomplete"));
            return;
        }
        capture.observedEntities = ReplayCanonicalSize.add(
                capture.observedEntities, counters.observedEntities());
        capture.observedFacts = ReplayCanonicalSize.add(
                capture.observedFacts, counters.observedFacts());
        capture.encodedBytes = ReplayCanonicalSize.add(capture.encodedBytes,
                ReplayCanonicalSize.add(tickBytes, counters.encodedEvidenceBytes()));
        CapturedTick captured = new CapturedTick(tick, evidence.orElseThrow());
        capture.ticks.add(captured);
        capture.tickByFrame.put(tick.resultingFrameId().orElseThrow(), captured);
    }

    private void freezeInputs(MutableCapture capture) {
        for (InputInjection injection : capture.inputs.values()) {
            if (injection.state() != InputInjectionState.EXECUTED
                    || injection.parametersRedacted()
                    || injection.recordedParameters().isEmpty()
                    || injection.resultingFrameId().isEmpty()
                    || injection.diagnostic().isPresent()
                    || injection.applicationFailure().isPresent()
                    || injection.command().status().map(CommandStatus::state)
                            .orElse(CommandState.FAILED) != CommandState.SUCCEEDED) {
                capture.markIncomplete("input did not complete with replayable parameters");
                continue;
            }
            if (!injection.executionEpochId().equals(capture.baselineEpoch)) {
                capture.markIncomplete("input execution epoch changed during replay capture");
                continue;
            }
            CapturedTick tick = capture.tickByFrame.get(
                    injection.resultingFrameId().orElseThrow());
            if (tick == null) {
                capture.markIncomplete("input resulting simulation tick is unavailable");
                continue;
            }
            SimulationDeterminismInput replayInput = new SimulationDeterminismInput(
                    tick.tick().epochTick(), injection.inputId(),
                    injection.recordedParameters().orElseThrow());
            long inputBytes = ReplayCanonicalSize.input(replayInput);
            if (ReplayCanonicalSize.add(capture.encodedBytes, inputBytes)
                    > limits.maximumEncodedEvidenceBytes()) {
                capture.markIncomplete("encoded replay evidence limit exceeded");
                continue;
            }
            capture.encodedBytes = ReplayCanonicalSize.add(capture.encodedBytes, inputBytes);
            capture.replayInputs.add(replayInput);
        }
    }

    private Optional<String> baselineProblem(ReplayCaptureSpec spec, FrameSnapshot baseline,
            ExecutionEpochId expectedEpoch) {
        BaselineKind expectedKind = spec.recording().scenarioId().isPresent()
                ? BaselineKind.SCENARIO_RESET : BaselineKind.CHECKPOINT_RESTORE;
        SimulationState state = runtime.simulation().state();
        if (!baseline.executionEpochId().equals(expectedEpoch)
                || !baseline.baselineKind().equals(Optional.of(expectedKind))
                || !state.executionEpochId().equals(expectedEpoch)
                || state.attemptedEpochTicks() != 0 || state.completedEpochTicks() != 0) {
            return Optional.of("replay origin baseline or simulation epoch is inconsistent");
        }
        Optional<String> problem = comparator.configurationProblem(
                baseline, spec.configurationRequirements());
        if (problem.isEmpty()) {
            problem = comparator.selectionProblem(
                    baseline, spec.profile().comparisonScope());
        }
        return problem;
    }

    private void validateValues(ReplayCaptureSpec spec) {
        RuntimeValueValidator.validate(
                spec.recording().configuration(), runtime.configuration().limits());
        spec.configurationRequirements().forEach(requirement ->
                RuntimeValueValidator.validate(
                        requirement.expected(), runtime.configuration().limits()));
    }

    private void validateEnvironment(ReplayCaptureSpec spec) {
        if (runtime.status() != RuntimeStatus.RUNNING) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.INVALID_LIFECYCLE, "replay capture requires a running runtime");
        }
        if (!runtime.controls().available() || !runtime.controls().acknowledgedTicksAvailable()) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_QUERY,
                    "replay capture requires acknowledged simulation control");
        }
        if (!runtime.controls().pauseStateKnown() || !runtime.controls().paused()) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                    "replay capture requires an explicitly paused simulation");
        }
        SimulationState state = runtime.simulation().state();
        if (!state.configured()) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_QUERY,
                    "replay capture requires a configured fixed simulation step");
        }
        runtime.inputs().validateReplayCaptureReady();
        runtime.recordings().validateReplayStart(spec.recording());
        synchronized (this) {
            if (activeCapture != null) {
                throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                        "a replay capture is already active");
            }
        }
        if (spec.recording().scenarioId().isPresent()) {
            if (!runtime.scenarios().replayAvailable(
                    spec.recording().scenarioId().orElseThrow())) {
                throw new IllegalArgumentException(
                        "scenario is not registered for deterministic replay");
            }
        } else if (!runtime.checkpoints().replayAvailable(
                spec.recording().checkpointId().orElseThrow())) {
            throw new IllegalArgumentException("checkpoint is not retained for replay");
        }
        long metadataBytes = ReplayCanonicalSize.captureMetadata(
                spec, state.configuredFixedStepNanos().orElseThrow());
        if (metadataBytes >= limits.maximumEncodedEvidenceBytes()) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "replay metadata leaves no room for baseline evidence");
        }
    }

    private synchronized ReplayCaptureOperation snapshot(
            String requestId, CaptureEvidence evidence, CommandLookup lookup) {
        return new ReplayCaptureOperation(evidence.spec.recording().id(), requestId, lookup,
                Optional.ofNullable(evidence.baselineEpoch),
                Optional.ofNullable(evidence.baselineFrame));
    }

    private synchronized void makeRoom(CommandDispatch dispatch) {
        if (captureOperations.size() < limits.retainedOperations()) {
            return;
        }
        String oldest = captureOperations.keySet().iterator().next();
        CommandLookup lookup = dispatch.status(oldest);
        if (lookup.kind() == CommandLookup.Kind.FOUND
                && lookup.status().map(status -> !terminal(status.state())).orElse(false)) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "replay operation retention is full");
        }
        captureOperations.remove(oldest);
    }

    synchronized void close() {
        activeCapture = null;
        captureOperations.clear();
        retainedReplays.clear();
        evictedRecordingIds.clear();
    }

    private static boolean terminal(CommandState state) {
        return state != CommandState.QUEUED && state != CommandState.EXECUTING;
    }

    private static void requireValidTimeout(Duration timeout, long maximumNanos) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        final long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("timeout exceeds the supported range", failure);
        }
        if (nanos > maximumNanos) {
            throw new IllegalArgumentException("timeout exceeds the configured limit");
        }
    }

    private static final class CaptureEvidence {
        private final ReplayCaptureSpec spec;
        private ExecutionEpochId baselineEpoch;
        private FrameId baselineFrame;

        private CaptureEvidence(ReplayCaptureSpec spec) {
            this.spec = spec;
        }
    }

    static record CapturedTick(SimulationTick tick,
            ObservableEvidenceComparator.FrameEvidence evidence) {
        CapturedTick {
            Objects.requireNonNull(tick, "tick");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    static record RetainedReplay(ReplayCaptureSpec spec, long fixedStepNanos,
            ExecutionEpochId baselineEpoch, FrameId baselineFrame,
            Optional<ObservableEvidenceComparator.FrameEvidence> baselineEvidence,
            List<SimulationDeterminismInput> inputs, List<CapturedTick> ticks,
            Optional<String> incompleteReason, long observedInputs, long observedTicks,
            long observedEntities, long observedFacts, long encodedBytes) {
        RetainedReplay {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(baselineEpoch, "baselineEpoch");
            Objects.requireNonNull(baselineFrame, "baselineFrame");
            baselineEvidence = Objects.requireNonNull(baselineEvidence, "baselineEvidence");
            inputs = List.copyOf(inputs);
            ticks = List.copyOf(ticks);
            incompleteReason = Objects.requireNonNull(incompleteReason, "incompleteReason");
        }
    }

    private static final class MutableCapture {
        private final ReplayCaptureSpec spec;
        private final long fixedStepNanos;
        private final ExecutionEpochId baselineEpoch;
        private final FrameId baselineFrame;
        private final Optional<ObservableEvidenceComparator.FrameEvidence> baselineEvidence;
        private final LinkedHashMap<String, InputInjection> inputs = new LinkedHashMap<>();
        private final ArrayList<CapturedTick> ticks = new ArrayList<>();
        private final LinkedHashMap<FrameId, CapturedTick> tickByFrame = new LinkedHashMap<>();
        private final ArrayList<SimulationDeterminismInput> replayInputs = new ArrayList<>();
        private Optional<String> incompleteReason;
        private long observedInputs;
        private long observedTicks;
        private long observedEntities;
        private long observedFacts;
        private long encodedBytes;

        private MutableCapture(ReplayCaptureSpec spec, long fixedStepNanos,
                ExecutionEpochId baselineEpoch, FrameId baselineFrame,
                Optional<ObservableEvidenceComparator.FrameEvidence> baselineEvidence,
                Optional<String> incompleteReason, long observedEntities, long observedFacts,
                long encodedBytes) {
            this.spec = spec;
            this.fixedStepNanos = fixedStepNanos;
            this.baselineEpoch = baselineEpoch;
            this.baselineFrame = baselineFrame;
            this.baselineEvidence = baselineEvidence;
            this.incompleteReason = incompleteReason;
            this.observedEntities = observedEntities;
            this.observedFacts = observedFacts;
            this.encodedBytes = encodedBytes;
        }

        private void markIncomplete(String reason) {
            if (incompleteReason.isEmpty()) {
                incompleteReason = Optional.of(reason);
            }
        }

        private RetainedReplay freeze() {
            return new RetainedReplay(spec, fixedStepNanos, baselineEpoch, baselineFrame,
                    baselineEvidence, replayInputs, ticks, incompleteReason, observedInputs,
                    observedTicks, observedEntities, observedFacts, encodedBytes);
        }
    }
}
