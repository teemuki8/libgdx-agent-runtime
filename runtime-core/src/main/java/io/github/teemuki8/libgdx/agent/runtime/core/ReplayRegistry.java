package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

/** Bounded replay-ready recording capture and deterministic replay execution registry. */
public final class ReplayRegistry {
    private final AgentRuntime runtime;
    private final ReplayLimits limits;
    private final ObservableEvidenceComparator comparator;
    private final LinkedHashMap<String, CaptureEvidence> captureOperations =
            new LinkedHashMap<>();
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

    private static final class MutableCapture {
        private final ReplayCaptureSpec spec;
        private final long fixedStepNanos;
        private final ExecutionEpochId baselineEpoch;
        private final FrameId baselineFrame;
        private final Optional<ObservableEvidenceComparator.FrameEvidence> baselineEvidence;
        private final Optional<String> incompleteReason;
        private final long observedEntities;
        private final long observedFacts;
        private final long encodedBytes;

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
    }
}
