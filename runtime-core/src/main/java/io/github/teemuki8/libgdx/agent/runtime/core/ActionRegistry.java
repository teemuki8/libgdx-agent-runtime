package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Explicit bounded registry and dispatcher for typed semantic actions. */
public final class ActionRegistry {
    private final AgentRuntime runtime;
    private final ActionLimits limits;
    private final LinkedHashMap<String, ActionSpec> actions = new LinkedHashMap<>();
    private final LinkedHashMap<String, RequestEvidence> requests = new LinkedHashMap<>();

    ActionRegistry(AgentRuntime runtime, ActionLimits limits) {
        this.runtime = runtime;
        this.limits = limits;
    }

    public synchronized void register(ActionSpec spec) {
        runtime.requireActionRegistration();
        Objects.requireNonNull(spec, "spec");
        if (actions.containsKey(spec.descriptor().id())) {
            throw new IllegalArgumentException("action id is already registered");
        }
        if (actions.size() >= limits.registeredActions()
                || spec.descriptor().parameters().size() > limits.parametersPerAction()) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "action registration limit reached");
        }
        actions.put(spec.descriptor().id(), spec);
    }

    public synchronized List<ActionDescriptor> list() {
        return actions.values().stream().map(ActionSpec::descriptor).toList();
    }

    public ActionInvocation invoke(String actionId, String requestId,
            RuntimeValue.ObjectValue parameters, Optional<String> correlationId,
            Duration timeout) {
        Objects.requireNonNull(parameters, "parameters");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        correlationId.ifPresent(value -> IdentifierSupport.validate(value, "correlation id"));
        CommandDispatch dispatch = runtime.commands().orElseThrow(() ->
                new IllegalStateException("semantic actions require application command dispatch"));
        requireValidTimeout(timeout, dispatch.limits().maximumTimeoutNanos());
        ActionSpec spec;
        RequestEvidence evidence;
        boolean existing;
        synchronized (this) {
            spec = actions.get(actionId);
            if (spec == null) {
                throw new IllegalArgumentException("unknown action id");
            }
            validate(spec.descriptor(), parameters);
            evidence = requests.get(requestId);
            existing = evidence != null;
            if (evidence != null && (!evidence.actionId.equals(actionId)
                    || !evidence.parameters.equals(parameters)
                    || !evidence.correlationId.equals(correlationId))) {
                throw new IllegalArgumentException("request id is bound to a different invocation");
            }
            if (evidence == null && dispatch.status(requestId).kind() != CommandLookup.Kind.UNKNOWN) {
                throw new IllegalArgumentException("request correlation evidence is no longer retained");
            }
            if (evidence == null) {
                evidence = new RequestEvidence(actionId, parameters, correlationId,
                        runtime.latestFrame().map(FrameSnapshot::frameId));
                requests.put(requestId, evidence);
                trim();
            }
        }
        if (existing) {
            return new ActionInvocation(actionId, requestId, dispatch.status(requestId),
                    evidence.submittedFrame, evidence.completedFrame, correlationId);
        }
        RequestEvidence retained = evidence;
        ActionParameters validated = new ActionParameters(parameters);
        CommandLookup lookup = dispatch.submit(requestId, timeout, () -> {
            spec.handler().accept(validated);
            retained.completedFrame = runtime.latestFrame().map(FrameSnapshot::frameId);
        });
        return new ActionInvocation(actionId, requestId, lookup, evidence.submittedFrame,
                evidence.completedFrame, correlationId);
    }

    public ActionLimits limits() {
        return limits;
    }

    private void validate(ActionDescriptor descriptor, RuntimeValue.ObjectValue parameters) {
        Map<String, ActionParameter> schema = descriptor.parameters().stream().collect(
                java.util.stream.Collectors.toMap(ActionParameter::name, value -> value));
        for (RuntimeValue.Field field : parameters.fields()) {
            ActionParameter expected = schema.get(field.name());
            if (expected == null) {
                throw new IllegalArgumentException("unknown action parameter");
            }
            if (!matches(expected.type(), field.value())) {
                throw new IllegalArgumentException("action parameter has the wrong type");
            }
            if (field.value() instanceof RuntimeValue.StringValue text
                    && text.value().length() > limits.stringLength()) {
                throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                        "action string parameter exceeds the configured limit");
            }
        }
        descriptor.parameters().stream().filter(ActionParameter::required)
                .filter(parameter -> parameters.fields().stream()
                        .noneMatch(field -> field.name().equals(parameter.name())))
                .findFirst().ifPresent(parameter -> {
                    throw new IllegalArgumentException("required action parameter is absent");
                });
    }

    private static boolean matches(ActionParameterType type, RuntimeValue value) {
        return switch (type) {
            case BOOLEAN -> value instanceof RuntimeValue.BooleanValue;
            case INTEGER -> value instanceof RuntimeValue.IntegerValue;
            case DECIMAL -> value instanceof RuntimeValue.DecimalValue;
            case STRING -> value instanceof RuntimeValue.StringValue;
            case ENUM -> value instanceof RuntimeValue.EnumValue;
            case ENTITY_ID -> value instanceof RuntimeValue.StringValue text
                    && validEntityId(text.value());
        };
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

    private static boolean validEntityId(String value) {
        try {
            EntityId.of(value);
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private synchronized void trim() {
        while (requests.size() > limits.retainedInvocations()) {
            requests.remove(requests.keySet().iterator().next());
        }
    }

    private static final class RequestEvidence {
        private final String actionId;
        private final RuntimeValue.ObjectValue parameters;
        private final Optional<String> correlationId;
        private final Optional<FrameId> submittedFrame;
        private volatile Optional<FrameId> completedFrame = Optional.empty();

        RequestEvidence(String actionId, RuntimeValue.ObjectValue parameters,
                Optional<String> correlationId, Optional<FrameId> submittedFrame) {
            this.actionId = actionId;
            this.parameters = parameters;
            this.correlationId = correlationId;
            this.submittedFrame = submittedFrame;
        }
    }
}
