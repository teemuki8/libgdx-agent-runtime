package io.github.teemuki8.libgdx.agent.runtime.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.teemuki8.libgdx.agent.runtime.core.ActionDescriptor;
import io.github.teemuki8.libgdx.agent.runtime.core.ActionParameter;
import io.github.teemuki8.libgdx.agent.runtime.core.ActionParameterType;
import io.github.teemuki8.libgdx.agent.runtime.core.AssertionScope;

/** Immutable catalog of the closed base tools and registered optional tools. */
public final class RuntimeToolCatalog {
    private static final int MAX_IDENTIFIER = 256;
    private static final int MAX_RESULTS = 1_000;
    private final List<McpSchema.Tool> tools;
    private final Map<String, McpSchema.Tool> byName;
    private final Map<String, ActionDescriptor> actions;

    /** Builds the fixed catalog. */
    public RuntimeToolCatalog() {
        this(Set.copyOf(io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService
                .BASE_TOOLS), List.of());
    }

    /** Builds a server-start catalog from the protocol's deterministic supported-tool union. */
    public RuntimeToolCatalog(java.util.Collection<String> supportedTools) {
        this(supportedTools, List.of());
    }

    /** Builds a server-start catalog including explicit closed action schemas. */
    public RuntimeToolCatalog(java.util.Collection<String> supportedTools,
            java.util.Collection<ActionDescriptor> actionDescriptors) {
        Set<String> supported = Set.copyOf(supportedTools);
        LinkedHashMap<String, ActionDescriptor> actionIndex = new LinkedHashMap<>();
        actionDescriptors.forEach(descriptor -> actionIndex.merge(descriptor.id(), descriptor,
                (first, second) -> first.equals(second) ? first : first));
        actions = Map.copyOf(actionIndex);
        ArrayList<McpSchema.Tool> selected = new ArrayList<>(List.of(
                tool("runtime_sessions",
                        "List published runtime sessions; no arguments",
                        object(Map.of(), List.of())),
                tool("runtime_capabilities",
                        "Report capabilities; protocolMinor defaults to frozen V1.0",
                        sessionInput(Map.of("protocolMinor", integer(0, 8)), List.of())),
                tool("runtime_frames",
                        "List frame summaries; fromFrame defaults to 0, toFrame to max, limit to 100",
                        queryInput(Map.of(), List.of())),
                tool("runtime_snapshot",
                        "Read latest or exact frame; entity filters are exact unless prefix is true",
                        queryInput(Map.of(
                                "frameId", integer(0, Long.MAX_VALUE),
                                "entityId", string(),
                                "entityIdPrefix", bool(),
                                "entityType", string(),
                                "entityTypePrefix", bool()), List.of())),
                tool("runtime_entity",
                        "Read latest state and history; frame range defaults to all, limit to 100",
                        queryInput(Map.of("entityId", string()), List.of("entityId"))),
                tool("runtime_changes",
                        "Query changes by exact entity ID, type, and property",
                        queryInput(Map.of(
                                "entityId", string(),
                                "entityType", string(),
                                "property", string()), List.of())),
                tool("runtime_events",
                        "Query events by range, type, subject, and source; type can be prefix",
                        queryInput(Map.of(
                                "eventType", string(),
                                "eventTypePrefix", bool(),
                                "subject", string(),
                                "source", string()), List.of())),
                tool("runtime_decisions",
                        "Query decisions by exact type, actor, choice, and reason code",
                        queryInput(Map.of(
                                "decisionType", string(),
                                "actor", string(),
                                "chosenCandidate", string(),
                                "reasonCode", string()), List.of()))));
        if (supported.contains("runtime_command_status")) {
            selected.add(tool("runtime_command_status",
                    "Read retained, expired, or unknown application command status",
                    sessionInput(Map.of("commandRequestId", string()),
                            List.of("commandRequestId"))));
        }
        if (supported.contains("runtime_command_cancel")) {
            selected.add(tool("runtime_command_cancel",
                    "Cancel an application command only before capture-thread dispatch",
                    sessionInput(Map.of("commandRequestId", string()),
                            List.of("commandRequestId"))));
        }
        if (supported.contains("runtime_epoch_frames")) {
            selected.add(tool("runtime_epoch_frames",
                    "Read bounded completed frame summaries for one execution epoch",
                    sessionInput(Map.of(
                            "executionEpochId", integer(0, Long.MAX_VALUE),
                            "limit", integer(1, MAX_RESULTS)),
                            List.of("executionEpochId"))));
        }
        if (supported.contains("runtime_scenarios")) {
            selected.add(tool("runtime_scenarios",
                    "List explicitly registered application-owned scenarios",
                    sessionInput(Map.of(), List.of())));
        }
        if (supported.contains("runtime_reset")) {
            selected.add(tool("runtime_reset",
                    "Submit or poll an idempotently correlated scenario reset",
                    sessionInput(Map.of(
                            "scenarioId", string(),
                            "resetRequestId", string(),
                            "timeoutNanos", integer(1, Long.MAX_VALUE)),
                            List.of("scenarioId", "resetRequestId", "timeoutNanos"))));
        }
        if (supported.contains("runtime_attributed_changes")) {
            selected.add(tool("runtime_attributed_changes",
                    "Query changes with exact explicit subsystem or correlation filters",
                    queryInput(Map.of(
                            "entityId", string(), "entityType", string(), "property", string(),
                            "sourceSubsystem", string(), "correlationId", string()), List.of())));
        }
        if (supported.contains("runtime_attributed_events")) {
            selected.add(tool("runtime_attributed_events",
                    "Query events with explicit metadata; source remains an entity ID",
                    queryInput(Map.of(
                            "eventType", string(), "eventTypePrefix", bool(),
                            "subject", string(), "source", string(),
                            "sourceSubsystem", string(), "correlationId", string()), List.of())));
        }
        if (supported.contains("runtime_attributed_decisions")) {
            selected.add(tool("runtime_attributed_decisions",
                    "Query decisions with exact explicit subsystem or correlation filters",
                    queryInput(Map.of(
                            "decisionType", string(), "actor", string(),
                            "chosenCandidate", string(), "reasonCode", string(),
                            "sourceSubsystem", string(), "correlationId", string()), List.of())));
        }
        if (supported.contains("runtime_actions")) {
            selected.add(tool("runtime_actions",
                    "List explicitly registered typed semantic actions",
                    sessionInput(Map.of(), List.of())));
        }
        if (supported.contains("runtime_action")) {
            selected.add(tool("runtime_action",
                    "Submit or poll one registered semantic action",
                    actionInput(actionIndex.values())));
        }
        if (supported.contains("runtime_assert")) {
            selected.add(tool("runtime_assert",
                    "Evaluate one bounded closed assertion over completed immutable evidence",
                    assertionInput()));
        }
        if (supported.contains("runtime_control")) {
            selected.add(tool("runtime_control",
                    "Read control state or submit/poll an idempotent pause or resume",
                    sessionInput(Map.of(
                            "action", Map.of("type", "string",
                                    "enum", List.of("STATUS", "PAUSE", "RESUME")),
                            "controlRequestId", string(),
                            "timeoutNanos", integer(1, Long.MAX_VALUE)),
                            List.of("action"))));
        }
        if (supported.contains("runtime_advance")) {
            selected.add(tool("runtime_advance",
                    "Advance an exact bounded number of application-owned ticks while paused",
                    sessionInput(Map.of(
                            "controlRequestId", string(),
                            "ticks", integer(1, Integer.MAX_VALUE),
                            "deltaNanos", integer(0, Long.MAX_VALUE),
                            "timeoutNanos", integer(1, Long.MAX_VALUE)),
                            List.of("controlRequestId", "ticks", "deltaNanos", "timeoutNanos"))));
        }
        if (supported.contains("runtime_wait")) {
            selected.add(tool("runtime_wait",
                    "Advance while paused until a named condition or closed assertion holds",
                    waitInput()));
        }
        selected.removeIf(tool -> !supported.contains(tool.name()));
        tools = List.copyOf(selected);
        LinkedHashMap<String, McpSchema.Tool> index = new LinkedHashMap<>();
        tools.forEach(tool -> index.put(tool.name(), tool));
        byName = Map.copyOf(index);
    }

    /** Returns tools in stable catalog order. */
    public List<McpSchema.Tool> tools() {
        return tools;
    }

    /** Returns the exact tool-name set. */
    public Set<String> toolNames() {
        return byName.keySet();
    }

    /** Resolves one approved tool. */
    public McpSchema.Tool tool(String name) {
        McpSchema.Tool tool = byName.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("unknown runtime tool");
        }
        return tool;
    }

    /** Resolves one action schema captured when the server started. */
    public ActionDescriptor action(String id) {
        ActionDescriptor descriptor = actions.get(id);
        if (descriptor == null) {
            throw new IllegalArgumentException("unknown semantic action");
        }
        return descriptor;
    }

    private static McpSchema.Tool tool(
            String name, String description, Map<String, Object> input) {
        return McpSchema.Tool.builder(name, input).description(description).build();
    }

    private static Map<String, Object> sessionInput(
            Map<String, Object> additions, List<String> required) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("sessionId", string());
        properties.putAll(additions);
        ArrayList<String> requiredFields = new ArrayList<>();
        requiredFields.add("sessionId");
        requiredFields.addAll(required);
        return object(properties, requiredFields);
    }

    private static Map<String, Object> queryInput(
            Map<String, Object> additions, List<String> required) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("fromFrame", integer(0, Long.MAX_VALUE));
        properties.put("toFrame", integer(0, Long.MAX_VALUE));
        properties.put("limit", integer(1, MAX_RESULTS));
        properties.putAll(additions);
        return sessionInput(properties, required);
    }

    private static Map<String, Object> object(
            Map<String, Object> properties, List<String> required) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.copyOf(properties));
        if (!required.isEmpty()) {
            schema.put("required", List.copyOf(required));
        }
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> actionInput(
            java.util.Collection<ActionDescriptor> descriptors) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("sessionId", string());
        properties.put("action", Map.of("type", "string", "enum",
                descriptors.stream().map(ActionDescriptor::id).sorted().toList()));
        properties.put("actionRequestId", string());
        properties.put("correlationId", string());
        properties.put("timeoutNanos", integer(1, Long.MAX_VALUE));
        List<Map<String, Object>> schemas = descriptors.stream()
                .map(RuntimeToolCatalog::parameterObject).distinct().toList();
        properties.put("parameters", schemas.size() == 1 ? schemas.getFirst()
                : Map.of("anyOf", schemas));
        return object(properties,
                List.of("sessionId", "action", "actionRequestId", "parameters", "timeoutNanos"));
    }

    private static Map<String, Object> assertionInput() {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("sessionId", string());
        properties.put("fromFrame", integer(0, Long.MAX_VALUE));
        properties.put("toFrame", integer(0, Long.MAX_VALUE));
        properties.put("executionEpochId", integer(0, Long.MAX_VALUE));
        properties.put("evidenceLimit", integer(1, 100));
        properties.put("assertion", Map.of("oneOf", assertionSchemas()));
        return object(properties, List.of("sessionId", "fromFrame", "toFrame",
                "executionEpochId", "evidenceLimit", "assertion"));
    }

    private static Map<String, Object> waitInput() {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("sessionId", string());
        properties.put("controlRequestId", string());
        properties.put("conditionId", string());
        properties.put("assertion", Map.of("oneOf", assertionSchemas()));
        properties.put("maximumTicks", integer(1, Integer.MAX_VALUE));
        properties.put("deltaNanos", integer(0, Long.MAX_VALUE));
        properties.put("evidenceLimit", integer(1, AssertionScope.MAX_EVIDENCE));
        properties.put("timeoutNanos", integer(1, Long.MAX_VALUE));
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>(object(properties, List.of(
                "sessionId", "controlRequestId", "maximumTicks", "deltaNanos",
                "evidenceLimit", "timeoutNanos")));
        schema.put("oneOf", List.of(
                Map.of("required", List.of("conditionId")),
                Map.of("required", List.of("assertion"))));
        return Map.copyOf(schema);
    }

    private static List<Map<String, Object>> assertionSchemas() {
        Map<String, Object> entityId = Map.of("entityId", string());
        Map<String, Object> eventType = Map.of("eventType", string());
        Map<String, Object> decision = Map.of("decisionType", string(), "candidate", string());
        return List.of(
                discriminated("entityExists", entityId, List.of("entityId")),
                discriminated("entityDoesNotExist", entityId, List.of("entityId")),
                discriminated("propertyEquals", Map.of(
                        "entityId", string(), "property", string(), "expected", naturalValue()),
                        List.of("entityId", "property", "expected")),
                discriminated("propertyChangesFrom", Map.of(
                        "entityId", string(), "property", string(), "from", naturalValue()),
                        List.of("entityId", "property", "from")),
                discriminated("propertyRemainsWithinRange", Map.of(
                        "entityId", string(), "property", string(),
                        "minimum", Map.of("type", "number"),
                        "maximum", Map.of("type", "number")),
                        List.of("entityId", "property", "minimum", "maximum")),
                discriminated("eventOccurs", eventType, List.of("eventType")),
                discriminated("eventDoesNotOccur", eventType, List.of("eventType")),
                discriminated("eventOccursExactly", Map.of(
                        "eventType", string(), "count", integer(1, 1_000_000)),
                        List.of("eventType", "count")),
                discriminated("decisionSelected", decision,
                        List.of("decisionType", "candidate")),
                discriminated("decisionRejected", decision,
                        List.of("decisionType", "candidate")),
                discriminated("entityCountStaysBelow", Map.of(
                        "entityType", string(), "limit", integer(1, 1_000_000)),
                        List.of("limit")),
                discriminated("snapshotsEquivalent", Map.of(
                        "leftFrameId", integer(0, Long.MAX_VALUE),
                        "rightFrameId", integer(0, Long.MAX_VALUE),
                        "comparisonScope", comparisonScope()),
                        List.of("leftFrameId", "rightFrameId", "comparisonScope")));
    }

    private static Map<String, Object> discriminated(String type,
            Map<String, Object> additions, List<String> required) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("assertionType", Map.of("type", "string", "const", type));
        properties.putAll(additions);
        ArrayList<String> requiredFields = new ArrayList<>(List.of("assertionType"));
        requiredFields.addAll(required);
        return object(properties, requiredFields);
    }

    private static Map<String, Object> comparisonScope() {
        Map<String, Object> stringArray = Map.of(
                "type", "array", "items", string(),
                "maxItems", AssertionScope.MAX_EVIDENCE);
        return object(Map.of(
                "entityIds", stringArray,
                "properties", stringArray,
                "excludedProperties", stringArray,
                "includeEvents", bool(),
                "includeDecisions", bool()), List.of(
                        "entityIds", "properties", "excludedProperties",
                        "includeEvents", "includeDecisions"));
    }

    private static Map<String, Object> naturalValue() {
        return Map.of("type", List.of(
                "null", "boolean", "integer", "number", "string", "array", "object"));
    }

    private static Map<String, Object> parameterObject(ActionDescriptor descriptor) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        ArrayList<String> required = new ArrayList<>();
        for (ActionParameter parameter : descriptor.parameters()) {
            properties.put(parameter.name(), parameterSchema(parameter.type()));
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }
        return object(properties, required);
    }

    private static Map<String, Object> parameterSchema(ActionParameterType type) {
        return switch (type) {
            case BOOLEAN -> bool();
            case INTEGER -> integer(Long.MIN_VALUE, Long.MAX_VALUE);
            case DECIMAL -> Map.of("type", "number");
            case STRING -> Map.of("type", "string", "maxLength", 16_384);
            case ENUM, ENTITY_ID -> string();
        };
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string", "minLength", 1, "maxLength", MAX_IDENTIFIER);
    }

    private static Map<String, Object> bool() {
        return Map.of("type", "boolean");
    }

    private static Map<String, Object> integer(long minimum, long maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }
}
