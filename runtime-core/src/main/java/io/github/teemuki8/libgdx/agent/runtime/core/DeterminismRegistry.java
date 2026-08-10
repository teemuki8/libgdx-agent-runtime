package io.github.teemuki8.libgdx.agent.runtime.core;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Application-dispatched bounded repeated-scenario execution and comparison. */
public final class DeterminismRegistry {
    private static final String EQUAL_MESSAGE =
            "equal for the configured observable state; whole-program determinism is not proven";
    private final AgentRuntime runtime;
    private final DeterminismLimits limits;
    private final ObservableEvidenceComparator evidenceComparator;
    private final ObservableEvidenceComparator.Limits evidenceLimits;
    private final LinkedHashMap<String, Evidence> operations = new LinkedHashMap<>();
    private final LinkedHashMap<String, DeterminismSpec> evictedOperations =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, SimulationEvidence> simulationOperations =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, SimulationDeterminismSpec> evictedSimulationOperations =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, OperationKind> operationOrder = new LinkedHashMap<>();
    private final LinkedHashMap<String, OperationKind> evictedOrder = new LinkedHashMap<>();

    DeterminismRegistry(AgentRuntime runtime, DeterminismLimits limits) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.limits = Objects.requireNonNull(limits, "limits");
        evidenceComparator = new ObservableEvidenceComparator(runtime);
        evidenceLimits = new ObservableEvidenceComparator.Limits(
                limits.maximumEntitiesPerFrame(), limits.maximumFactsPerFrame(),
                limits.maximumEncodedEvidenceBytes());
    }

    /** Submits or polls one at-most-once bounded determinism comparison. */
    public DeterminismOperation check(
            DeterminismSpec spec, String requestId, Duration timeout) {
        Objects.requireNonNull(spec, "spec");
        IdentifierSupport.validate(requestId, "determinism request id");
        CommandDispatch dispatch = runtime.commands().orElseThrow(() ->
                new IllegalStateException("determinism comparison requires command dispatch"));
        long timeoutNanos = requireTimeout(timeout, dispatch.limits().maximumTimeoutNanos());
        validate(spec);
        Evidence evidence;
        synchronized (this) {
            evidence = operations.get(requestId);
            if (evidence != null) {
                if (!evidence.spec.equals(spec)) {
                    throw new IllegalArgumentException(
                            "determinism request id is bound to a different specification");
                }
                return snapshot(requestId, evidence, dispatch.status(requestId));
            }
            DeterminismSpec evictedSpec = evictedOperations.get(requestId);
            if (evictedSpec != null) {
                if (!evictedSpec.equals(spec)) {
                    throw new IllegalArgumentException(
                            "determinism request id is bound to a different specification");
                }
                return evicted(requestId, spec, dispatch.status(requestId));
            }
            if (simulationOperations.containsKey(requestId)
                    || evictedSimulationOperations.containsKey(requestId)) {
                throw new IllegalArgumentException(
                        "determinism request id is bound to a different specification");
            }
            if (dispatch.status(requestId).kind() != CommandLookup.Kind.UNKNOWN) {
                throw new IllegalArgumentException(
                        "determinism request correlation evidence is no longer retained");
            }
            makeRoom(dispatch);
            evidence = new Evidence(spec);
            operations.put(requestId, evidence);
            operationOrder.put(requestId, OperationKind.LEGACY);
        }
        Evidence retained = evidence;
        long executionNanos = Math.min(timeoutNanos, limits.maximumExecutionNanos());
        long deadline = deadline(executionNanos);
        CommandLookup lookup = dispatch.submit(requestId, timeout, () ->
                retained.result = Optional.of(
                        execute(spec, deadline, executionNanos, requestId)));
        return snapshot(requestId, retained, lookup);
    }

    /** Returns configured hard determinism bounds. */
    public DeterminismLimits limits() {
        return limits;
    }

    /** Reports whether exact acknowledged simulation determinism can accept a request. */
    public boolean simulationAvailable() {
        return runtime.commands().isPresent() && runtime.controls().acknowledgedTicksAvailable()
                && runtime.scenarios().determinismAvailable()
                && runtime.simulation().state().configured();
    }

    /** Submits or polls one at-most-once exact simulation-tick determinism comparison. */
    public SimulationDeterminismOperation checkSimulation(
            SimulationDeterminismSpec spec, String requestId, Duration timeout) {
        Objects.requireNonNull(spec, "spec");
        IdentifierSupport.validate(requestId, "simulation determinism request id");
        CommandDispatch dispatch = runtime.commands().orElseThrow(() ->
                new IllegalStateException("determinism comparison requires command dispatch"));
        long timeoutNanos = requireTimeout(timeout, dispatch.limits().maximumTimeoutNanos());
        validateSimulationBounds(spec);
        SimulationEvidence evidence;
        synchronized (this) {
            evidence = simulationOperations.get(requestId);
            if (evidence != null) {
                if (!evidence.spec.equals(spec)) {
                    throw new IllegalArgumentException(
                            "determinism request id is bound to a different specification");
                }
                return simulationSnapshot(requestId, evidence, dispatch.status(requestId));
            }
            SimulationDeterminismSpec evictedSpec = evictedSimulationOperations.get(requestId);
            if (evictedSpec != null) {
                if (!evictedSpec.equals(spec)) {
                    throw new IllegalArgumentException(
                            "determinism request id is bound to a different specification");
                }
                return simulationEvicted(requestId, spec, dispatch.status(requestId));
            }
            if (operations.containsKey(requestId) || evictedOperations.containsKey(requestId)) {
                throw new IllegalArgumentException(
                        "determinism request id is bound to a different specification");
            }
        }
        validateSimulationEnvironment(spec);
        synchronized (this) {
            evidence = simulationOperations.get(requestId);
            if (evidence != null) {
                if (!evidence.spec.equals(spec)) {
                    throw new IllegalArgumentException(
                            "determinism request id is bound to a different specification");
                }
                return simulationSnapshot(requestId, evidence, dispatch.status(requestId));
            }
            SimulationDeterminismSpec concurrentlyEvicted =
                    evictedSimulationOperations.get(requestId);
            if (concurrentlyEvicted != null) {
                if (!concurrentlyEvicted.equals(spec)) {
                    throw new IllegalArgumentException(
                            "determinism request id is bound to a different specification");
                }
                return simulationEvicted(requestId, spec, dispatch.status(requestId));
            }
            if (operations.containsKey(requestId) || evictedOperations.containsKey(requestId)
                    || dispatch.status(requestId).kind() != CommandLookup.Kind.UNKNOWN) {
                throw new IllegalArgumentException(
                        "determinism request correlation evidence is no longer retained");
            }
            makeRoom(dispatch);
            evidence = new SimulationEvidence(spec);
            simulationOperations.put(requestId, evidence);
            operationOrder.put(requestId, OperationKind.SIMULATION);
        }
        SimulationEvidence retained = evidence;
        long executionNanos = Math.min(timeoutNanos, limits.maximumExecutionNanos());
        long deadline = deadline(executionNanos);
        CommandLookup lookup = dispatch.submit(requestId, timeout, () ->
                retained.result = Optional.of(
                        executeSimulation(spec, deadline, executionNanos, requestId)));
        return simulationSnapshot(requestId, retained, lookup);
    }

    private DeterminismResult execute(
            DeterminismSpec spec, long deadline, long executionNanos, String requestId) {
        ArrayList<RunEvidence> runs = new ArrayList<>();
        ExecutionCounters counters = new ExecutionCounters();
        boolean previouslyPaused;
        try {
            previouslyPaused = runtime.controls().pauseForDeterminism();
        } catch (RuntimeException | Error failure) {
            return inconclusive(spec, counters, executionNanos,
                    failureEvidence(requestId, "determinism.pause", failure));
        }
        long uiEvictions = runtime.uiCorrelations().evictedFrameCount();
        DeterminismResult result = executeWhilePaused(
                spec, deadline, executionNanos, requestId, runs, counters, uiEvictions);
        try {
            runtime.controls().restorePauseAfterDeterminism(previouslyPaused);
        } catch (RuntimeException | Error failure) {
            result = inconclusive(spec, counters, executionNanos,
                    failureEvidence(requestId, "determinism.restore", failure));
        }
        return result;
    }

    private DeterminismResult executeWhilePaused(DeterminismSpec spec, long deadline,
            long executionNanos, String requestId, ArrayList<RunEvidence> runs,
            ExecutionCounters counters, long uiEvictions) {
        try {
            for (int repeat = 0; repeat < spec.repeatCount(); repeat++) {
                if (expired(deadline)) {
                    return inconclusive(spec, counters, executionNanos,
                            "execution deadline elapsed");
                }
                FrameId baseline = runtime.scenarios().resetForDeterminism(
                        spec.scenarioId(), new ScenarioResetContext(
                                OptionalLong.of(spec.randomSeed()), spec.configuration()));
                ArrayList<ObservableEvidenceComparator.FrameEvidence> frames = new ArrayList<>();
                FrameSnapshot baselineSnapshot = runtime.frame(baseline).orElseThrow();
                Optional<ObservableEvidenceComparator.FrameEvidence> baselineEvidence =
                        capture(baselineSnapshot, spec.profile(), counters);
                if (baselineEvidence.isEmpty()) {
                    return inconclusive(spec, counters, executionNanos,
                            counters.incompleteReason().orElse(
                                    "determinism evidence bounds were exceeded"));
                }
                frames.add(baselineEvidence.orElseThrow());
                for (int tick = 1; tick <= spec.ticksPerRepeat(); tick++) {
                    if (expired(deadline)) {
                        return inconclusive(spec, counters, executionNanos,
                                "execution deadline elapsed");
                    }
                    FrameSnapshot frame = runtime.controls().tickForDeterminism(spec.deltaNanos());
                    Optional<ObservableEvidenceComparator.FrameEvidence> evidence =
                            capture(frame, spec.profile(), counters);
                    if (evidence.isEmpty()) {
                        return inconclusive(spec, counters, executionNanos,
                                counters.incompleteReason().orElse(
                                        "determinism evidence bounds were exceeded"));
                    }
                    frames.add(evidence.orElseThrow());
                }
                runs.add(new RunEvidence(runtime.currentEpoch(), List.copyOf(frames)));
                counters.completedRepeats++;
                if (counters.incompleteReason().isPresent()) {
                    return inconclusive(spec, counters, executionNanos,
                            counters.incompleteReason().orElseThrow());
                }
                if (spec.profile().includeUiCorrelations()
                        && runtime.uiCorrelations().evictedFrameCount() != uiEvictions) {
                    return inconclusive(spec, counters, executionNanos,
                            "UI correlation evidence was evicted");
                }
            }
            return compare(spec, runs, counters, executionNanos);
        } catch (RuntimeException | Error failure) {
            return inconclusive(spec, counters, executionNanos,
                    failureEvidence(requestId, "determinism.execute", failure));
        }
    }

    private SimulationDeterminismResult executeSimulation(SimulationDeterminismSpec spec,
            long deadline, long executionNanos, String requestId) {
        ArrayList<SimulationRunEvidence> runs = new ArrayList<>();
        ExecutionCounters counters = new ExecutionCounters();
        try {
            runtime.inputs().beginDeterminism(spec.inputs());
        } catch (RuntimeException | Error failure) {
            return simulationInconclusive(spec, counters, executionNanos,
                    failureEvidence(requestId, "simulationDeterminism.inputs", failure));
        }
        boolean previouslyPaused;
        try {
            previouslyPaused = runtime.controls().pauseForDeterminism();
        } catch (RuntimeException | Error failure) {
            runtime.inputs().endDeterminism();
            return simulationInconclusive(spec, counters, executionNanos,
                    failureEvidence(requestId, "simulationDeterminism.pause", failure));
        }
        long uiEvictions = runtime.uiCorrelations().evictedFrameCount();
        SimulationDeterminismResult result = executeSimulationWhilePaused(
                spec, deadline, executionNanos, requestId, runs, counters, uiEvictions);
        try {
            runtime.controls().restorePauseAfterDeterminism(previouslyPaused);
        } catch (RuntimeException | Error failure) {
            result = simulationInconclusive(spec, counters, executionNanos,
                    failureEvidence(requestId, "simulationDeterminism.restore", failure));
        } finally {
            runtime.inputs().endDeterminism();
        }
        return result;
    }

    private SimulationDeterminismResult executeSimulationWhilePaused(
            SimulationDeterminismSpec spec, long deadline, long executionNanos,
            String requestId, ArrayList<SimulationRunEvidence> runs,
            ExecutionCounters counters, long uiEvictions) {
        try {
            for (int repeat = 0; repeat < spec.execution().repeatCount(); repeat++) {
                if (expired(deadline)) {
                    return simulationInconclusive(spec, counters, executionNanos,
                            "execution deadline elapsed");
                }
                FrameId baseline = runtime.scenarios().resetForDeterminism(
                        spec.execution().scenarioId(), new ScenarioResetContext(
                                OptionalLong.of(spec.execution().randomSeed()),
                                spec.execution().configuration()));
                FrameSnapshot baselineSnapshot = runtime.frame(baseline).orElseThrow();
                Optional<String> baselineProblem = configurationProblem(
                        baselineSnapshot, spec.configurationRequirements());
                if (baselineProblem.isPresent()) {
                    return simulationInconclusive(spec, counters, executionNanos,
                            baselineProblem.orElseThrow());
                }
                Optional<String> baselineSelection = selectionProblem(
                        baselineSnapshot, spec.execution().profile().comparisonScope());
                if (baselineSelection.isPresent()) {
                    return simulationInconclusive(spec, counters, executionNanos,
                            baselineSelection.orElseThrow());
                }
                if (capture(baselineSnapshot, spec.execution().profile(), counters,
                        spec.eventTypes()).isEmpty()) {
                    return simulationInconclusive(spec, counters, executionNanos,
                            counters.incompleteReason().orElse(
                                    "determinism evidence bounds were exceeded"));
                }
                ArrayList<SimulationFrameEvidence> frames = new ArrayList<>();
                for (int epochTick = 1;
                        epochTick <= spec.execution().ticksPerRepeat(); epochTick++) {
                    if (expired(deadline)) {
                        return simulationInconclusive(spec, counters, executionNanos,
                                "execution deadline elapsed");
                    }
                    List<SimulationDeterminismInput> tickInputs = inputsAt(
                            spec.inputs(), epochTick);
                    SimulationControlRegistry.DeterminismTickEvidence completed =
                            runtime.controls().tickForDeterminism(
                                    spec.execution().deltaNanos(), tickInputs);
                    Optional<String> tickProblem = tickProblem(
                            completed, epochTick, spec.execution().deltaNanos());
                    if (tickProblem.isPresent()) {
                        return simulationInconclusive(spec, counters, executionNanos,
                                tickProblem.orElseThrow());
                    }
                    Optional<String> requirementProblem = evidenceProblem(
                            completed.frame(), spec.evidenceRequirements());
                    if (requirementProblem.isPresent()) {
                        return simulationInconclusive(spec, counters, executionNanos,
                                requirementProblem.orElseThrow());
                    }
                    Optional<String> selectionProblem = selectionProblem(
                            completed.frame(), spec.execution().profile().comparisonScope());
                    if (selectionProblem.isPresent()) {
                        return simulationInconclusive(spec, counters, executionNanos,
                                selectionProblem.orElseThrow());
                    }
                    Optional<ObservableEvidenceComparator.FrameEvidence> evidence =
                            capture(completed.frame(),
                            spec.execution().profile(), counters, spec.eventTypes());
                    if (evidence.isEmpty()) {
                        return simulationInconclusive(spec, counters, executionNanos,
                                counters.incompleteReason().orElse(
                                        "determinism evidence bounds were exceeded"));
                    }
                    frames.add(new SimulationFrameEvidence(
                            completed.tick(), evidence.orElseThrow()));
                }
                runs.add(new SimulationRunEvidence(
                        runtime.currentEpoch(), List.copyOf(frames)));
                counters.completedRepeats++;
                if (counters.incompleteReason().isPresent()) {
                    return simulationInconclusive(spec, counters, executionNanos,
                            counters.incompleteReason().orElseThrow());
                }
                if (spec.execution().profile().includeUiCorrelations()
                        && runtime.uiCorrelations().evictedFrameCount() != uiEvictions) {
                    return simulationInconclusive(spec, counters, executionNanos,
                            "UI correlation evidence was evicted");
                }
            }
            return compareSimulation(spec, runs, counters, executionNanos);
        } catch (RuntimeException | Error failure) {
            return simulationInconclusive(spec, counters, executionNanos,
                    failureEvidence(requestId, "simulationDeterminism.execute", failure));
        }
    }

    private SimulationDeterminismResult compareSimulation(SimulationDeterminismSpec spec,
            List<SimulationRunEvidence> runs, ExecutionCounters counters, long executionNanos) {
        SimulationRunEvidence reference = runs.getFirst();
        for (int repeat = 1; repeat < runs.size(); repeat++) {
            SimulationRunEvidence candidate = runs.get(repeat);
            for (int index = 0; index < reference.frames.size(); index++) {
                SimulationFrameEvidence left = reference.frames.get(index);
                SimulationFrameEvidence right = candidate.frames.get(index);
                Optional<DeterminismDifference> difference =
                        difference(left.frame(), right.frame());
                if (difference.isPresent()) {
                    return new SimulationDeterminismResult(DeterminismStatus.DIVERGED,
                            "first divergence in selected simulation evidence",
                            spec.execution().profile(), Optional.of(
                                    new SimulationDeterminismDivergence(
                                            left.tick.epochTick(),
                                            left.tick.simulationTickId(),
                                            right.tick.simulationTickId(),
                                            reference.epoch, candidate.epoch,
                                            left.frame().frameId(), right.frame().frameId(),
                                            difference.orElseThrow())),
                            bounds(spec.execution(), counters, executionNanos), Optional.empty());
                }
            }
        }
        return new SimulationDeterminismResult(DeterminismStatus.EQUAL,
                "equal for the selected simulation evidence; whole-program determinism is not proven",
                spec.execution().profile(), Optional.empty(),
                bounds(spec.execution(), counters, executionNanos), Optional.empty());
    }

    private static List<SimulationDeterminismInput> inputsAt(
            List<SimulationDeterminismInput> inputs, long epochTick) {
        return inputs.stream().filter(input -> input.epochTick() == epochTick).toList();
    }

    private Optional<String> tickProblem(
            SimulationControlRegistry.DeterminismTickEvidence evidence,
            long expectedEpochTick, long fixedStepNanos) {
        SimulationTick tick = evidence.tick();
        if (tick.epochTick() != expectedEpochTick
                || tick.source() != SimulationTickSource.PAUSED
                || tick.outcome() != SimulationTickOutcome.COMPLETED
                || tick.mutationOutcome() != SimulationMutationOutcome.KNOWN_COMPLETED
                || tick.configuredFixedStepNanos().isEmpty()
                || tick.configuredFixedStepNanos().orElseThrow() != fixedStepNanos
                || tick.runtimeSuppliedDeltaNanos() != fixedStepNanos
                || tick.executedDeltaNanos().isEmpty()
                || tick.executedDeltaNanos().orElseThrow() != fixedStepNanos
                || tick.resultingFrameId().isEmpty()
                || !tick.resultingFrameId().orElseThrow().equals(evidence.frame().frameId())
                || !tick.executionEpochId().equals(evidence.frame().executionEpochId())) {
            return Optional.of("simulation tick evidence is incomplete or mismatched");
        }
        return Optional.empty();
    }

    private Optional<String> configurationProblem(FrameSnapshot frame,
            List<SimulationConfigurationRequirement> requirements) {
        return evidenceComparator.configurationProblem(frame, requirements);
    }

    private Optional<String> evidenceProblem(FrameSnapshot frame,
            List<SimulationEvidenceRequirement> requirements) {
        return evidenceComparator.evidenceProblem(frame, requirements);
    }

    private Optional<String> selectionProblem(
            FrameSnapshot frame, SnapshotComparisonScope scope) {
        return evidenceComparator.selectionProblem(frame, scope);
    }

    private Optional<ObservableEvidenceComparator.FrameEvidence> capture(
            FrameSnapshot frame, DeterminismProfile profile, ExecutionCounters counters) {
        return capture(frame, profile, counters, List.of());
    }

    private Optional<ObservableEvidenceComparator.FrameEvidence> capture(FrameSnapshot frame,
            DeterminismProfile profile, ExecutionCounters counters, List<EventType> eventTypes) {
        return evidenceComparator.capture(
                frame, profile, eventTypes, evidenceLimits, counters.observable);
    }

    private DeterminismResult compare(DeterminismSpec spec, List<RunEvidence> runs,
            ExecutionCounters counters, long executionNanos) {
        RunEvidence reference = runs.getFirst();
        for (int repeat = 1; repeat < runs.size(); repeat++) {
            RunEvidence candidate = runs.get(repeat);
            for (int tick = 0; tick < reference.frames.size(); tick++) {
                ObservableEvidenceComparator.FrameEvidence left = reference.frames.get(tick);
                ObservableEvidenceComparator.FrameEvidence right = candidate.frames.get(tick);
                Optional<DeterminismDifference> difference = difference(left, right);
                if (difference.isPresent()) {
                    return new DeterminismResult(
                            DeterminismStatus.DIVERGED,
                            "first divergence in configured observable state",
                            spec.profile(), OptionalInt.of(tick), Optional.of(reference.epoch),
                            Optional.of(candidate.epoch), Optional.of(left.frameId()),
                            Optional.of(right.frameId()), difference,
                            bounds(spec, counters, executionNanos), Optional.empty());
                }
            }
        }
        return new DeterminismResult(
                DeterminismStatus.EQUAL, EQUAL_MESSAGE, spec.profile(), OptionalInt.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), bounds(spec, counters, executionNanos), Optional.empty());
    }

    private Optional<DeterminismDifference> difference(
            ObservableEvidenceComparator.FrameEvidence left,
            ObservableEvidenceComparator.FrameEvidence right) {
        return evidenceComparator.difference(left, right);
    }

    private DeterminismResult inconclusive(DeterminismSpec spec, ExecutionCounters counters,
            long executionNanos, String message) {
        return inconclusive(spec, counters, executionNanos, message, Optional.empty());
    }

    private DeterminismResult inconclusive(DeterminismSpec spec, ExecutionCounters counters,
            long executionNanos, ApplicationFailureEvidence failure) {
        return inconclusive(spec, counters, executionNanos,
                failure.legacyEnvelope(), Optional.of(failure));
    }

    private DeterminismResult inconclusive(DeterminismSpec spec, ExecutionCounters counters,
            long executionNanos, String message,
            Optional<ApplicationFailureEvidence> applicationFailure) {
        return new DeterminismResult(
                DeterminismStatus.INCONCLUSIVE, message, spec.profile(), OptionalInt.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), bounds(spec, counters, executionNanos), applicationFailure);
    }

    private SimulationDeterminismResult simulationInconclusive(
            SimulationDeterminismSpec spec, ExecutionCounters counters,
            long executionNanos, String message) {
        return simulationInconclusive(
                spec, counters, executionNanos, message, Optional.empty());
    }

    private SimulationDeterminismResult simulationInconclusive(
            SimulationDeterminismSpec spec, ExecutionCounters counters,
            long executionNanos, ApplicationFailureEvidence failure) {
        return simulationInconclusive(spec, counters, executionNanos,
                failure.legacyEnvelope(), Optional.of(failure));
    }

    private SimulationDeterminismResult simulationInconclusive(
            SimulationDeterminismSpec spec, ExecutionCounters counters, long executionNanos,
            String message, Optional<ApplicationFailureEvidence> applicationFailure) {
        return new SimulationDeterminismResult(DeterminismStatus.INCONCLUSIVE,
                message, spec.execution().profile(), Optional.empty(),
                bounds(spec.execution(), counters, executionNanos), applicationFailure);
    }

    private ApplicationFailureEvidence failureEvidence(
            String requestId, String category, Throwable failure) {
        Optional<String> correlationId =
                runtime.commands().orElseThrow().correlationId(requestId);
        return correlationId.isPresent()
                ? runtime.diagnostics().describe(category, failure, correlationId.orElseThrow())
                : runtime.diagnostics().describe(category, failure);
    }

    private DeterminismBounds bounds(
            DeterminismSpec spec, ExecutionCounters counters, long executionNanos) {
        return new DeterminismBounds(counters.completedRepeats, spec.ticksPerRepeat(),
                counters.observable.observedEntities(), counters.observable.observedFacts(),
                counters.observable.encodedEvidenceBytes(), executionNanos);
    }

    private void validate(DeterminismSpec spec) {
        if (spec.repeatCount() > limits.maximumRepeats()
                || spec.ticksPerRepeat() > limits.maximumTicksPerRepeat()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "determinism execution limit exceeded");
        }
        long configurationBytes = spec.configuration().toString()
                .getBytes(StandardCharsets.UTF_8).length;
        if (spec.configuration().fields().size() > limits.maximumFactsPerFrame()
                || configurationBytes > limits.maximumEncodedEvidenceBytes()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "determinism configuration limit exceeded");
        }
        if (!runtime.controls().available()) {
            throw new IllegalStateException(
                    "determinism comparison requires simulation control");
        }
    }

    private void validateSimulationBounds(SimulationDeterminismSpec spec) {
        validate(spec.execution());
        spec.inputs().forEach(input ->
                RuntimeValueValidator.validate(
                        input.parameters(), runtime.configuration().limits()));
        spec.configurationRequirements().forEach(requirement ->
                RuntimeValueValidator.validate(
                        requirement.expected(), runtime.configuration().limits()));
        long requestFacts = (long) spec.inputs().size()
                + spec.configurationRequirements().size()
                + spec.evidenceRequirements().size() + spec.eventTypes().size();
        if (requestFacts > limits.maximumFactsPerFrame()) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "simulation determinism request evidence limit exceeded");
        }
        long requestBytes = DeterminismCanonicalSize.listPrefix();
        for (SimulationDeterminismInput input : spec.inputs()) {
            requestBytes = addRequestBytes(requestBytes,
                    DeterminismCanonicalSize.simulationInput(input));
        }
        requestBytes = addRequestBytes(requestBytes, DeterminismCanonicalSize.listPrefix());
        for (SimulationConfigurationRequirement requirement
                : spec.configurationRequirements()) {
            requestBytes = addRequestBytes(requestBytes,
                    DeterminismCanonicalSize.simulationConfiguration(requirement));
        }
        requestBytes = addRequestBytes(requestBytes, DeterminismCanonicalSize.listPrefix());
        for (SimulationEvidenceRequirement requirement : spec.evidenceRequirements()) {
            requestBytes = addRequestBytes(requestBytes,
                    DeterminismCanonicalSize.simulationEvidence(requirement));
        }
        requestBytes = addRequestBytes(requestBytes, DeterminismCanonicalSize.listPrefix());
        for (EventType eventType : spec.eventTypes()) {
            requestBytes = addRequestBytes(requestBytes,
                    DeterminismCanonicalSize.simulationEventType(eventType));
        }
    }

    private long addRequestBytes(long current, long candidate) {
        long total = DeterminismCanonicalSize.add(current, candidate);
        if (total > limits.maximumEncodedEvidenceBytes()) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "simulation determinism request evidence limit exceeded");
        }
        return total;
    }

    private void validateSimulationEnvironment(SimulationDeterminismSpec spec) {
        SimulationState state = runtime.simulation().state();
        if (!state.configured()
                || state.configuredFixedStepNanos().orElseThrow()
                        != spec.execution().deltaNanos()) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_QUERY,
                    "requested determinism step does not match the simulation timeline");
        }
        if (!runtime.controls().acknowledgedTicksAvailable()) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_QUERY,
                    "simulation determinism requires an acknowledged tick callback");
        }
        runtime.inputs().validateDeterminismInputs(spec.inputs());
        FrameSnapshot latest = runtime.latestFrame().orElseThrow(() ->
                new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                        "simulation determinism requires a completed baseline"));
        Optional<String> problem = configurationProblem(
                latest, spec.configurationRequirements());
        if (problem.isEmpty()) {
            problem = selectionProblem(
                    latest, spec.execution().profile().comparisonScope());
        }
        if (problem.isPresent()) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_QUERY,
                    problem.orElseThrow());
        }
    }

    private synchronized DeterminismOperation snapshot(
            String requestId, Evidence evidence, CommandLookup lookup) {
        return new DeterminismOperation(evidence.spec, requestId, lookup, evidence.result);
    }

    private synchronized SimulationDeterminismOperation simulationSnapshot(
            String requestId, SimulationEvidence evidence, CommandLookup lookup) {
        return new SimulationDeterminismOperation(
                evidence.spec, requestId, lookup, evidence.result);
    }

    private SimulationDeterminismOperation simulationEvicted(String requestId,
            SimulationDeterminismSpec spec, CommandLookup lookup) {
        SimulationDeterminismResult result = new SimulationDeterminismResult(
                DeterminismStatus.INCONCLUSIVE,
                "simulation determinism result evidence was evicted",
                spec.execution().profile(), Optional.empty(),
                new DeterminismBounds(0, spec.execution().ticksPerRepeat(),
                        0, 0, 0, limits.maximumExecutionNanos()), Optional.empty());
        return new SimulationDeterminismOperation(
                spec, requestId, lookup, Optional.of(result));
    }

    private DeterminismOperation evicted(
            String requestId, DeterminismSpec spec, CommandLookup lookup) {
        DeterminismResult result = new DeterminismResult(
                DeterminismStatus.INCONCLUSIVE, "determinism result evidence was evicted",
                spec.profile(), OptionalInt.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                new DeterminismBounds(
                        0, spec.ticksPerRepeat(), 0, 0, 0, limits.maximumExecutionNanos()),
                Optional.empty());
        return new DeterminismOperation(spec, requestId, lookup, Optional.of(result));
    }

    private void makeRoom(CommandDispatch dispatch) {
        if (operationOrder.size() < limits.retainedOperations()) {
            return;
        }
        String oldest = operationOrder.keySet().iterator().next();
        CommandLookup lookup = dispatch.status(oldest);
        if (lookup.kind() != CommandLookup.Kind.FOUND
                || lookup.status().orElseThrow().state() == CommandState.QUEUED
                || lookup.status().orElseThrow().state() == CommandState.EXECUTING) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "determinism operation retention is full");
        }
        OperationKind kind = operationOrder.remove(oldest);
        if (kind == OperationKind.LEGACY) {
            Evidence removed = operations.remove(oldest);
            evictedOperations.put(oldest, removed.spec);
        } else {
            SimulationEvidence removed = simulationOperations.remove(oldest);
            evictedSimulationOperations.put(oldest, removed.spec);
        }
        evictedOrder.put(oldest, kind);
        while (evictedOrder.size() > dispatch.limits().retainedRequestIds()) {
            String expired = evictedOrder.keySet().iterator().next();
            OperationKind expiredKind = evictedOrder.remove(expired);
            if (expiredKind == OperationKind.LEGACY) {
                evictedOperations.remove(expired);
            } else {
                evictedSimulationOperations.remove(expired);
            }
        }
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

    private static long requireTimeout(Duration timeout, long maximumNanos) {
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

    synchronized void close() {
        operations.clear();
        evictedOperations.clear();
        simulationOperations.clear();
        evictedSimulationOperations.clear();
        operationOrder.clear();
        evictedOrder.clear();
    }

    private static final class Evidence {
        private final DeterminismSpec spec;
        private Optional<DeterminismResult> result = Optional.empty();

        private Evidence(DeterminismSpec spec) {
            this.spec = spec;
        }
    }

    private static final class SimulationEvidence {
        private final SimulationDeterminismSpec spec;
        private Optional<SimulationDeterminismResult> result = Optional.empty();

        private SimulationEvidence(SimulationDeterminismSpec spec) {
            this.spec = spec;
        }
    }

    private enum OperationKind { LEGACY, SIMULATION }

    private static final class ExecutionCounters {
        private int completedRepeats;
        private final ObservableEvidenceComparator.Counters observable =
                new ObservableEvidenceComparator.Counters();

        private Optional<String> incompleteReason() {
            return observable.incompleteReason();
        }
    }

    private record RunEvidence(ExecutionEpochId epoch,
            List<ObservableEvidenceComparator.FrameEvidence> frames) {}

    private record SimulationRunEvidence(
            ExecutionEpochId epoch, List<SimulationFrameEvidence> frames) {}

    private record SimulationFrameEvidence(SimulationTick tick,
            ObservableEvidenceComparator.FrameEvidence frame) {}
}
