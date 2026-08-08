package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/** Explicit bounded registry and deterministic controlled-tick scheduler for input facts. */
public final class InputRegistry {
    private final Object submissionLock = new Object();
    private final AgentRuntime runtime;
    private final InputLimits limits;
    private final LinkedHashMap<String, InputSpec> inputs = new LinkedHashMap<>();
    private final LinkedHashMap<String, Evidence> requests = new LinkedHashMap<>();
    private final TreeMap<Long, ArrayDeque<Evidence>> scheduled = new TreeMap<>();
    private final Map<Long, List<Evidence>> executedByTick = new LinkedHashMap<>();
    private int outstanding;

    InputRegistry(AgentRuntime runtime, InputLimits limits) {
        this.runtime = runtime;
        this.limits = limits;
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
        inputs.put(spec.descriptor().id(), spec);
    }

    /** Returns registered input descriptors in stable registration order. */
    public synchronized List<InputDescriptor> list() {
        return inputs.values().stream().map(InputSpec::descriptor).toList();
    }

    /** Returns configured hard input bounds. */
    public InputLimits limits() {
        return limits;
    }

    /**
     * Submits, schedules, or polls one at-most-once input for the next or an explicit controlled
     * tick.
     */
    public InputInjection inject(String inputId, String requestId,
            RuntimeValue.ObjectValue parameters, OptionalLong requestedTargetTick,
            Duration timeout) {
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
        InputSpec spec;
        Evidence evidence;
        boolean existing;
        synchronized (this) {
            spec = inputs.get(inputId);
            if (spec == null) {
                throw new IllegalArgumentException("unknown input id");
            }
            validate(spec.descriptor(), parameters);
            evidence = requests.get(requestId);
            existing = evidence != null;
            if (evidence != null && (!evidence.inputId.equals(inputId)
                    || !evidence.parameters.equals(parameters)
                    || !evidence.requestedTargetTick.equals(requestedTargetTick))) {
                throw new IllegalArgumentException("request id is bound to a different input");
            }
            if (evidence == null) {
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
                        spec.descriptor().redactionPolicy()
                                == InputRedactionPolicy.OMIT_PARAMETERS);
                requests.put(requestId, evidence);
                outstanding++;
            }
        }
        if (existing) {
            return snapshot(evidence, dispatch.status(requestId));
        }
        Evidence retained = evidence;
        retained.spec = spec;
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
            if (!evidence.executionEpochId.equals(epochId)) {
                evidence.state = InputInjectionState.FAILED;
                evidence.diagnostic = Optional.of("execution epoch changed before target tick");
                continue;
            }
            evidence.recordedParameters = evidence.parametersRedacted
                    ? Optional.empty() : Optional.of(evidence.parameters);
            try {
                evidence.spec.handler().accept(new InputParameters(evidence.parameters));
                evidence.state = InputInjectionState.EXECUTED;
            } catch (RuntimeException failure) {
                evidence.state = InputInjectionState.FAILED;
                recordFailure(evidence, "input.execution", failure);
                if (firstRuntimeFailure == null) {
                    firstRuntimeFailure = failure;
                }
            } catch (Error failure) {
                evidence.state = InputInjectionState.FAILED;
                recordFailure(evidence, "input.execution", failure);
                if (firstError == null) {
                    firstError = failure;
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

    synchronized void completeTick(long tick, FrameId resultingFrameId) {
        List<Evidence> executed = executedByTick.remove(tick);
        if (executed != null) {
            executed.forEach(evidence -> evidence.resultingFrameId = Optional.of(resultingFrameId));
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
        return injection;
    }

    synchronized Optional<InputInjection> recording(String requestId) {
        Evidence evidence = requests.get(requestId);
        if (evidence == null || runtime.commands().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(snapshot(
                evidence, runtime.commands().orElseThrow().status(requestId)));
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
        while (requests.size() >= limits.retainedInjections()) {
            String removable = requests.entrySet().stream()
                    .filter(entry -> entry.getValue().state == InputInjectionState.EXECUTED
                            || entry.getValue().state == InputInjectionState.FAILED)
                    .map(Map.Entry::getKey).findFirst().orElseThrow(() ->
                            new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                                    "input result retention limit reached"));
            requests.remove(removable);
        }
    }

    private void recordFailure(Evidence evidence, String category, Throwable failure) {
        ApplicationFailureEvidence failureEvidence =
                runtime.diagnostics().describe(category, failure);
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
        private final long targetTick;
        private final ExecutionEpochId executionEpochId;
        private final Optional<FrameId> submittedFrameId;
        private final boolean parametersRedacted;
        private InputSpec spec;
        private InputInjectionState state = InputInjectionState.QUEUED;
        private OptionalLong actualTick = OptionalLong.empty();
        private Optional<FrameId> resultingFrameId = Optional.empty();
        private Optional<RuntimeValue.ObjectValue> recordedParameters = Optional.empty();
        private Optional<String> diagnostic = Optional.empty();
        private Optional<ApplicationFailureEvidence> applicationFailure = Optional.empty();

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
}
