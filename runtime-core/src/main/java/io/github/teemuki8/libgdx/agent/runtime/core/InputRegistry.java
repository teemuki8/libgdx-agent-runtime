package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Explicit bounded registry and deterministic controlled-tick scheduler for input facts. */
public final class InputRegistry {
    private final Object submissionLock = new Object();
    private final AgentRuntime runtime;
    private final InputLimits limits;
    private final InputTimelineLimits timelineLimits;
    private final InputTimelineExecutor timelines;
    private final LinkedHashMap<String, InputDescriptor> inputs = new LinkedHashMap<>();
    private final LinkedHashMap<String, Consumer<InputParameters>> handlers = new LinkedHashMap<>();
    private final LinkedHashMap<String, Evidence> requests = new LinkedHashMap<>();
    private final LinkedHashSet<String> timelineParentIds = new LinkedHashSet<>();
    private final TreeMap<Long, ArrayDeque<Evidence>> scheduled = new TreeMap<>();
    private final Map<Long, List<Evidence>> executedByTick = new LinkedHashMap<>();
    private int outstanding;
    private boolean determinismExecuting;
    private boolean timelineExecuting;
    private long timelineDeadlineNanos = Long.MAX_VALUE;

    InputRegistry(AgentRuntime runtime, InputLimits limits, InputTimelineLimits timelineLimits) {
        this.runtime = runtime;
        this.limits = limits;
        this.timelineLimits = timelineLimits;
        timelines = new InputTimelineExecutor(runtime, this, timelineLimits);
    }

    /** Registers one input type before runtime start. */
    public synchronized void register(InputSpec spec) {
        runtime.requireInputRegistration();
        Objects.requireNonNull(spec, "spec");
        if (inputs.containsKey(spec.descriptor().id())) {
            throw new IllegalArgumentException("input id is already registered");
        }
        if (inputs.size() >= limits.registeredInputs()
                || spec.descriptor().parameters().size() > limits.parametersPerInput()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "input registration limit reached");
        }
        inputs.put(spec.descriptor().id(), spec.descriptor());
        handlers.put(spec.descriptor().id(), spec.handler());
    }

    /** Returns registered input descriptors in stable registration order. */
    public synchronized List<InputDescriptor> list() {
        return List.copyOf(inputs.values());
    }

    /** Returns configured hard input bounds. */
    public InputLimits limits() {
        return limits;
    }

    /** Submits or polls one at-most-once exact-tick input timeline. */
    public InputTimelineOperation executeTimeline(
            InputTimelineSpec spec, String requestId, Duration timeout) {
        runtime.requireSubmissionsOpen();
        synchronized (submissionLock) {
            return timelines.execute(spec, requestId, timeout);
        }
    }

    /** Returns configured hard input-timeline bounds. */
    public InputTimelineLimits timelineLimits() {
        return timelines.limits();
    }

    /** Reports whether the registrations required by input timelines are available. */
    public synchronized boolean timelineAvailable() {
        return runtime.commands().isPresent()
                && runtime.controls().acknowledgedTicksAvailable()
                && runtime.simulation().state().configured()
                && !inputs.isEmpty();
    }

    /** Package-private close observation: number of retained application input handlers. */
    synchronized int retainedInputHandlers() {
        return handlers.size();
    }

    /** Package-private close observation: number of retained queued/scheduled injections. */
    synchronized int retainedPendingInjections() {
        return outstanding;
    }

    /**
     * Releases application input handlers and queued/scheduled injection state while keeping the
     * immutable input catalog.
     *
     * <p>Takes the submission lock and the registry monitor in the same order as {@link #inject},
     * so an in-flight injection cannot retain evidence after close.
     */
    void close() {
        synchronized (submissionLock) {
            synchronized (this) {
                timelines.close();
                handlers.clear();
                requests.clear();
                timelineParentIds.clear();
                scheduled.clear();
                executedByTick.clear();
                outstanding = 0;
                determinismExecuting = false;
                timelineExecuting = false;
            }
        }
    }

    /**
     * Submits, schedules, or polls one at-most-once input for the next or an explicit controlled
     * tick.
     */
    public InputInjection inject(String inputId, String requestId,
            RuntimeValue.ObjectValue parameters, OptionalLong requestedTargetTick,
            Duration timeout) {
        runtime.requireSubmissionsOpen();
        synchronized (submissionLock) {
            return injectOrdered(
                    inputId, requestId, parameters, requestedTargetTick, timeout);
        }
    }

    private InputInjection injectOrdered(String inputId, String requestId,
            RuntimeValue.ObjectValue parameters, OptionalLong requestedTargetTick,
            Duration timeout) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(requestedTargetTick, "requestedTargetTick");
        IdentifierSupport.validate(requestId, "input request id");
        CommandDispatch dispatch = runtime.commands().orElseThrow(() ->
                new IllegalStateException("input injection requires application command dispatch"));
        requireValidTimeout(timeout, dispatch.limits().maximumTimeoutNanos());
        InputDescriptor descriptor;
        Consumer<InputParameters> handler;
        Evidence evidence;
        boolean existing;
        synchronized (this) {
            if (determinismExecuting || timelineExecuting) {
                throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                        "input injection is unavailable during exclusive input execution");
            }
            descriptor = inputs.get(inputId);
            if (descriptor == null) {
                throw new IllegalArgumentException("unknown input id");
            }
            handler = handlers.get(inputId);
            validate(descriptor, parameters);
            if (timelineParentIds.contains(requestId)) {
                throw new IllegalArgumentException(
                        "request id is reserved by an input timeline");
            }
            evidence = requests.get(requestId);
            existing = evidence != null;
            if (evidence != null && evidence.timelineParentId != null) {
                throw new IllegalArgumentException(
                        "request id is reserved by an input timeline");
            }
            if (evidence != null && (!evidence.inputId.equals(inputId)
                    || !evidence.parameters.equals(parameters)
                    || !evidence.requestedTargetTick.equals(requestedTargetTick))) {
                throw new IllegalArgumentException("request id is bound to a different input");
            }
            if (evidence == null) {
                runtime.requireSubmissionsOpen();
                requireTargetingState();
                long currentTick = runtime.controls().currentTick();
                long targetTick = requestedTargetTick.isPresent()
                        ? requestedTargetTick.orElseThrow() : Math.addExact(currentTick, 1);
                validateTarget(currentTick, targetTick);
                if (outstanding >= limits.queuedInputs()) {
                    throw new AgentRuntimeException(
                            RuntimeErrorCode.LIMIT_EXCEEDED, "input queue limit reached");
                }
                makeRoom();
                if (dispatch.status(requestId).kind() != CommandLookup.Kind.UNKNOWN) {
                    throw new IllegalArgumentException(
                            "request correlation evidence is no longer retained");
                }
                evidence = new Evidence(inputId, requestId, parameters, requestedTargetTick,
                        targetTick, runtime.currentEpoch(),
                        runtime.latestFrame().map(FrameSnapshot::frameId),
                        descriptor.redactionPolicy()
                                == InputRedactionPolicy.OMIT_PARAMETERS);
                requests.put(requestId, evidence);
                outstanding++;
            }
        }
        if (existing) {
            return snapshot(evidence, dispatch.status(requestId));
        }
        Evidence retained = evidence;
        retained.handler = handler;
        CommandLookup lookup = dispatch.submit(requestId, timeout, () -> {
            try {
                schedule(retained);
            } catch (RuntimeException | Error failure) {
                failBeforeExecution(retained, failure);
                throw failure;
            }
        });
        return snapshot(retained, lookup);
    }

    synchronized void executeTick(long tick, ExecutionEpochId epochId) {
        ArrayDeque<Evidence> due = scheduled.remove(tick);
        if (due == null) {
            return;
        }
        ArrayList<Evidence> executed = new ArrayList<>();
        RuntimeException firstRuntimeFailure = null;
        Error firstError = null;
        while (!due.isEmpty()) {
            Evidence evidence = due.removeFirst();
            outstanding--;
            evidence.actualTick = OptionalLong.of(tick);
            executed.add(evidence);
            beginLogicalExecution(evidence);
            if (!evidence.executionEpochId.equals(epochId)) {
                evidence.state = InputInjectionState.FAILED;
                evidence.diagnostic = Optional.of("execution epoch changed before target tick");
                completeLogicalExecution(evidence, CommandState.FAILED);
                if (timelineExecuting) {
                    stopRemainingTimelineChildren(due,
                            InputTimelineCanonicalSize.LIFECYCLE_CHANGED_MESSAGE);
                    executedByTick.put(tick, List.copyOf(executed));
                    throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                            "input timeline lifecycle changed before target tick");
                }
                continue;
            }
            evidence.recordedParameters = evidence.parametersRedacted
                    ? Optional.empty() : Optional.of(evidence.parameters);
            try {
                evidence.handler.accept(new InputParameters(evidence.parameters));
            } catch (RuntimeException failure) {
                evidence.state = InputInjectionState.FAILED;
                recordFailure(evidence, "input.execution", failure);
                completeLogicalExecution(evidence, CommandState.FAILED);
                if (timelineExecuting) {
                    stopRemainingTimelineChildren(due,
                            InputTimelineCanonicalSize.INPUT_FAILED_MESSAGE);
                    executedByTick.put(tick, List.copyOf(executed));
                    throw failure;
                }
                if (firstRuntimeFailure == null) {
                    firstRuntimeFailure = failure;
                }
                continue;
            } catch (Error failure) {
                evidence.state = InputInjectionState.FAILED;
                recordFailure(evidence, "input.execution", failure);
                completeLogicalExecution(evidence, CommandState.FAILED);
                if (timelineExecuting) {
                    stopRemainingTimelineChildren(due,
                            InputTimelineCanonicalSize.INPUT_FAILED_MESSAGE);
                    executedByTick.put(tick, List.copyOf(executed));
                    throw failure;
                }
                if (firstError == null) {
                    firstError = failure;
                }
                continue;
            }
            evidence.state = InputInjectionState.EXECUTED;
            completeLogicalExecution(evidence, CommandState.SUCCEEDED);
            if (timelineExecuting) {
                if (runtime.monotonicTimeNanos() >= timelineDeadlineNanos) {
                    stopRemainingTimelineChildren(due,
                            InputTimelineCanonicalSize.TIMED_OUT_MESSAGE);
                    executedByTick.put(tick, List.copyOf(executed));
                    throw new InputTimelineExecutor.InputTimelineDeadlineExceeded();
                }
                if (!runtime.currentEpoch().equals(evidence.executionEpochId)) {
                    stopRemainingTimelineChildren(due,
                            InputTimelineCanonicalSize.LIFECYCLE_CHANGED_MESSAGE);
                    executedByTick.put(tick, List.copyOf(executed));
                    throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                            "input timeline lifecycle changed before the next transition");
                }
            }
        }
        executedByTick.put(tick, List.copyOf(executed));
        if (firstError != null) {
            throw firstError;
        }
        if (firstRuntimeFailure != null) {
            throw firstRuntimeFailure;
        }
    }

    private void stopRemainingTimelineChildren(ArrayDeque<Evidence> due, String diagnostic) {
        while (!due.isEmpty()) {
            Evidence remaining = due.removeFirst();
            outstanding--;
            remaining.state = InputInjectionState.FAILED;
            remaining.timelineNotExecuted = true;
            remaining.diagnostic = Optional.of(boundedDiagnostic(diagnostic));
        }
    }

    void beginDeterminism(List<SimulationDeterminismInput> script) {
        Objects.requireNonNull(script, "script");
        synchronized (submissionLock) {
            synchronized (this) {
                if (determinismExecuting || timelineExecuting) {
                    throw new IllegalStateException("determinism input execution is already active");
                }
                if (outstanding != 0 || !scheduled.isEmpty() || !executedByTick.isEmpty()) {
                    throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                            "determinism execution requires an empty ordinary input queue");
                }
                validateDeterminismScript(script);
                determinismExecuting = true;
            }
        }
    }

    synchronized void validateDeterminismInputs(List<SimulationDeterminismInput> script) {
        Objects.requireNonNull(script, "script");
        if (outstanding != 0 || !scheduled.isEmpty() || !executedByTick.isEmpty()) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                    "determinism execution requires an empty ordinary input queue");
        }
        validateDeterminismScript(script);
    }

    synchronized void validateReplayCaptureReady() {
        if (determinismExecuting || timelineExecuting || outstanding != 0
                || !scheduled.isEmpty() || !executedByTick.isEmpty()) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                    "replay capture requires an empty ordinary input queue");
        }
    }

    private void validateDeterminismScript(List<SimulationDeterminismInput> script) {
        for (SimulationDeterminismInput input : script) {
            InputDescriptor descriptor = inputs.get(input.inputId());
            if (descriptor == null || handlers.get(input.inputId()) == null) {
                throw new IllegalArgumentException("unknown determinism input id");
            }
            validate(descriptor, input.parameters());
        }
    }

    synchronized void executeDeterminismInputs(List<SimulationDeterminismInput> inputsForTick) {
        if (!determinismExecuting) {
            throw new IllegalStateException("determinism input execution is not active");
        }
        for (SimulationDeterminismInput input : inputsForTick) {
            Consumer<InputParameters> handler = handlers.get(input.inputId());
            if (handler == null) {
                throw new IllegalStateException("registered determinism input is unavailable");
            }
            handler.accept(new InputParameters(input.parameters()));
        }
    }

    void endDeterminism() {
        synchronized (submissionLock) {
            synchronized (this) {
                determinismExecuting = false;
            }
        }
    }

    synchronized void completeTick(long tick, FrameId resultingFrameId) {
        List<Evidence> executed = executedByTick.remove(tick);
        if (executed != null) {
            executed.forEach(evidence -> evidence.resultingFrameId = Optional.of(resultingFrameId));
            executed.stream().filter(evidence -> evidence.timelineParentId != null)
                    .forEach(evidence -> snapshot(evidence, logicalLookup(evidence)));
        }
    }

    synchronized void failTick(long tick) {
        List<Evidence> executed = executedByTick.remove(tick);
        if (executed != null) {
            executed.stream()
                    .filter(evidence -> evidence.state == InputInjectionState.EXECUTED)
                    .forEach(evidence -> evidence.diagnostic =
                            Optional.of("resulting frame did not complete"));
        }
    }

    private synchronized void schedule(Evidence evidence) {
        requireTargetingState();
        long currentTick = runtime.controls().currentTick();
        validateTarget(currentTick, evidence.targetTick);
        if (!runtime.currentEpoch().equals(evidence.executionEpochId)) {
            throw new IllegalStateException("execution epoch changed before input scheduling");
        }
        scheduled.computeIfAbsent(evidence.targetTick, ignored -> new ArrayDeque<>())
                .addLast(evidence);
        evidence.state = InputInjectionState.SCHEDULED;
    }

    private synchronized void failBeforeExecution(Evidence evidence, Throwable failure) {
        if (evidence.state == InputInjectionState.QUEUED) {
            outstanding--;
        }
        evidence.state = InputInjectionState.FAILED;
        recordFailure(evidence, "input.schedule", failure);
    }

    private synchronized InputInjection snapshot(Evidence evidence, CommandLookup command) {
        reconcileTerminalDispatch(evidence, command);
        InputInjection injection = new InputInjection(
                evidence.inputId, evidence.requestId, command, evidence.state,
                evidence.targetTick, evidence.actualTick, evidence.executionEpochId,
                evidence.submittedFrameId, evidence.resultingFrameId,
                evidence.recordedParameters, evidence.parametersRedacted, evidence.diagnostic,
                evidence.applicationFailure);
        runtime.recordings().recordInput(injection);
        runtime.replays().recordInput(injection);
        return injection;
    }

    synchronized Optional<InputInjection> recording(String requestId) {
        Evidence evidence = requests.get(requestId);
        if (evidence == null || runtime.commands().isEmpty()) {
            return Optional.empty();
        }
        CommandLookup lookup = evidence.timelineParentId == null
                ? runtime.commands().orElseThrow().status(requestId)
                : logicalLookup(evidence);
        return Optional.of(snapshot(evidence, lookup));
    }

    synchronized int effectiveTimelineTransitions() {
        return Math.min(timelineLimits.maximumTransitions(),
                Math.min(limits.queuedInputs(), limits.retainedInjections()));
    }

    synchronized TimelineReservation planTimelineReservation(InputTimelineSpec spec,
            String parentRequestId, CommandDispatch dispatch, long startingControlledTick,
            ExecutionEpochId executionEpochId, List<String> parentEvictions) {
        Set<String> parentEvictionSet = new HashSet<>(parentEvictions);
        validateTimeline(spec, parentRequestId, dispatch, parentEvictionSet);
        long retained = requests.values().stream().filter(evidence ->
                evidence.timelineParentId == null
                        || !parentEvictionSet.contains(evidence.timelineParentId)).count();
        ArrayList<String> ordinaryEvictions = new ArrayList<>();
        for (Map.Entry<String, Evidence> entry : requests.entrySet()) {
            if (retained + spec.transitions().size() <= limits.retainedInjections()) {
                break;
            }
            Evidence evidence = entry.getValue();
            if (evidence.timelineParentId == null
                    && (evidence.state == InputInjectionState.EXECUTED
                            || evidence.state == InputInjectionState.FAILED)) {
                ordinaryEvictions.add(entry.getKey());
                retained--;
            }
        }
        if (retained + spec.transitions().size() > limits.retainedInjections()) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "input result retention limit reached");
        }
        Optional<FrameId> submittedFrameId = runtime.latestFrame().map(FrameSnapshot::frameId);
        ArrayList<Evidence> reservations = new ArrayList<>(spec.transitions().size());
        for (InputTimelineTransition transition : spec.transitions()) {
            long targetTick = Math.addExact(
                    startingControlledTick, (long) transition.timelineTick());
            InputDescriptor descriptor = inputs.get(transition.inputId());
            Evidence evidence = new Evidence(transition.inputId(), transition.transitionId(),
                    transition.parameters(), OptionalLong.of(targetTick), targetTick,
                    executionEpochId, submittedFrameId,
                    descriptor.redactionPolicy() == InputRedactionPolicy.OMIT_PARAMETERS);
            evidence.handler = handlers.get(transition.inputId());
            evidence.timelineParentId = parentRequestId;
            reservations.add(evidence);
        }
        return new TimelineReservation(parentRequestId,
                parentEvictions, ordinaryEvictions, reservations,
                new LinkedHashMap<>(requests), List.copyOf(timelineParentIds),
                outstanding, timelineExecuting);
    }

    synchronized void commitTimelineReservation(TimelineReservation reservation) {
        Set<String> parentEvictions = new HashSet<>(reservation.parentEvictions);
        requests.entrySet().removeIf(entry -> entry.getValue().timelineParentId != null
                && parentEvictions.contains(entry.getValue().timelineParentId));
        for (String requestId : reservation.ordinaryEvictions) {
            requests.remove(requestId);
        }
        for (Evidence evidence : reservation.reservations) {
            requests.put(evidence.requestId, evidence);
        }
        timelineParentIds.removeAll(reservation.parentEvictions);
        timelineParentIds.add(reservation.parentRequestId);
        outstanding += reservation.reservations.size();
        timelineExecuting = true;
    }

    synchronized void rollbackTimelineReservation(TimelineReservation reservation) {
        requests.clear();
        requests.putAll(reservation.previousRequests);
        timelineParentIds.clear();
        timelineParentIds.addAll(reservation.previousParentIds);
        outstanding = reservation.previousOutstanding;
        timelineExecuting = reservation.previousTimelineExecuting;
    }

    private void validateTimeline(InputTimelineSpec spec, String parentRequestId,
            CommandDispatch dispatch, Set<String> parentEvictions) {
        if (determinismExecuting || timelineExecuting
                || outstanding != 0 || !scheduled.isEmpty() || !executedByTick.isEmpty()) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                    "input timeline requires an empty ordinary input queue");
        }
        if (spec.transitions().size() > effectiveTimelineTransitions()) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "input timeline transition count exceeds the effective limit");
        }
        if (retainedAfterParentEvictions(parentRequestId, parentEvictions)
                || retainedParentIdAfterEvictions(parentRequestId, parentEvictions)) {
            throw new IllegalArgumentException(
                    "input timeline parent id collides with input evidence");
        }
        for (InputTimelineTransition transition : spec.transitions()) {
            if (transition.transitionId().equals(parentRequestId)) {
                throw new IllegalArgumentException(
                        "input timeline parent and transition ids must differ");
            }
            InputDescriptor descriptor = inputs.get(transition.inputId());
            Consumer<InputParameters> handler = handlers.get(transition.inputId());
            if (descriptor == null || handler == null) {
                throw new IllegalArgumentException("unknown input timeline input id");
            }
            validate(descriptor, transition.parameters());
            if (retainedAfterParentEvictions(
                            transition.transitionId(), parentEvictions)
                    || retainedParentIdAfterEvictions(
                            transition.transitionId(), parentEvictions)
                    || dispatch.status(transition.transitionId()).kind()
                            != CommandLookup.Kind.UNKNOWN) {
                throw new IllegalArgumentException(
                        "input timeline transition id collides with retained evidence");
            }
        }
    }

    private boolean retainedAfterParentEvictions(
            String requestId, Set<String> parentEvictions) {
        Evidence evidence = requests.get(requestId);
        return evidence != null && (evidence.timelineParentId == null
                || !parentEvictions.contains(evidence.timelineParentId));
    }

    private boolean retainedParentIdAfterEvictions(
            String requestId, Set<String> parentEvictions) {
        return timelineParentIds.contains(requestId) && !parentEvictions.contains(requestId);
    }

    synchronized void stageTimeline(InputTimelineSpec spec, String parentRequestId,
            long startingControlledTick, ExecutionEpochId executionEpochId,
            CommandStatus parentStatus) {
        if (!timelineExecuting) {
            throw new IllegalStateException("input timeline execution is not reserved");
        }
        timelineDeadlineNanos = parentStatus.deadlineNanos();
        for (InputTimelineTransition transition : spec.transitions()) {
            Evidence evidence = requireTimelineEvidence(
                    transition.transitionId(), parentRequestId);
            evidence.targetTick = Math.addExact(
                    startingControlledTick, (long) transition.timelineTick());
            evidence.executionEpochId = executionEpochId;
            evidence.logicalStatus = new CommandStatus(evidence.requestId, CommandState.QUEUED,
                    parentStatus.submittedAtNanos(), parentStatus.deadlineNanos(),
                    Optional.empty(), Optional.empty(), false,
                    Optional.empty(), Optional.empty());
            scheduled.computeIfAbsent(evidence.targetTick, ignored -> new ArrayDeque<>())
                    .addLast(evidence);
            evidence.state = InputInjectionState.SCHEDULED;
            snapshot(evidence, logicalLookup(evidence));
        }
    }

    /**
     * Releases exclusive timeline staging, marking any unattempted child as terminal without
     * running its handler, and always clears the exclusive execution flag.
     */
    synchronized void releaseTimeline(String parentRequestId, String diagnostic) {
        for (Evidence evidence : requests.values()) {
            if (!parentRequestId.equals(evidence.timelineParentId)) {
                continue;
            }
            if (evidence.state == InputInjectionState.QUEUED
                    || evidence.state == InputInjectionState.SCHEDULED) {
                outstanding--;
                evidence.state = InputInjectionState.FAILED;
                evidence.timelineNotExecuted = true;
                evidence.diagnostic = Optional.of(boundedDiagnostic(diagnostic));
            }
        }
        timelineExecuting = false;
    }

    /** Returns the first bounded structured failure of an attempted timeline child, if any. */
    synchronized Optional<ApplicationFailureEvidence> timelineChildFailure(
            String parentRequestId) {
        for (Evidence evidence : requests.values()) {
            if (parentRequestId.equals(evidence.timelineParentId)
                    && evidence.state == InputInjectionState.FAILED
                    && !evidence.timelineNotExecuted) {
                return evidence.applicationFailure;
            }
        }
        return Optional.empty();
    }

    /**
     * Returns terminal injection evidence for an attempted timeline transition, or empty when the
     * transition was never attempted and must be reported as {@code NOT_EXECUTED}.
     */
    synchronized Optional<InputInjection> attemptedTimelineInjection(
            String transitionId, String parentRequestId) {
        Evidence evidence = requireTimelineEvidence(transitionId, parentRequestId);
        if (evidence.state != InputInjectionState.EXECUTED
                && (evidence.state != InputInjectionState.FAILED
                        || evidence.timelineNotExecuted)) {
            return Optional.empty();
        }
        return Optional.of(snapshot(evidence, logicalLookup(evidence)));
    }

    private Evidence requireTimelineEvidence(String transitionId, String parentRequestId) {
        Evidence evidence = requests.get(transitionId);
        if (evidence == null || !parentRequestId.equals(evidence.timelineParentId)) {
            throw new IllegalStateException("input timeline reservation is unavailable");
        }
        return evidence;
    }

    private void beginLogicalExecution(Evidence evidence) {
        if (evidence.logicalStatus == null) {
            return;
        }
        long started = Math.max(
                evidence.logicalStatus.submittedAtNanos(), runtime.monotonicTimeNanos());
        evidence.logicalStatus = new CommandStatus(evidence.requestId, CommandState.EXECUTING,
                evidence.logicalStatus.submittedAtNanos(), evidence.logicalStatus.deadlineNanos(),
                Optional.of(started), Optional.empty(), false,
                Optional.empty(), Optional.empty());
    }

    private void completeLogicalExecution(Evidence evidence, CommandState state) {
        if (evidence.logicalStatus == null) {
            return;
        }
        long started = evidence.logicalStatus.startedAtNanos().orElseThrow();
        long completed = Math.max(started, runtime.monotonicTimeNanos());
        evidence.logicalStatus = new CommandStatus(evidence.requestId, state,
                evidence.logicalStatus.submittedAtNanos(), evidence.logicalStatus.deadlineNanos(),
                Optional.of(started), Optional.of(completed), true,
                evidence.diagnostic, evidence.applicationFailure);
    }

    private static CommandLookup logicalLookup(Evidence evidence) {
        if (evidence.logicalStatus == null) {
            throw new IllegalStateException("logical input command status is unavailable");
        }
        return CommandLookup.found(evidence.logicalStatus);
    }

    private void reconcileTerminalDispatch(Evidence evidence, CommandLookup command) {
        if (evidence.state != InputInjectionState.QUEUED || command.status().isEmpty()) {
            return;
        }
        CommandStatus status = command.status().orElseThrow();
        boolean endedBeforeExecution = status.state() == CommandState.REJECTED
                || status.state() == CommandState.CANCELLED
                || status.state() == CommandState.TIMED_OUT && status.startedAtNanos().isEmpty();
        if (endedBeforeExecution) {
            outstanding--;
            evidence.state = InputInjectionState.FAILED;
            evidence.diagnostic = Optional.of(boundedDiagnostic(
                    status.diagnostic().orElse("command ended before input execution")));
        }
    }

    private void requireTargetingState() {
        if (!runtime.controls().available()) {
            throw new IllegalStateException("input targeting requires simulation control");
        }
        if (!runtime.controls().paused()) {
            throw new IllegalStateException("input targeting requires paused simulation");
        }
    }

    private void validateTarget(long currentTick, long targetTick) {
        if (targetTick <= currentTick) {
            throw new IllegalArgumentException("input target tick is in the past");
        }
        if (targetTick - currentTick > limits.futureTicks()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "input target tick is too distant");
        }
    }

    private void validate(InputDescriptor descriptor, RuntimeValue.ObjectValue parameters) {
        RuntimeValueValidator.validate(parameters, runtime.configuration().limits());
        Map<String, ActionParameter> schema = descriptor.parameters().stream().collect(
                java.util.stream.Collectors.toMap(ActionParameter::name, value -> value));
        for (RuntimeValue.Field field : parameters.fields()) {
            ActionParameter expected = schema.get(field.name());
            if (expected == null) {
                throw new IllegalArgumentException("unknown input parameter");
            }
            if (!matches(expected.type(), field.value())) {
                throw new IllegalArgumentException("input parameter has the wrong type");
            }
            if (field.value() instanceof RuntimeValue.StringValue text
                    && text.value().length() > limits.stringLength()) {
                throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                        "input string parameter exceeds the configured limit");
            }
        }
        descriptor.parameters().stream().filter(ActionParameter::required)
                .filter(parameter -> parameters.fields().stream()
                        .noneMatch(field -> field.name().equals(parameter.name())))
                .findFirst().ifPresent(parameter -> {
                    throw new IllegalArgumentException("required input parameter is absent");
                });
    }

    private static boolean matches(ActionParameterType type, RuntimeValue value) {
        return switch (type) {
            case BOOLEAN -> value instanceof RuntimeValue.BooleanValue;
            case INTEGER -> value instanceof RuntimeValue.IntegerValue;
            case DECIMAL -> value instanceof RuntimeValue.DecimalValue;
            case STRING -> value instanceof RuntimeValue.StringValue;
            case ENUM -> value instanceof RuntimeValue.EnumValue;
            case ENTITY_ID -> value instanceof RuntimeValue.StringValue text && validEntityId(text);
        };
    }

    private static boolean validEntityId(RuntimeValue.StringValue value) {
        try {
            EntityId.of(value.value());
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private synchronized void makeRoom() {
        makeRoom(1);
    }

    private synchronized void makeRoom(int requestedCapacity) {
        while ((long) requests.size() + requestedCapacity > limits.retainedInjections()) {
            String removable = requests.entrySet().stream()
                    .filter(entry -> entry.getValue().timelineParentId == null)
                    .filter(entry -> entry.getValue().state == InputInjectionState.EXECUTED
                            || entry.getValue().state == InputInjectionState.FAILED)
                    .map(Map.Entry::getKey).findFirst().orElseThrow(() ->
                            new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                                    "input result retention limit reached"));
            requests.remove(removable);
        }
    }

    private void recordFailure(Evidence evidence, String category, Throwable failure) {
        Optional<String> correlationId = runtime.commands().orElseThrow()
                .correlationId(evidence.requestId);
        ApplicationFailureEvidence failureEvidence = correlationId.isPresent()
                ? runtime.diagnostics().describe(category, failure, correlationId.orElseThrow())
                : runtime.diagnostics().describe(category, failure);
        evidence.diagnostic = Optional.of(failureEvidence.legacyEnvelope());
        evidence.applicationFailure = Optional.of(failureEvidence);
    }

    private String boundedDiagnostic(String value) {
        return value.length() <= limits.stringLength()
                ? value : value.substring(0, limits.stringLength());
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

    private static final class Evidence {
        private final String inputId;
        private final String requestId;
        private final RuntimeValue.ObjectValue parameters;
        private final OptionalLong requestedTargetTick;
        private long targetTick;
        private ExecutionEpochId executionEpochId;
        private final Optional<FrameId> submittedFrameId;
        private final boolean parametersRedacted;
        private Consumer<InputParameters> handler;
        private InputInjectionState state = InputInjectionState.QUEUED;
        private OptionalLong actualTick = OptionalLong.empty();
        private Optional<FrameId> resultingFrameId = Optional.empty();
        private Optional<RuntimeValue.ObjectValue> recordedParameters = Optional.empty();
        private Optional<String> diagnostic = Optional.empty();
        private Optional<ApplicationFailureEvidence> applicationFailure = Optional.empty();
        private String timelineParentId;
        private CommandStatus logicalStatus;
        private boolean timelineNotExecuted;

        Evidence(String inputId, String requestId, RuntimeValue.ObjectValue parameters,
                OptionalLong requestedTargetTick, long targetTick,
                ExecutionEpochId executionEpochId, Optional<FrameId> submittedFrameId,
                boolean parametersRedacted) {
            this.inputId = inputId;
            this.requestId = requestId;
            this.parameters = parameters;
            this.requestedTargetTick = requestedTargetTick;
            this.targetTick = targetTick;
            this.executionEpochId = executionEpochId;
            this.submittedFrameId = submittedFrameId;
            this.parametersRedacted = parametersRedacted;
        }
    }

    static final class TimelineReservation {
        private final String parentRequestId;
        private final List<String> parentEvictions;
        private final List<String> ordinaryEvictions;
        private final List<Evidence> reservations;
        private final LinkedHashMap<String, Evidence> previousRequests;
        private final List<String> previousParentIds;
        private final int previousOutstanding;
        private final boolean previousTimelineExecuting;

        private TimelineReservation(String parentRequestId, List<String> parentEvictions,
                List<String> ordinaryEvictions, List<Evidence> reservations,
                LinkedHashMap<String, Evidence> previousRequests,
                List<String> previousParentIds, int previousOutstanding,
                boolean previousTimelineExecuting) {
            this.parentRequestId = parentRequestId;
            this.parentEvictions = List.copyOf(parentEvictions);
            this.ordinaryEvictions = List.copyOf(ordinaryEvictions);
            this.reservations = List.copyOf(reservations);
            this.previousRequests = previousRequests;
            this.previousParentIds = previousParentIds;
            this.previousOutstanding = previousOutstanding;
            this.previousTimelineExecuting = previousTimelineExecuting;
        }
    }
}
