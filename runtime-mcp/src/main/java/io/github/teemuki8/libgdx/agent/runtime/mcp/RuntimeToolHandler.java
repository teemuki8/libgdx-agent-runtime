package io.github.teemuki8.libgdx.agent.runtime.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolJson;
import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolVersion;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeCommand;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRequest;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeResponse;
import io.github.teemuki8.libgdx.agent.runtime.core.ActionDescriptor;
import io.github.teemuki8.libgdx.agent.runtime.core.ActionParameter;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionType;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.EventType;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameId;
import io.github.teemuki8.libgdx.agent.runtime.core.InputDescriptor;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeAssertion;
import io.github.teemuki8.libgdx.agent.runtime.core.SnapshotComparisonScope;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Maps each MCP call to exactly one typed protocol request. */
public final class RuntimeToolHandler implements AutoCloseable {
    private static final long DEFAULT_FROM = 0;
    private static final long DEFAULT_TO = Long.MAX_VALUE;
    private static final int DEFAULT_LIMIT = 100;
    private static final ObjectMapper MAPPER = ProtocolJson.mapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    private final RuntimeProtocolService protocol;
    private final RuntimeToolCatalog catalog;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Scheduler scheduler = Schedulers.fromExecutorService(executor);
    private final AtomicLong sequence = new AtomicLong();

    /** Creates a handler over one protocol service. */
    public RuntimeToolHandler(RuntimeProtocolService protocol) {
        this(protocol, new RuntimeToolCatalog(
                protocol.toolNames(), protocol.actionCatalog(), protocol.inputCatalog()));
    }

    RuntimeToolHandler(RuntimeProtocolService protocol, RuntimeToolCatalog catalog) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** Validates and invokes one approved tool asynchronously. */
    public Mono<McpSchema.CallToolResult> handle(McpSchema.CallToolRequest call) {
        Objects.requireNonNull(call, "call");
        return Mono.fromCallable(() -> handleSynchronously(call)).subscribeOn(scheduler);
    }

    private McpSchema.CallToolResult handleSynchronously(McpSchema.CallToolRequest call) {
        Map<String, Object> arguments = call.arguments() == null ? Map.of() : call.arguments();
        McpSchema.Tool tool;
        try {
            tool = catalog.tool(call.name());
        } catch (IllegalArgumentException failure) {
            return error("INVALID_QUERY", "unknown runtime tool");
        }
        var validation =
                McpJsonDefaults.getSchemaValidator().validate(tool.inputSchema(), arguments);
        if (!validation.valid()) {
            return error("INVALID_QUERY", "arguments do not match the closed tool schema");
        }
        try {
            RuntimeRequest request = toRequest(call.name(), arguments);
            RuntimeResponse response = protocol.execute(request);
            if (response instanceof RuntimeResponse.Failure failure) {
                return error(failure.error().code().name(), failure.error().message());
            }
            RuntimeResponse.Success success = (RuntimeResponse.Success) response;
            ProtocolJson.encode(success);
            LinkedHashMap<String, Object> content =
                    MAPPER.convertValue(success.result(), MAP_TYPE);
            return McpSchema.CallToolResult.builder()
                    .structuredContent(Map.copyOf(content))
                    .addTextContent(MAPPER.writeValueAsString(content))
                    .isError(false)
                    .build();
        } catch (ProtocolJson.ProtocolJsonException failure) {
            return error(failure.code().name(), failure.getMessage());
        } catch (RuntimeException failure) {
            return error("INVALID_QUERY", "arguments could not be decoded");
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            return error("INTERNAL_ERROR", "result could not be encoded");
        }
    }

    private RuntimeRequest toRequest(String toolName, Map<String, Object> arguments) {
        String sessionId = string(arguments, "sessionId");
        long from = number(arguments, "fromFrame", DEFAULT_FROM);
        long to = number(arguments, "toFrame", DEFAULT_TO);
        int limit = Math.toIntExact(number(arguments, "limit", DEFAULT_LIMIT));
        RuntimeCommand command = switch (toolName) {
            case "runtime_sessions" -> new RuntimeCommand.Sessions();
            case "runtime_capabilities" -> new RuntimeCommand.Capabilities();
            case "runtime_frames" -> new RuntimeCommand.Frames(from, to, limit);
            case "runtime_snapshot" -> new RuntimeCommand.Snapshot(
                    optionalLong(arguments, "frameId"),
                    string(arguments, "entityId"),
                    bool(arguments, "entityIdPrefix"),
                    string(arguments, "entityType"),
                    bool(arguments, "entityTypePrefix"),
                    limit);
            case "runtime_entity" -> new RuntimeCommand.Entity(
                    string(arguments, "entityId"), from, to, limit);
            case "runtime_changes" -> new RuntimeCommand.Changes(
                    from, to, string(arguments, "entityId"),
                    string(arguments, "entityType"), string(arguments, "property"), limit);
            case "runtime_events" -> new RuntimeCommand.Events(
                    from, to, string(arguments, "eventType"),
                    bool(arguments, "eventTypePrefix"), string(arguments, "subject"),
                    string(arguments, "source"), limit);
            case "runtime_decisions" -> new RuntimeCommand.Decisions(
                    from, to, string(arguments, "decisionType"),
                    string(arguments, "actor"), string(arguments, "chosenCandidate"),
                    string(arguments, "reasonCode"), limit);
            case "runtime_command_status" -> new RuntimeCommand.CommandStatus(
                    string(arguments, "commandRequestId"));
            case "runtime_command_cancel" -> new RuntimeCommand.CommandCancel(
                    string(arguments, "commandRequestId"));
            case "runtime_epoch_frames" -> new RuntimeCommand.EpochFrames(
                    number(arguments, "executionEpochId", -1), limit);
            case "runtime_scenarios" -> new RuntimeCommand.Scenarios();
            case "runtime_reset" -> new RuntimeCommand.Reset(
                    string(arguments, "scenarioId"), string(arguments, "resetRequestId"),
                    number(arguments, "timeoutNanos", -1));
            case "runtime_attributed_changes" -> new RuntimeCommand.AttributedChanges(
                    from, to, string(arguments, "entityId"), string(arguments, "entityType"),
                    string(arguments, "property"), string(arguments, "sourceSubsystem"),
                    string(arguments, "correlationId"), limit);
            case "runtime_attributed_events" -> new RuntimeCommand.AttributedEvents(
                    from, to, string(arguments, "eventType"), bool(arguments, "eventTypePrefix"),
                    string(arguments, "subject"), string(arguments, "source"),
                    string(arguments, "sourceSubsystem"), string(arguments, "correlationId"),
                    limit);
            case "runtime_attributed_decisions" -> new RuntimeCommand.AttributedDecisions(
                    from, to, string(arguments, "decisionType"), string(arguments, "actor"),
                    string(arguments, "chosenCandidate"), string(arguments, "reasonCode"),
                    string(arguments, "sourceSubsystem"), string(arguments, "correlationId"),
                    limit);
            case "runtime_actions" -> new RuntimeCommand.Actions();
            case "runtime_action" -> new RuntimeCommand.Action(
                    string(arguments, "action"), string(arguments, "actionRequestId"),
                    actionParameters(string(arguments, "action"), arguments.get("parameters")),
                    string(arguments, "correlationId"), number(arguments, "timeoutNanos", -1));
            case "runtime_assert" -> new RuntimeCommand.Assert(
                    assertion(arguments.get("assertion")), from, to,
                    number(arguments, "executionEpochId", -1),
                    Math.toIntExact(number(arguments, "evidenceLimit", -1)));
            case "runtime_control" -> control(arguments);
            case "runtime_advance" -> new RuntimeCommand.Advance(
                    string(arguments, "controlRequestId"),
                    Math.toIntExact(number(arguments, "ticks", -1)),
                    number(arguments, "deltaNanos", -1),
                    number(arguments, "timeoutNanos", -1));
            case "runtime_wait" -> new RuntimeCommand.Wait(
                    string(arguments, "controlRequestId"), string(arguments, "conditionId"),
                    arguments.containsKey("assertion")
                            ? assertion(arguments.get("assertion")) : null,
                    Math.toIntExact(number(arguments, "maximumTicks", -1)),
                    number(arguments, "deltaNanos", -1),
                    Math.toIntExact(number(arguments, "evidenceLimit", -1)),
                    number(arguments, "timeoutNanos", -1));
            case "runtime_inputs" -> new RuntimeCommand.Inputs();
            case "runtime_input" -> new RuntimeCommand.Input(
                    string(arguments, "input"), string(arguments, "inputRequestId"),
                    inputParameters(string(arguments, "input"), arguments.get("parameters")),
                    optionalLong(arguments, "targetTick"),
                    number(arguments, "timeoutNanos", -1));
            default -> throw new IllegalArgumentException("unknown runtime tool");
        };
        ProtocolVersion version = switch (toolName) {
            case "runtime_capabilities" -> new ProtocolVersion(
                    1, Math.toIntExact(number(arguments, "protocolMinor", 0)));
            case "runtime_command_status", "runtime_command_cancel" -> ProtocolVersion.V1_2;
            case "runtime_epoch_frames" -> ProtocolVersion.V1_3;
            case "runtime_scenarios", "runtime_reset" -> ProtocolVersion.V1_4;
            case "runtime_attributed_changes", "runtime_attributed_events",
                    "runtime_attributed_decisions" -> ProtocolVersion.V1_5;
            case "runtime_actions", "runtime_action" -> ProtocolVersion.V1_6;
            case "runtime_assert" -> ProtocolVersion.V1_7;
            case "runtime_control", "runtime_advance", "runtime_wait" -> ProtocolVersion.V1_8;
            case "runtime_inputs", "runtime_input" -> ProtocolVersion.V1_9;
            default -> ProtocolVersion.V1;
        };
        return new RuntimeRequest(version,
                "mcp-" + Long.toUnsignedString(sequence.incrementAndGet()), sessionId, command);
    }

    private static RuntimeCommand.Control control(Map<String, Object> arguments) {
        RuntimeCommand.ControlAction action = RuntimeCommand.ControlAction.valueOf(
                string(arguments, "action"));
        return action == RuntimeCommand.ControlAction.STATUS
                ? new RuntimeCommand.Control(action, null, 0)
                : new RuntimeCommand.Control(action, string(arguments, "controlRequestId"),
                        number(arguments, "timeoutNanos", -1));
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : (String) value;
    }

    private static long number(Map<String, Object> values, String key, long fallback) {
        Object value = values.get(key);
        return value == null ? fallback : ((Number) value).longValue();
    }

    private static Long optionalLong(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : ((Number) value).longValue();
    }

    private static boolean bool(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value != null && (Boolean) value;
    }

    private RuntimeValue.ObjectValue actionParameters(String actionId, Object raw) {
        if (!(raw instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("action parameters must be an object");
        }
        ActionDescriptor descriptor = catalog.action(actionId);
        Map<String, ActionParameter> schema = descriptor.parameters().stream().collect(
                java.util.stream.Collectors.toMap(ActionParameter::name, value -> value));
        java.util.ArrayList<RuntimeValue.Field> fields = new java.util.ArrayList<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new IllegalArgumentException("action parameter name must be a string");
            }
            ActionParameter parameter = schema.get(name);
            if (parameter == null) {
                throw new IllegalArgumentException("unknown action parameter");
            }
            fields.add(RuntimeValues.field(name, actionValue(parameter, entry.getValue())));
        }
        return new RuntimeValue.ObjectValue(fields);
    }

    private static RuntimeValue actionValue(ActionParameter parameter, Object raw) {
        return switch (parameter.type()) {
            case BOOLEAN -> raw instanceof Boolean value ? RuntimeValues.bool(value) : wrongType();
            case INTEGER -> raw instanceof Byte || raw instanceof Short || raw instanceof Integer
                    || raw instanceof Long ? RuntimeValues.integer(((Number) raw).longValue())
                    : wrongType();
            case DECIMAL -> raw instanceof Number value
                    ? RuntimeValues.decimal(value.toString()) : wrongType();
            case STRING, ENTITY_ID -> raw instanceof String value
                    ? RuntimeValues.string(value) : wrongType();
            case ENUM -> raw instanceof String value
                    ? RuntimeValues.enumValue(value) : wrongType();
        };
    }

    private RuntimeValue.ObjectValue inputParameters(String inputId, Object raw) {
        if (!(raw instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("input parameters must be an object");
        }
        InputDescriptor descriptor = catalog.input(inputId);
        Map<String, ActionParameter> schema = descriptor.parameters().stream().collect(
                java.util.stream.Collectors.toMap(ActionParameter::name, value -> value));
        java.util.ArrayList<RuntimeValue.Field> fields = new java.util.ArrayList<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new IllegalArgumentException("input parameter name must be a string");
            }
            ActionParameter parameter = schema.get(name);
            if (parameter == null) {
                throw new IllegalArgumentException("unknown input parameter");
            }
            fields.add(RuntimeValues.field(name, actionValue(parameter, entry.getValue())));
        }
        return new RuntimeValue.ObjectValue(fields);
    }


    private static RuntimeAssertion assertion(Object raw) {
        Map<String, Object> values = stringMap(raw, "assertion");
        String type = string(values, "assertionType");
        return switch (type) {
            case "entityExists" -> new RuntimeAssertion.EntityExists(
                    EntityId.of(string(values, "entityId")));
            case "entityDoesNotExist" -> new RuntimeAssertion.EntityDoesNotExist(
                    EntityId.of(string(values, "entityId")));
            case "propertyEquals" -> new RuntimeAssertion.PropertyEquals(
                    EntityId.of(string(values, "entityId")), string(values, "property"),
                    runtimeValue(values.get("expected"), 0));
            case "propertyChangesFrom" -> new RuntimeAssertion.PropertyChangesFrom(
                    EntityId.of(string(values, "entityId")), string(values, "property"),
                    runtimeValue(values.get("from"), 0));
            case "propertyRemainsWithinRange" ->
                    new RuntimeAssertion.PropertyRemainsWithinRange(
                            EntityId.of(string(values, "entityId")), string(values, "property"),
                            decimal(values, "minimum"), decimal(values, "maximum"));
            case "eventOccurs" -> new RuntimeAssertion.EventOccurs(
                    EventType.of(string(values, "eventType")));
            case "eventDoesNotOccur" -> new RuntimeAssertion.EventDoesNotOccur(
                    EventType.of(string(values, "eventType")));
            case "eventOccursExactly" -> new RuntimeAssertion.EventOccursExactly(
                    EventType.of(string(values, "eventType")),
                    Math.toIntExact(number(values, "count", -1)));
            case "decisionSelected" -> new RuntimeAssertion.DecisionSelected(
                    DecisionType.of(string(values, "decisionType")),
                    EntityId.of(string(values, "candidate")));
            case "decisionRejected" -> new RuntimeAssertion.DecisionRejected(
                    DecisionType.of(string(values, "decisionType")),
                    EntityId.of(string(values, "candidate")));
            case "entityCountStaysBelow" -> new RuntimeAssertion.EntityCountStaysBelow(
                    Optional.ofNullable(string(values, "entityType")).map(EntityType::of),
                    Math.toIntExact(number(values, "limit", -1)));
            case "snapshotsEquivalent" -> new RuntimeAssertion.SnapshotsEquivalent(
                    new FrameId(number(values, "leftFrameId", -1)),
                    new FrameId(number(values, "rightFrameId", -1)),
                    comparisonScope(values.get("comparisonScope")));
            default -> throw new IllegalArgumentException("unknown assertion type");
        };
    }

    private static SnapshotComparisonScope comparisonScope(Object raw) {
        Map<String, Object> values = stringMap(raw, "comparisonScope");
        return new SnapshotComparisonScope(
                strings(values.get("entityIds")).stream().map(EntityId::of).toList(),
                strings(values.get("properties")), strings(values.get("excludedProperties")),
                bool(values, "includeEvents"), bool(values, "includeDecisions"));
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> values)) {
            throw new IllegalArgumentException("assertion string list is invalid");
        }
        return values.stream().map(value -> {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("assertion list value is not a string");
            }
            return text;
        }).toList();
    }

    private static BigDecimal decimal(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("assertion decimal is invalid");
        }
        return new BigDecimal(number.toString());
    }

    private static Map<String, Object> stringMap(Object raw, String name) {
        if (!(raw instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException(name + " field name must be a string");
            }
            result.put(text, value);
        });
        return Map.copyOf(result);
    }

    private static RuntimeValue runtimeValue(Object raw, int depth) {
        if (depth > ProtocolJson.MAX_NESTING_DEPTH) {
            throw new IllegalArgumentException("runtime value nesting is too deep");
        }
        if (raw == null) {
            return RuntimeValues.nullValue();
        }
        if (raw instanceof Boolean value) {
            return RuntimeValues.bool(value);
        }
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer
                || raw instanceof Long) {
            return RuntimeValues.integer(((Number) raw).longValue());
        }
        if (raw instanceof Number value) {
            return RuntimeValues.decimal(value.toString());
        }
        if (raw instanceof String value) {
            return RuntimeValues.string(value);
        }
        if (raw instanceof List<?> values) {
            if (values.size() > ProtocolJson.MAX_RESULT_ITEMS) {
                throw new IllegalArgumentException("runtime value list is too large");
            }
            return RuntimeValues.list(values.stream()
                    .map(value -> runtimeValue(value, depth + 1)).toList());
        }
        if (raw instanceof Map<?, ?> values) {
            if (values.size() > ProtocolJson.MAX_RESULT_ITEMS) {
                throw new IllegalArgumentException("runtime value object is too large");
            }
            java.util.ArrayList<RuntimeValue.Field> fields = new java.util.ArrayList<>();
            values.forEach((key, value) -> {
                if (!(key instanceof String name)) {
                    throw new IllegalArgumentException("runtime value field name is not a string");
                }
                fields.add(RuntimeValues.field(name, runtimeValue(value, depth + 1)));
            });
            return new RuntimeValue.ObjectValue(fields);
        }
        throw new IllegalArgumentException("unsupported runtime value");
    }

    private static RuntimeValue wrongType() {
        throw new IllegalArgumentException("action parameter has the wrong type");
    }

    private static McpSchema.CallToolResult error(String code, String message) {
        Map<String, Object> content =
                Map.of("kind", "error", "code", code, "message", message);
        return McpSchema.CallToolResult.builder()
                .structuredContent(content)
                .addTextContent(code + ": " + message)
                .isError(true)
                .build();
    }

    /** Stops owned request dispatch. */
    @Override public void close() {
        scheduler.dispose();
        executor.shutdownNow();
    }
}
