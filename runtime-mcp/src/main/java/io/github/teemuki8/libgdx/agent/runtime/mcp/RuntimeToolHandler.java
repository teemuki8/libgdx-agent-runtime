package io.github.teemuki8.libgdx.agent.runtime.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolJson;
import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolVersion;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeCommand;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRequest;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeResponse;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.Map;
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
        this(protocol, new RuntimeToolCatalog(protocol.toolNames()));
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
            default -> ProtocolVersion.V1;
        };
        return new RuntimeRequest(version,
                "mcp-" + Long.toUnsignedString(sequence.incrementAndGet()), sessionId, command);
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
