package io.github.teemuki8.libgdx.agent.runtime.protocol;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntimeException;
import io.github.teemuki8.libgdx.agent.runtime.core.ChangeQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionType;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityHistory;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntitySnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.EventQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameId;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameRange;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameSnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.QueryPage;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SnapshotStats;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Executes explicit protocol commands against an injected registry. */
public final class RuntimeProtocolService {
    /** Stable MCP tool names corresponding to the protocol commands. */
    public static final List<String> TOOLS = List.of(
            "runtime_sessions", "runtime_capabilities", "runtime_frames", "runtime_snapshot",
            "runtime_entity", "runtime_changes", "runtime_events", "runtime_decisions");
    private static final List<String> FEATURES = List.of(
            "entities", "frames", "changes", "events", "decisions");
    private final RuntimeRegistry registry;

    /** Creates a service over an isolated or global registry. */
    public RuntimeProtocolService(RuntimeRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Executes one request without exposing local exceptions or stack traces. */
    public RuntimeResponse execute(RuntimeRequest request) {
        Objects.requireNonNull(request, "request");
        if (!ProtocolVersion.V1.equals(request.version())) {
            return failure(request, ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED,
                    "protocol version is unsupported", Map.of(
                            "supported", "1.0",
                            "requested", request.version().major() + "." + request.version().minor()));
        }
        try {
            return new RuntimeResponse.Success(
                    ProtocolVersion.V1, request.requestId(), executeCommand(request));
        } catch (ProtocolFailure failure) {
            return failure(request, failure.code, failure.getMessage(), failure.details);
        } catch (AgentRuntimeException failure) {
            ProtocolErrorCode code = failure.code().name().equals("LIMIT_EXCEEDED")
                    ? ProtocolErrorCode.LIMIT_EXCEEDED : ProtocolErrorCode.INVALID_QUERY;
            return failure(request, code, failure.getMessage(), Map.of());
        } catch (IllegalArgumentException failure) {
            return failure(request, ProtocolErrorCode.INVALID_QUERY,
                    "request fields are invalid", Map.of());
        } catch (RuntimeException failure) {
            return failure(request, ProtocolErrorCode.INTERNAL_ERROR,
                    "runtime query failed", Map.of());
        }
    }

    private RuntimeResponse.Result executeCommand(RuntimeRequest request) {
        if (request.command() instanceof RuntimeCommand.Sessions) {
            List<RuntimeResponse.SessionInfo> sessions = registry.sessions().stream()
                    .map(runtime -> new RuntimeResponse.SessionInfo(
                            runtime.sessionId().value(), runtime.status(),
                            runtime.latestFrame().map(frame -> frame.frameId().value())))
                    .toList();
            return new RuntimeResponse.Result.Sessions(sessions);
        }
        AgentRuntime runtime = registry.find(SessionId.of(request.sessionId()))
                .orElseThrow(() -> new ProtocolFailure(
                        ProtocolErrorCode.SESSION_NOT_FOUND, "runtime session was not found",
                        Map.of("sessionId", request.sessionId())));
        return switch (request.command()) {
            case RuntimeCommand.Capabilities ignored -> capabilities(runtime);
            case RuntimeCommand.Frames command -> new RuntimeResponse.Result.Frames(
                    runtime.frames(range(command.fromFrame(), command.toFrame()), command.limit()));
            case RuntimeCommand.Snapshot command -> snapshot(runtime, command);
            case RuntimeCommand.Entity command -> entity(runtime, command);
            case RuntimeCommand.Changes command -> changes(runtime, command);
            case RuntimeCommand.Events command -> events(runtime, command);
            case RuntimeCommand.Decisions command -> decisions(runtime, command);
            case RuntimeCommand.Sessions ignored ->
                    throw new AssertionError("sessions handled before runtime lookup");
        };
    }

    private static RuntimeResponse.Result capabilities(AgentRuntime runtime) {
        return new RuntimeResponse.Result.Capabilities(
                ProtocolVersion.V1, TOOLS, FEATURES, runtime.configuration().limits(),
                runtime.latestFrame().map(frame -> frame.frameId().value()), runtime.status());
    }

    private static RuntimeResponse.Result snapshot(
            AgentRuntime runtime, RuntimeCommand.Snapshot command) {
        FrameSnapshot source = command.frameId() == null
                ? runtime.latestFrame().orElseThrow(() -> captureUnavailable(runtime))
                : runtime.frame(new FrameId(command.frameId())).orElseThrow(() ->
                        new ProtocolFailure(ProtocolErrorCode.FRAME_NOT_FOUND,
                                "frame was not retained",
                                Map.of("frameId", Long.toString(command.frameId()))));
        List<EntitySnapshot> matches = source.entities().stream()
                .filter(entity -> matches(entity.id().value(), command.entityId(),
                        command.entityIdPrefix()))
                .filter(entity -> matches(entity.type().value(), command.entityType(),
                        command.entityTypePrefix()))
                .toList();
        boolean filtered = command.entityId() != null || command.entityType() != null;
        boolean hasMore = matches.size() > command.limit();
        List<EntitySnapshot> retained = matches.stream().limit(command.limit()).toList();
        FrameSnapshot result = new FrameSnapshot(
                source.sessionId(), source.frameId(), source.monotonicTimeNanos(),
                source.deltaNanos(), source.capturedAt(), retained,
                source.changes().stream()
                        .filter(change -> retained.stream()
                                .anyMatch(entity -> entity.id().equals(change.entityId())))
                        .toList(),
                source.events(), source.decisions(),
                new SnapshotStats(matches.size(), retained.size(),
                        source.stats().diagnostics(), source.stats().truncations()));
        return new RuntimeResponse.Result.Snapshot(result, filtered, hasMore);
    }

    private static RuntimeResponse.Result entity(
            AgentRuntime runtime, RuntimeCommand.Entity command) {
        EntityId id = EntityId.of(command.entityId());
        EntitySnapshot latest = runtime.entity(id).orElseThrow(() ->
                new ProtocolFailure(ProtocolErrorCode.ENTITY_NOT_FOUND,
                        "entity was not found", Map.of("entityId", command.entityId())));
        FrameRange range = range(command.fromFrame(), command.toFrame());
        EntityHistory full = runtime.entityHistory(id, range);
        List<EntityHistory.Version> versions =
                full.versions().stream().limit(command.limit()).toList();
        QueryPage<io.github.teemuki8.libgdx.agent.runtime.core.PropertyChange> changes =
                runtime.changes(new ChangeQuery(range, Optional.of(id), Optional.empty(),
                        Optional.empty(), command.limit()));
        return new RuntimeResponse.Result.Entity(
                latest, new EntityHistory(id, versions, changes));
    }

    private static RuntimeResponse.Result changes(
            AgentRuntime runtime, RuntimeCommand.Changes command) {
        return new RuntimeResponse.Result.Changes(runtime.changes(new ChangeQuery(
                range(command.fromFrame(), command.toFrame()),
                optional(command.entityId()).map(EntityId::of),
                optional(command.entityType()).map(EntityType::of),
                optional(command.property()), command.limit())));
    }

    private static RuntimeResponse.Result events(
            AgentRuntime runtime, RuntimeCommand.Events command) {
        return new RuntimeResponse.Result.Events(runtime.events(new EventQuery(
                range(command.fromFrame(), command.toFrame()), optional(command.eventType()),
                command.eventTypePrefix(), optional(command.subject()).map(EntityId::of),
                optional(command.source()).map(EntityId::of), command.limit())));
    }

    private static RuntimeResponse.Result decisions(
            AgentRuntime runtime, RuntimeCommand.Decisions command) {
        return new RuntimeResponse.Result.Decisions(runtime.decisions(new DecisionQuery(
                range(command.fromFrame(), command.toFrame()),
                optional(command.decisionType()).map(DecisionType::of),
                optional(command.actor()).map(EntityId::of),
                optional(command.chosenCandidate()).map(EntityId::of),
                optional(command.reasonCode()), command.limit())));
    }

    private static FrameRange range(long from, long to) {
        try {
            return FrameRange.of(from, to);
        } catch (IllegalArgumentException failure) {
            throw new ProtocolFailure(
                    ProtocolErrorCode.INVALID_RANGE, "frame range is invalid", Map.of());
        }
    }

    private static boolean matches(String value, String filter, boolean prefix) {
        return filter == null || (prefix ? value.startsWith(filter) : value.equals(filter));
    }

    private static Optional<String> optional(String value) {
        return Optional.ofNullable(value);
    }

    private static ProtocolFailure captureUnavailable(AgentRuntime runtime) {
        return new ProtocolFailure(ProtocolErrorCode.CAPTURE_NOT_AVAILABLE,
                "runtime has no completed frame", Map.of("status", runtime.status().name()));
    }

    private static RuntimeResponse failure(RuntimeRequest request, ProtocolErrorCode code,
            String message, Map<String, String> details) {
        return new RuntimeResponse.Failure(
                ProtocolVersion.V1, request.requestId(), new ProtocolError(code, message, details));
    }

    @SuppressWarnings("serial")
    private static final class ProtocolFailure extends RuntimeException {
        private final ProtocolErrorCode code;
        private final Map<String, String> details;

        ProtocolFailure(ProtocolErrorCode code, String message, Map<String, String> details) {
            super(message);
            this.code = code;
            this.details = details;
        }
    }
}
