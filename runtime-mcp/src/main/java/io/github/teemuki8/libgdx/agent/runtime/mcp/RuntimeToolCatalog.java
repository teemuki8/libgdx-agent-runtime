package io.github.teemuki8.libgdx.agent.runtime.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable catalog of the closed base tools and registered optional tools. */
public final class RuntimeToolCatalog {
    private static final int MAX_IDENTIFIER = 256;
    private static final int MAX_RESULTS = 1_000;
    private final List<McpSchema.Tool> tools;
    private final Map<String, McpSchema.Tool> byName;

    /** Builds the fixed catalog. */
    public RuntimeToolCatalog() {
        this(Set.copyOf(io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService
                .BASE_TOOLS));
    }

    /** Builds a server-start catalog from the protocol's deterministic supported-tool union. */
    public RuntimeToolCatalog(java.util.Collection<String> supportedTools) {
        Set<String> supported = Set.copyOf(supportedTools);
        ArrayList<McpSchema.Tool> selected = new ArrayList<>(List.of(
                tool("runtime_sessions",
                        "List published runtime sessions; no arguments",
                        object(Map.of(), List.of())),
                tool("runtime_capabilities",
                        "Report capabilities; protocolMinor defaults to frozen V1.0",
                        sessionInput(Map.of("protocolMinor", integer(0, 2)), List.of())),
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
