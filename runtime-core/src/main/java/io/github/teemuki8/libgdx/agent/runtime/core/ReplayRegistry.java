package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Bounded replay-ready recording capture and deterministic replay execution registry. */
public final class ReplayRegistry {
    private final AgentRuntime runtime;
    private final ReplayLimits limits;
    private final ObservableEvidenceComparator comparator;
    private final LinkedHashMap<String, CaptureEvidence> captureOperations =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, ExecutionEvidence> executionOperations =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, OperationKind> operationOrder = new LinkedHashMap<>();
    private final LinkedHashMap<String, RetainedReplay> retainedReplays =
            new LinkedHashMap<>();
    private final ArrayDeque<String> evictedRecordingIds = new ArrayDeque<>();
    private MutableCapture activeCapture;
    private boolean activeExecution;

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
            if (executionOperations.containsKey(requestId)) {
                throw new IllegalArgumentException(
                        "replay request id is bound to a different operation");
            }
            if (activeExecution || hasNonterminalExecution(dispatch)) {
                throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                        "another replay execution is active");
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
            if (executionOperations.containsKey(requestId)) {
                throw new IllegalArgumentException(
                        "replay request id is bound to a different operation");
            }
            if (activeExecution || hasNonterminalExecution(dispatch)) {
                throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                        "another replay execution is active");
            }
            if (dispatch.status(requestId).kind() != CommandLookup.Kind.UNKNOWN) {
                throw new IllegalArgumentException(
                        "replay request correlation evidence is no longer retained");
            }
            makeRoom(dispatch);
            evidence = new CaptureEvidence(spec);
            captureOperations.put(requestId, evidence);
            operationOrder.put(requestId, OperationKind.CAPTURE);
        }
        CaptureEvidence retained = evidence;
        CommandLookup lookup;
        try {
            lookup = dispatch.submit(requestId, timeout, () -> startNow(retained));
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                captureOperations.remove(requestId);
                operationOrder.remove(requestId);
            }
            throw failure;
        }
        return snapshot(requestId, retained, lookup);
    }

    /** Returns configured hard replay bounds. */
    public ReplayLimits limits() {
        return limits;
    }

    /** Reports whether mandatory replay execution capabilities and an origin are registered. */
    public boolean available() {
        return runtime.commands().isPresent() && runtime.controls().acknowledgedTicksAvailable()
                && runtime.simulation().state().configured()
                && (runtime.scenarios().determinismAvailable()
                        || runtime.checkpoints().available());
    }

    /** Executes or polls one stopped replay-ready recording at most once. */
    public ReplayOperation execute(
            String recordingId, String requestId, Duration timeout) {
        runtime.requireSubmissionsOpen();
        IdentifierSupport.validate(recordingId, "replay recording id");
        IdentifierSupport.validate(requestId, "replay request id");
        CommandDispatch dispatch = runtime.commands().orElseThrow(() ->
                new IllegalStateException("replay execution requires application command dispatch"));
        long timeoutNanos = requireValidTimeout(timeout, dispatch.limits().maximumTimeoutNanos());
        long executionNanos = Math.min(timeoutNanos, limits.maximumExecutionNanos());
        synchronized (this) {
            ExecutionEvidence existing = executionOperations.get(requestId);
            if (existing != null) {
                if (!existing.recordingId.equals(recordingId)) {
                    throw new IllegalArgumentException(
                            "replay request id is bound to a different recording");
                }
                return executionSnapshot(requestId, existing, dispatch.status(requestId));
            }
            if (captureOperations.containsKey(requestId)) {
                throw new IllegalArgumentException(
                        "replay request id is bound to a different operation");
            }
        }
        validateExecutionEnvironment(recordingId, dispatch);
        ExecutionEvidence evidence;
        synchronized (this) {
            runtime.requireSubmissionsOpen();
            ExecutionEvidence concurrent = executionOperations.get(requestId);
            if (concurrent != null) {
                if (!concurrent.recordingId.equals(recordingId)) {
                    throw new IllegalArgumentException(
                            "replay request id is bound to a different recording");
                }
                return executionSnapshot(requestId, concurrent, dispatch.status(requestId));
            }
            if (captureOperations.containsKey(requestId)
                    || dispatch.status(requestId).kind() != CommandLookup.Kind.UNKNOWN) {
                throw new IllegalArgumentException(
                        "replay request correlation evidence is no longer retained");
            }
            if (activeCapture != null || activeExecution || hasNonterminalExecution(dispatch)) {
                throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                        "another replay capture or execution is active");
            }
            makeRoom(dispatch);
            evidence = new ExecutionEvidence(recordingId, executionNanos);
            executionOperations.put(requestId, evidence);
            operationOrder.put(requestId, OperationKind.EXECUTE);
        }
        long deadline = deadline(executionNanos);
        ExecutionEvidence retained = evidence;
        CommandLookup lookup;
        try {
            lookup = dispatch.submit(requestId, timeout, () -> {
                synchronized (this) {
                    activeExecution = true;
                }
                try {
                    retained.result = Optional.of(executeNow(
                            recordingId, requestId, deadline, executionNanos));
                } finally {
                    synchronized (this) {
                        activeExecution = false;
                    }
                }
            });
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                executionOperations.remove(requestId);
                operationOrder.remove(requestId);
            }
            throw failure;
        }
        return executionSnapshot(requestId, retained, lookup);
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

    synchronized void freeze(String recordingId, RecordingStopReason stopReason,
            boolean reproductionEvidenceComplete) {
        if (activeCapture == null) {
            return;
        }
        MutableCapture capture = activeCapture;
        if (!capture.spec.recording().id().equals(recordingId)) {
            capture.markIncomplete("replay recording lifecycle is inconsistent");
            return;
        }
        if (!runtime.currentEpoch().equals(capture.baselineEpoch)) {
            capture.markIncomplete("simulation execution epoch changed during replay capture");
        }
        if (!reproductionEvidenceComplete || stopReason != RecordingStopReason.REQUESTED) {
            capture.markIncomplete("recording stopped with incomplete reproduction evidence: "
                    + stopReason.name().toLowerCase().replace('_', '-'));
        }
        if (uiEvidenceEvicted(capture.spec.profile(), capture.uiEvictions)) {
            capture.markIncomplete("UI correlation evidence was evicted");
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

    private ReplayResult executeNow(String recordingId, String requestId,
            long deadline, long executionNanos) {
        RetainedReplay replay;
        synchronized (this) {
            replay = retainedReplays.get(recordingId);
        }
        if (replay == null) {
            String message = replayEvicted(recordingId)
                    || runtime.recordings().replayLookup(recordingId)
                            == RecordingRegistry.ReplayLookup.EVICTED
                    ? "recording or replay evidence was evicted"
                    : "replay evidence was not captured for this ordinary recording";
            return inconclusive(recordingId, Optional.empty(), 0, 0, 0,
                    new ObservableEvidenceComparator.Counters(), executionNanos,
                    message, Optional.empty());
        }
        if (replay.incompleteReason().isPresent()) {
            return inconclusive(replay, 0, new ObservableEvidenceComparator.Counters(),
                    executionNanos, replay.incompleteReason().orElseThrow(), Optional.empty());
        }
        SimulationState state = runtime.simulation().state();
        if (!state.configured()
                || state.configuredFixedStepNanos().orElseThrow() != replay.fixedStepNanos()) {
            return inconclusive(replay, 0, new ObservableEvidenceComparator.Counters(),
                    executionNanos, "configured fixed simulation step changed", Optional.empty());
        }

        ObservableEvidenceComparator.Counters counters =
                new ObservableEvidenceComparator.Counters();
        int[] completedTicks = {0};
        boolean inputMode = false;
        boolean pauseEstablished = false;
        boolean previouslyPaused = true;
        ReplayResult result;
        try {
            runtime.inputs().beginDeterminism(replay.inputs());
            inputMode = true;
            previouslyPaused = runtime.controls().pauseForDeterminism();
            pauseEstablished = true;
            long uiEvictions = runtime.uiCorrelations().evictedFrameCount();
            result = executeWhilePaused(replay, deadline, executionNanos, counters,
                    completedTicks, uiEvictions);
        } catch (RuntimeException | Error failure) {
            result = inconclusive(replay, completedTicks[0], counters, executionNanos,
                    failureEvidence(requestId, "replay.execute", failure));
        }
        if (pauseEstablished) {
            try {
                runtime.controls().restorePauseAfterDeterminism(previouslyPaused);
            } catch (RuntimeException | Error failure) {
                result = inconclusive(replay, completedTicks[0], counters, executionNanos,
                        failureEvidence(requestId, "replay.restore", failure));
            }
        }
        if (inputMode) {
            runtime.inputs().endDeterminism();
        }
        return result;
    }

    private ReplayResult executeWhilePaused(RetainedReplay replay,
            long deadline, long executionNanos,
            ObservableEvidenceComparator.Counters counters, int[] completedTicks,
            long uiEvictions) {
        if (expired(deadline)) {
            return inconclusive(replay, 0, counters, executionNanos,
                    "replay execution deadline elapsed", Optional.empty());
        }
        FrameId replayBaselineFrame = restoreOrigin(replay.spec().recording());
        ExecutionEpochId replayEpoch = runtime.currentEpoch();
        FrameSnapshot baseline = runtime.frame(replayBaselineFrame).orElseThrow();
        Optional<String> problem = baselineProblem(replay.spec(), baseline, replayEpoch);
        if (problem.isPresent()) {
            return inconclusive(replay, 0, counters, executionNanos,
                    problem.orElseThrow(), Optional.empty());
        }
        Optional<ObservableEvidenceComparator.FrameEvidence> baselineEvidence =
                captureExecutionEvidence(replay, baseline, counters);
        if (baselineEvidence.isEmpty()) {
            return inconclusive(replay, 0, counters, executionNanos,
                    counters.incompleteReason().orElse("replay baseline evidence is incomplete"),
                    Optional.empty());
        }
        if (expired(deadline)) {
            return inconclusive(replay, 0, counters, executionNanos,
                    "replay execution deadline elapsed", Optional.empty());
        }
        if (uiEvidenceEvicted(replay.spec().profile(), uiEvictions)) {
            return inconclusive(replay, 0, counters, executionNanos,
                    "UI correlation evidence was evicted", Optional.empty());
        }
        Optional<DeterminismDifference> baselineDifference = comparator.difference(
                replay.baselineEvidence().orElseThrow(), baselineEvidence.orElseThrow());
        if (baselineDifference.isPresent()) {
            return diverged(replay, 0, counters, executionNanos,
                    new ReplayDivergence(ReplayPhase.BASELINE, OptionalLong.empty(),
                            Optional.empty(), Optional.empty(), replay.baselineEpoch(), replayEpoch,
                            replay.baselineFrame(), replayBaselineFrame,
                            baselineDifference.orElseThrow()));
        }

        for (CapturedTick reference : replay.ticks()) {
            if (expired(deadline)) {
                return inconclusive(replay, completedTicks[0], counters, executionNanos,
                        "replay execution deadline elapsed", Optional.empty());
            }
            long epochTick = reference.tick().epochTick();
            List<SimulationDeterminismInput> tickInputs = replay.inputs().stream()
                    .filter(input -> input.epochTick() == epochTick).toList();
            SimulationControlRegistry.ExactTickEvidence completed =
                    runtime.controls().tickForDeterminism(replay.fixedStepNanos(), tickInputs);
            Optional<String> tickProblem = replayTickProblem(
                    completed, epochTick, replay.fixedStepNanos(), replayEpoch);
            if (tickProblem.isPresent()) {
                return inconclusive(replay, completedTicks[0], counters, executionNanos,
                        tickProblem.orElseThrow(), Optional.empty());
            }
            Optional<String> evidenceProblem = comparator.evidenceProblem(
                    completed.frame(), replay.spec().evidenceRequirements());
            if (evidenceProblem.isEmpty()) {
                evidenceProblem = comparator.selectionProblem(completed.frame(),
                        replay.spec().profile().comparisonScope());
            }
            if (evidenceProblem.isPresent()) {
                return inconclusive(replay, completedTicks[0], counters, executionNanos,
                        evidenceProblem.orElseThrow(), Optional.empty());
            }
            Optional<ObservableEvidenceComparator.FrameEvidence> replayEvidence =
                    captureExecutionEvidence(replay, completed.frame(), counters);
            if (replayEvidence.isEmpty()) {
                return inconclusive(replay, completedTicks[0], counters, executionNanos,
                        counters.incompleteReason().orElse("replay tick evidence is incomplete"),
                        Optional.empty());
            }
            if (expired(deadline)) {
                return inconclusive(replay, completedTicks[0], counters, executionNanos,
                        "replay execution deadline elapsed", Optional.empty());
            }
            if (uiEvidenceEvicted(replay.spec().profile(), uiEvictions)) {
                return inconclusive(replay, completedTicks[0], counters, executionNanos,
                        "UI correlation evidence was evicted", Optional.empty());
            }
            Optional<DeterminismDifference> difference = comparator.difference(
                    reference.evidence(), replayEvidence.orElseThrow());
            if (difference.isPresent()) {
                return diverged(replay, completedTicks[0], counters, executionNanos,
                        new ReplayDivergence(ReplayPhase.SIMULATION_TICK,
                                OptionalLong.of(epochTick),
                                Optional.of(reference.tick().simulationTickId()),
                                Optional.of(completed.tick().simulationTickId()),
                                replay.baselineEpoch(), replayEpoch,
                                reference.tick().resultingFrameId().orElseThrow(),
                                completed.frame().frameId(), difference.orElseThrow()));
            }
            completedTicks[0]++;
        }
        if (expired(deadline)) {
            return inconclusive(replay, completedTicks[0], counters, executionNanos,
                    "replay execution deadline elapsed", Optional.empty());
        }
        if (uiEvidenceEvicted(replay.spec().profile(), uiEvictions)) {
            return inconclusive(replay, completedTicks[0], counters, executionNanos,
                    "UI correlation evidence was evicted", Optional.empty());
        }
        return new ReplayResult(DeterminismStatus.EQUAL,
                "equal for the selected replay evidence; whole-program determinism is not proven",
                replay.spec().recording().id(), Optional.of(replay.spec().profile()),
                Optional.empty(), bounds(replay, completedTicks[0], counters, executionNanos),
                Optional.empty());
    }

    private Optional<ObservableEvidenceComparator.FrameEvidence> captureExecutionEvidence(
            RetainedReplay replay, FrameSnapshot frame,
            ObservableEvidenceComparator.Counters counters) {
        return comparator.capture(frame, replay.spec().profile(), replay.spec().eventTypes(),
                new ObservableEvidenceComparator.Limits(limits.maximumEntitiesPerFrame(),
                        limits.maximumFactsPerFrame(), limits.maximumEncodedEvidenceBytes()),
                counters);
    }

    private Optional<String> replayTickProblem(
            SimulationControlRegistry.ExactTickEvidence evidence,
            long expectedEpochTick, long fixedStepNanos, ExecutionEpochId expectedEpoch) {
        SimulationTick tick = evidence.tick();
        if (tick.epochTick() != expectedEpochTick
                || !tick.executionEpochId().equals(expectedEpoch)
                || tick.source() != SimulationTickSource.PAUSED
                || tick.outcome() != SimulationTickOutcome.COMPLETED
                || tick.mutationOutcome() != SimulationMutationOutcome.KNOWN_COMPLETED
                || tick.configuredFixedStepNanos().orElse(-1) != fixedStepNanos
                || tick.runtimeSuppliedDeltaNanos() != fixedStepNanos
                || tick.executedDeltaNanos().orElse(-1) != fixedStepNanos
                || tick.resultingFrameId().isEmpty()
                || !tick.resultingFrameId().orElseThrow().equals(evidence.frame().frameId())
                || !evidence.frame().executionEpochId().equals(expectedEpoch)) {
            return Optional.of("replay simulation tick evidence is incomplete or mismatched");
        }
        return Optional.empty();
    }

    private ReplayResult diverged(RetainedReplay replay, int completedTicks,
            ObservableEvidenceComparator.Counters counters, long executionNanos,
            ReplayDivergence divergence) {
        return new ReplayResult(DeterminismStatus.DIVERGED,
                "first divergence in selected replay evidence",
                replay.spec().recording().id(), Optional.of(replay.spec().profile()),
                Optional.of(divergence), bounds(replay, completedTicks, counters, executionNanos),
                Optional.empty());
    }

    private ReplayResult inconclusive(RetainedReplay replay, int completedTicks,
            ObservableEvidenceComparator.Counters counters, long executionNanos,
            String message, Optional<ApplicationFailureEvidence> failure) {
        return inconclusive(replay.spec().recording().id(), Optional.of(replay.spec().profile()),
                replay.ticks().size(), completedTicks, replay.inputs().size(), counters,
                executionNanos, message, failure);
    }

    private ReplayResult inconclusive(RetainedReplay replay, int completedTicks,
            ObservableEvidenceComparator.Counters counters, long executionNanos,
            ApplicationFailureEvidence failure) {
        return inconclusive(replay, completedTicks, counters, executionNanos,
                failure.legacyEnvelope(), Optional.of(failure));
    }

    private ReplayResult inconclusive(String recordingId, Optional<DeterminismProfile> profile,
            int requestedTicks, int completedTicks, int recordedInputs,
            ObservableEvidenceComparator.Counters counters, long executionNanos,
            String message, Optional<ApplicationFailureEvidence> failure) {
        return new ReplayResult(DeterminismStatus.INCONCLUSIVE, boundedMessage(message),
                recordingId, profile, Optional.empty(), new ReplayBounds(requestedTicks,
                        completedTicks, recordedInputs, counters.observedEntities(),
                        counters.observedFacts(), counters.encodedEvidenceBytes(), executionNanos),
                failure);
    }

    private ReplayBounds bounds(RetainedReplay replay, int completedTicks,
            ObservableEvidenceComparator.Counters counters, long executionNanos) {
        return new ReplayBounds(replay.ticks().size(), completedTicks, replay.inputs().size(),
                counters.observedEntities(), counters.observedFacts(),
                counters.encodedEvidenceBytes(), executionNanos);
    }

    private ApplicationFailureEvidence failureEvidence(
            String requestId, String category, Throwable failure) {
        Optional<String> correlationId = runtime.commands().orElseThrow().correlationId(requestId);
        return correlationId.isPresent()
                ? runtime.diagnostics().describe(category, failure, correlationId.orElseThrow())
                : runtime.diagnostics().describe(category, failure);
    }

    private static String boundedMessage(String message) {
        int limit = ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY;
        return message.length() <= limit ? message : message.substring(0, limit);
    }

    private void startNow(CaptureEvidence operation) {
        ReplayCaptureSpec spec = operation.spec;
        validateEnvironment(spec);
        long uiEvictions = runtime.uiCorrelations().evictedFrameCount();
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
        if (incompleteReason.isEmpty() && uiEvidenceEvicted(spec.profile(), uiEvictions)) {
            incompleteReason = Optional.of("UI correlation evidence was evicted");
        }
        long encodedBytes = ReplayCanonicalSize.add(
                metadataBytes, counters.encodedEvidenceBytes());
        MutableCapture candidate = new MutableCapture(spec, fixedStepNanos,
                baselineEpoch, baselineFrame, baselineEvidence, incompleteReason,
                counters.observedEntities(), counters.observedFacts(), encodedBytes, uiEvictions);

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

    private boolean uiEvidenceEvicted(DeterminismProfile profile, long expectedEvictions) {
        return profile.includeUiCorrelations()
                && runtime.uiCorrelations().evictedFrameCount() != expectedEvictions;
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
        SnapshotComparisonScope scope = spec.profile().comparisonScope();
        if (scope.entityIds().size() > limits.maximumEntitiesPerFrame()
                || scope.properties().size() > limits.maximumFactsPerFrame()) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "replay selectors exceed configured evidence limits");
        }
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
            if (activeCapture != null || activeExecution
                    || hasNonterminalExecution(runtime.commands().orElseThrow())) {
                throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                        "another replay capture or execution is active");
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

    private void validateExecutionEnvironment(
            String recordingId, CommandDispatch dispatch) {
        if (runtime.status() != RuntimeStatus.RUNNING) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.INVALID_LIFECYCLE, "replay execution requires a running runtime");
        }
        if (!runtime.controls().available() || !runtime.controls().acknowledgedTicksAvailable()
                || !runtime.simulation().state().configured()) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_QUERY,
                    "replay execution capabilities are unavailable");
        }
        if (!runtime.controls().pauseStateKnown() || !runtime.controls().paused()) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                    "replay execution requires an explicitly paused simulation");
        }
        RecordingRegistry.ReplayLookup lookup = runtime.recordings().replayLookup(recordingId);
        if (lookup == RecordingRegistry.ReplayLookup.UNKNOWN) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.INVALID_QUERY, "recording does not exist");
        }
        if (lookup == RecordingRegistry.ReplayLookup.ACTIVE || runtime.recordings().active()) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                    "replay execution requires no active recording");
        }
        runtime.inputs().validateReplayCaptureReady();
        synchronized (this) {
            if (activeCapture != null || activeExecution || hasNonterminalExecution(dispatch)) {
                throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                        "another replay capture or execution is active");
            }
        }
    }

    private boolean hasNonterminalExecution(CommandDispatch dispatch) {
        for (String requestId : executionOperations.keySet()) {
            CommandLookup lookup = dispatch.status(requestId);
            if (lookup.kind() == CommandLookup.Kind.FOUND
                    && lookup.status().map(status -> !terminal(status.state())).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    private synchronized ReplayCaptureOperation snapshot(
            String requestId, CaptureEvidence evidence, CommandLookup lookup) {
        return new ReplayCaptureOperation(evidence.spec.recording().id(), requestId, lookup,
                Optional.ofNullable(evidence.baselineEpoch),
                Optional.ofNullable(evidence.baselineFrame));
    }

    private synchronized ReplayOperation executionSnapshot(
            String requestId, ExecutionEvidence evidence, CommandLookup lookup) {
        if (evidence.result.isEmpty() && lookup.status().isPresent()
                && lookup.status().orElseThrow().state() == CommandState.TIMED_OUT) {
            RetainedReplay replay = retainedReplays.get(evidence.recordingId);
            ObservableEvidenceComparator.Counters counters =
                    new ObservableEvidenceComparator.Counters();
            evidence.result = Optional.of(replay == null
                    ? inconclusive(evidence.recordingId, Optional.empty(), 0, 0, 0,
                            counters, evidence.executionNanos,
                            "replay command timed out before execution", Optional.empty())
                    : inconclusive(replay, 0, counters, evidence.executionNanos,
                            "replay command timed out before execution", Optional.empty()));
        }
        return new ReplayOperation(
                evidence.recordingId, requestId, lookup, evidence.result);
    }

    private synchronized void makeRoom(CommandDispatch dispatch) {
        if (operationOrder.size() < limits.retainedOperations()) {
            return;
        }
        String oldest = operationOrder.keySet().iterator().next();
        CommandLookup lookup = dispatch.status(oldest);
        if (lookup.kind() == CommandLookup.Kind.FOUND
                && lookup.status().map(status -> !terminal(status.state())).orElse(false)) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "replay operation retention is full");
        }
        OperationKind kind = operationOrder.remove(oldest);
        if (kind == OperationKind.CAPTURE) {
            captureOperations.remove(oldest);
        } else {
            executionOperations.remove(oldest);
        }
    }

    synchronized void close() {
        activeCapture = null;
        activeExecution = false;
        captureOperations.clear();
        executionOperations.clear();
        operationOrder.clear();
        retainedReplays.clear();
        evictedRecordingIds.clear();
    }

    private static boolean terminal(CommandState state) {
        return state != CommandState.QUEUED && state != CommandState.EXECUTING;
    }

    private static long requireValidTimeout(Duration timeout, long maximumNanos) {
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
        return nanos;
    }

    private long deadline(long executionNanos) {
        try {
            return Math.addExact(runtime.monotonicTimeNanos(), executionNanos);
        } catch (ArithmeticException failure) {
            return Long.MAX_VALUE;
        }
    }

    private boolean expired(long deadline) {
        return runtime.monotonicTimeNanos() >= deadline;
    }

    private static final class CaptureEvidence {
        private final ReplayCaptureSpec spec;
        private ExecutionEpochId baselineEpoch;
        private FrameId baselineFrame;

        private CaptureEvidence(ReplayCaptureSpec spec) {
            this.spec = spec;
        }
    }

    private static final class ExecutionEvidence {
        private final String recordingId;
        private final long executionNanos;
        private Optional<ReplayResult> result = Optional.empty();

        private ExecutionEvidence(String recordingId, long executionNanos) {
            this.recordingId = recordingId;
            this.executionNanos = executionNanos;
        }
    }

    private enum OperationKind { CAPTURE, EXECUTE }

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
        private final long uiEvictions;

        private MutableCapture(ReplayCaptureSpec spec, long fixedStepNanos,
                ExecutionEpochId baselineEpoch, FrameId baselineFrame,
                Optional<ObservableEvidenceComparator.FrameEvidence> baselineEvidence,
                Optional<String> incompleteReason, long observedEntities, long observedFacts,
                long encodedBytes, long uiEvictions) {
            this.spec = spec;
            this.fixedStepNanos = fixedStepNanos;
            this.baselineEpoch = baselineEpoch;
            this.baselineFrame = baselineFrame;
            this.baselineEvidence = baselineEvidence;
            this.incompleteReason = incompleteReason;
            this.observedEntities = observedEntities;
            this.observedFacts = observedFacts;
            this.encodedBytes = encodedBytes;
            this.uiEvictions = uiEvictions;
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
