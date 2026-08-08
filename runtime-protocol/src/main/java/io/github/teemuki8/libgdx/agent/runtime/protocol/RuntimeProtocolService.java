package io.github.teemuki8.libgdx.agent.runtime.protocol;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.ActionDescriptor;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntimeException;
import io.github.teemuki8.libgdx.agent.runtime.core.ApplicationFailureEvidence;
import io.github.teemuki8.libgdx.agent.runtime.core.ChangeQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionType;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityHistory;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntitySnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.ExecutionEpochId;
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
import java.util.stream.Stream;
import java.time.Duration;

/** Executes explicit protocol commands against an injected registry. */
public final class RuntimeProtocolService {
    /** Stable MCP tool names corresponding to the protocol commands. */
    public static final List<String> TOOLS = List.of(
            "runtime_sessions", "runtime_capabilities", "runtime_frames", "runtime_snapshot",
            "runtime_entity", "runtime_changes", "runtime_events", "runtime_decisions");
    /** Alias naming the frozen base-tool catalog explicitly. */
    public static final List<String> BASE_TOOLS = TOOLS;
    private static final List<String> COMMAND_TOOLS =
            List.of("runtime_command_status", "runtime_command_cancel");
    private static final List<String> EPOCH_TOOLS = List.of("runtime_epoch_frames");
    private static final List<String> SCENARIO_TOOLS =
            List.of("runtime_scenarios", "runtime_reset");
    private static final List<String> ATTRIBUTION_TOOLS = List.of(
            "runtime_attributed_changes", "runtime_attributed_events",
            "runtime_attributed_decisions");
    private static final List<String> ACTION_TOOLS = List.of("runtime_actions", "runtime_action");
    private static final List<String> ASSERTION_TOOLS = List.of("runtime_assert");
    private static final List<String> CONTROL_TOOLS = List.of(
            "runtime_control", "runtime_advance", "runtime_wait");
    private static final List<String> INPUT_TOOLS = List.of("runtime_inputs", "runtime_input");
    private static final List<String> CHECKPOINT_TOOLS = List.of(
            "runtime_checkpoints", "runtime_checkpoint_create", "runtime_checkpoint_restore");
    private static final List<String> UI_CORRELATION_TOOLS =
            List.of("runtime_ui_bindings", "runtime_ui_frames");
    private static final List<String> RECORDING_TOOLS = List.of(
            "runtime_recording_start", "runtime_recording_stop", "runtime_recording_get");
    private static final List<String> DETERMINISM_TOOLS =
            List.of("runtime_determinism_check");
    private static final List<String> V2_TOOLS = List.of("runtime_entity_history");
    private static final List<String> FEATURES = List.of(
            "entities", "frames", "changes", "events", "decisions");
    private static final List<ProtocolVersion> SUPPORTED_VERSIONS =
            List.of(ProtocolVersion.V1, ProtocolVersion.V1_1, ProtocolVersion.V1_2,
                    ProtocolVersion.V1_3, ProtocolVersion.V1_4, ProtocolVersion.V1_5,
                    ProtocolVersion.V1_6, ProtocolVersion.V1_7, ProtocolVersion.V1_8,
                    ProtocolVersion.V1_9, ProtocolVersion.V1_10, ProtocolVersion.V1_11,
                    ProtocolVersion.V1_12, ProtocolVersion.V1_13, ProtocolVersion.V2);
    private final RuntimeRegistry registry;

    /** Creates a service over an isolated or global registry. */
    public RuntimeProtocolService(RuntimeRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Returns the server-start tool union backed by currently published implementations. */
    public List<String> toolNames() {
        boolean commandDispatch = registry.sessions().stream()
                .anyMatch(runtime -> runtime.commands().isPresent());
        Stream<String> tools = Stream.concat(Stream.concat(
                Stream.concat(BASE_TOOLS.stream(), EPOCH_TOOLS.stream()),
                ATTRIBUTION_TOOLS.stream()), ASSERTION_TOOLS.stream());
        if (commandDispatch) {
            tools = Stream.concat(tools, COMMAND_TOOLS.stream());
        }
        boolean scenarios = registry.sessions().stream()
                .anyMatch(runtime -> !runtime.scenarios().list().isEmpty());
        if (scenarios) {
            tools = Stream.concat(tools, SCENARIO_TOOLS.stream());
        }
        boolean actions = registry.sessions().stream()
                .anyMatch(runtime -> !runtime.actions().list().isEmpty());
        if (actions) {
            tools = Stream.concat(tools, ACTION_TOOLS.stream());
        }
        boolean controls = registry.sessions().stream()
                .anyMatch(runtime -> runtime.controls().available());
        if (controls) {
            tools = Stream.concat(tools, CONTROL_TOOLS.stream());
        }
        boolean inputs = registry.sessions().stream()
                .anyMatch(runtime -> !runtime.inputs().list().isEmpty());
        if (inputs) {
            tools = Stream.concat(tools, INPUT_TOOLS.stream());
        }
        boolean checkpoints = registry.sessions().stream()
                .anyMatch(runtime -> runtime.checkpoints().available());
        if (checkpoints) {
            tools = Stream.concat(tools, CHECKPOINT_TOOLS.stream());
        }
        boolean uiCorrelations = registry.sessions().stream()
                .anyMatch(runtime -> runtime.uiCorrelations().available());
        if (uiCorrelations) {
            tools = Stream.concat(tools, UI_CORRELATION_TOOLS.stream());
        }
        if (commandDispatch) {
            tools = Stream.concat(tools, RECORDING_TOOLS.stream());
        }
        boolean determinism = registry.sessions().stream().anyMatch(runtime ->
                runtime.commands().isPresent() && runtime.controls().available()
                        && runtime.scenarios().determinismAvailable());
        if (determinism) {
            tools = Stream.concat(tools, DETERMINISM_TOOLS.stream());
        }
        return Stream.concat(tools, V2_TOOLS.stream()).toList();
    }

    /** Returns registered action schemas in deterministic session and action order. */
    public List<ActionDescriptor> actionCatalog() {
        java.util.LinkedHashMap<String, ActionDescriptor> catalog = new java.util.LinkedHashMap<>();
        registry.sessions().forEach(runtime -> runtime.actions().list().forEach(descriptor -> {
            ActionDescriptor previous = catalog.putIfAbsent(descriptor.id(), descriptor);
            if (previous != null && !previous.equals(descriptor)) {
                throw new IllegalStateException(
                        "published sessions use conflicting schemas for one action id");
            }
        }));
        return List.copyOf(catalog.values());
    }

    /** Returns registered input schemas in deterministic session and input order. */
    public List<io.github.teemuki8.libgdx.agent.runtime.core.InputDescriptor> inputCatalog() {
        java.util.LinkedHashMap<String,
                io.github.teemuki8.libgdx.agent.runtime.core.InputDescriptor> catalog =
                        new java.util.LinkedHashMap<>();
        registry.sessions().forEach(runtime -> runtime.inputs().list().forEach(descriptor -> {
            var previous = catalog.putIfAbsent(descriptor.id(), descriptor);
            if (previous != null && !previous.equals(descriptor)) {
                throw new IllegalStateException(
                        "published sessions use conflicting schemas for one input id");
            }
        }));
        return List.copyOf(catalog.values());
    }

    public RuntimeResponse execute(RuntimeRequest request) {
        Objects.requireNonNull(request, "request");
        if (!SUPPORTED_VERSIONS.contains(request.version())) {
            return failure(request, ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED,
                    "protocol version is unsupported", Map.of(
                            "supported",
                            "1.0,1.1,1.2,1.3,1.4,1.5,1.6,1.7,1.8,1.9,1.10,1.11,1.12,1.13,2.0",
                            "requested", request.version().major() + "." + request.version().minor()));
        }
        try {
            if (!request.version().capability(request.command())) {
                throw new ProtocolFailure(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED,
                        request.version().requiredVersionMessage(request.command()), Map.of());
            }
            return new RuntimeResponse.Success(
                    request.version(), request.requestId(), executeCommand(request));
        } catch (ProtocolFailure failure) {
            return failure(request, failure.code, failure.getMessage(), failure.details);
        } catch (AgentRuntimeException failure) {
            ProtocolErrorCode code = switch (failure.code()) {
                case LIMIT_EXCEEDED -> ProtocolErrorCode.LIMIT_EXCEEDED;
                case RECORDING_EVICTED -> ProtocolErrorCode.RECORDING_EVICTED;
                case ENTITY_HISTORY_NOT_RETAINED -> ProtocolErrorCode.ENTITY_HISTORY_NOT_RETAINED;
                default -> ProtocolErrorCode.INVALID_QUERY;
            };
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
            case RuntimeCommand.Capabilities ignored -> capabilities(runtime, request.version());
            case RuntimeCommand.Frames command -> new RuntimeResponse.Result.Frames(
                    runtime.frames(range(command.fromFrame(), command.toFrame()), command.limit()));
            case RuntimeCommand.Snapshot command -> snapshot(runtime, command);
            case RuntimeCommand.Entity command -> entity(runtime, command);
            case RuntimeCommand.Changes command -> changes(runtime, command);
            case RuntimeCommand.Events command -> events(runtime, command);
            case RuntimeCommand.Decisions command -> decisions(runtime, command);
            case RuntimeCommand.CommandStatus command -> commandStatus(runtime, command,
                    request.version());
            case RuntimeCommand.CommandCancel command ->
                    new RuntimeResponse.Result.CommandCancellation(
                            runtime.commands().orElseThrow(() -> capabilityUnavailable(runtime))
                                    .cancel(command.commandRequestId()));
            case RuntimeCommand.EpochFrames command -> epochFrames(runtime, command);
            case RuntimeCommand.Scenarios ignored ->
                    new RuntimeResponse.Result.Scenarios(runtime.scenarios().list());
            case RuntimeCommand.Reset command -> reset(runtime, command);
            case RuntimeCommand.AttributedChanges command -> attributedChanges(runtime, command);
            case RuntimeCommand.AttributedEvents command -> attributedEvents(runtime, command);
            case RuntimeCommand.AttributedDecisions command -> attributedDecisions(runtime, command);
            case RuntimeCommand.Actions ignored ->
                    new RuntimeResponse.Result.Actions(runtime.actions().list());
            case RuntimeCommand.Action command -> action(runtime, command);
            case RuntimeCommand.Assert command -> assertion(runtime, command);
            case RuntimeCommand.Control command -> control(runtime, command);
            case RuntimeCommand.Advance command -> advance(runtime, command);
            case RuntimeCommand.Wait command -> waitFor(runtime, command);
            case RuntimeCommand.Inputs ignored ->
                    new RuntimeResponse.Result.Inputs(runtime.inputs().list());
            case RuntimeCommand.UiBindings command -> uiBindings(runtime, command);
            case RuntimeCommand.UiFrames command -> uiFrames(runtime, command);
            case RuntimeCommand.Input command -> input(runtime, command, request.version());
            case RuntimeCommand.Checkpoints ignored ->
                    new RuntimeResponse.Result.Checkpoints(runtime.checkpoints().list());
            case RuntimeCommand.CheckpointCreate command ->
                    checkpointCreate(runtime, command, request.version());
            case RuntimeCommand.CheckpointRestore command ->
                    checkpointRestore(runtime, command, request.version());
            case RuntimeCommand.RecordingStart command -> recordingStart(runtime, command);
            case RuntimeCommand.RecordingStop command -> recordingStop(runtime, command);
            case RuntimeCommand.RecordingGet command -> new RuntimeResponse.Result.RecordingChunkResult(
                    runtime.recordings().get(command.recordingId(), command.offset(), command.limit()));
            case RuntimeCommand.DeterminismCheck command -> determinism(runtime, command,
                    request.version());
            case RuntimeCommand.EntityHistory command -> entityHistory(runtime, command);
            case RuntimeCommand.Sessions ignored ->
                    throw new AssertionError("sessions handled before runtime lookup");
        };
    }

    private static RuntimeResponse.Result capabilities(
            AgentRuntime runtime, ProtocolVersion version) {
        if (ProtocolVersion.V1.equals(version)) {
            return new RuntimeResponse.Result.Capabilities(
                    ProtocolVersion.V1, BASE_TOOLS, FEATURES, runtime.configuration().limits(),
                    runtime.latestFrame().map(frame -> frame.frameId().value()), runtime.status());
        }
        List<RuntimeCapability> details = capabilityDetails(runtime, version);
        List<String> enabled = details.stream()
                .filter(capability -> capability.availability()
                        == RuntimeCapability.Availability.AVAILABLE)
                .map(RuntimeCapability::id)
                .toList();
        return new RuntimeResponse.Result.Capabilities(
                version, toolsFor(runtime, version),
                enabled, runtime.configuration().limits(),
                runtime.latestFrame().map(frame -> frame.frameId().value()), runtime.status(),
                Optional.of(new CapabilityReport(RuntimeVersion.current(), details)));
    }

    private static List<RuntimeCapability> capabilityDetails(
            AgentRuntime runtime, ProtocolVersion version) {
        var limits = runtime.configuration().limits();
        List<RuntimeCapability> details = new java.util.ArrayList<>(List.of(
                capability(runtime, "changes", List.of("AgentRuntime#changes"),
                        List.of("changes"), List.of("runtime_changes"), Map.of(
                                "queryResults", (long) limits.queryResults(),
                                "retainedFrames", (long) limits.retainedFrames()),
                        List.of("exact-property-filter"), List.of("frames")),
                capability(runtime, "decisions", List.of("AgentRuntime#decisions"),
                        List.of("decisions"), List.of("runtime_decisions"), Map.of(
                                "candidatesPerDecision", (long) limits.candidatesPerDecision(),
                                "decisionsPerFrame", (long) limits.decisionsPerFrame(),
                                "queryResults", (long) limits.queryResults()),
                        List.of("exact-filter"), List.of("frames")),
                capability(runtime, "entities", List.of(
                                "AgentRuntime#entities", "AgentRuntime#entity",
                                "AgentRuntime#entityHistory"),
                        List.of("entity", "snapshot"),
                        List.of("runtime_entity", "runtime_snapshot"), Map.of(
                                "entitiesPerSnapshot", (long) limits.entitiesPerSnapshot(),
                                "propertiesPerEntity", (long) limits.propertiesPerEntity(),
                                "queryResults", (long) limits.queryResults()),
                        List.of("exact", "history", "prefix"), List.of("frames")),
                capability(runtime, "events", List.of("AgentRuntime#events"),
                        List.of("events"), List.of("runtime_events"), Map.of(
                                "attributesPerItem", (long) limits.attributesPerItem(),
                                "queryResults", (long) limits.queryResults(),
                                "retainedEvents", (long) limits.retainedEvents()),
                        List.of("exact-filter", "type-prefix-filter"), List.of("frames")),
                capability(runtime, "frames", List.of(
                                "AgentRuntime#frame", "AgentRuntime#frames",
                                "AgentRuntime#latestFrame"),
                        List.of("frames", "snapshot"),
                        List.of("runtime_frames", "runtime_snapshot"), Map.of(
                                "queryResults", (long) limits.queryResults(),
                                "retainedFrames", (long) limits.retainedFrames()),
                        List.of("exact", "latest", "range"), List.of())));
        if (version.atLeast(2)) {
            boolean available = runtime.commands().isPresent();
            Map<String, Long> commandLimits = runtime.commands().map(commands -> Map.of(
                    "queuedCommands", (long) commands.limits().queuedCommands(),
                    "maximumTimeoutNanos", commands.limits().maximumTimeoutNanos(),
                    "retainedRequestIds", (long) commands.limits().retainedRequestIds(),
                    "retainedResults", (long) commands.limits().retainedResults()))
                    .orElse(Map.of());
            details.add(new RuntimeCapability(
                    "command-dispatch", ProtocolVersion.V1_2,
                    available ? RuntimeCapability.Availability.AVAILABLE
                            : RuntimeCapability.Availability.UNAVAILABLE,
                    available ? Optional.empty() : Optional.of(runtime.configuration().enabled()
                            ? "dispatcher-not-registered" : "runtime-disabled"),
                    RuntimeCapability.Access.MUTATING,
                    List.of("AgentRuntime#commands", "CommandDispatch#status",
                            "CommandDispatch#cancel"),
                    List.of("commandStatus", "commandCancel"), COMMAND_TOOLS, commandLimits,
                    List.of("application-owned", "at-most-once", "bounded"), List.of()));
        }
        if (version.atLeast(3)) {
            details.add(new RuntimeCapability(
                    "execution-epochs", ProtocolVersion.V1_3,
                    runtime.configuration().enabled()
                            ? RuntimeCapability.Availability.AVAILABLE
                            : RuntimeCapability.Availability.UNAVAILABLE,
                    runtime.configuration().enabled() ? Optional.empty()
                            : Optional.of("runtime-disabled"),
                    RuntimeCapability.Access.READ_ONLY,
                    List.of("AgentRuntime#currentEpoch", "AgentRuntime#frames",
                            "AgentRuntime#startEpoch"),
                    List.of("epochFrames"), EPOCH_TOOLS, Map.of(
                            "queryResults", (long) limits.queryResults(),
                            "retainedFrames", (long) limits.retainedFrames()),
                    List.of("baseline", "epoch-filter"), List.of("frames")));
        }
        if (version.atLeast(4)) {
            boolean registered = !runtime.scenarios().list().isEmpty();
            boolean available = registered && runtime.commands().isPresent();
            details.add(new RuntimeCapability(
                    "resettable-scenarios", ProtocolVersion.V1_4,
                    available ? RuntimeCapability.Availability.AVAILABLE
                            : RuntimeCapability.Availability.UNAVAILABLE,
                    available ? Optional.empty() : Optional.of(registered
                            ? "dispatcher-not-registered" : "scenario-not-registered"),
                    RuntimeCapability.Access.MUTATING,
                    List.of("AgentRuntime#scenarios", "ScenarioRegistry#register",
                            "ScenarioRegistry#reset"),
                    List.of("scenarios", "reset"), SCENARIO_TOOLS, Map.of(
                            "registeredScenarios",
                            (long) runtime.scenarios().limits().registeredScenarios(),
                            "retainedResetResults",
                            (long) runtime.scenarios().limits().retainedResetResults()),
                    List.of("application-owned", "explicit-registration", "bounded",
                            "reset-parameters-unsupported", "seed-control-unsupported"),
                    List.of("command-dispatch", "execution-epochs")));
        }
        if (version.atLeast(5)) {
            details.add(new RuntimeCapability(
                    "explicit-fact-attribution", ProtocolVersion.V1_5,
                    runtime.configuration().enabled()
                            ? RuntimeCapability.Availability.AVAILABLE
                            : RuntimeCapability.Availability.UNAVAILABLE,
                    runtime.configuration().enabled() ? Optional.empty()
                            : Optional.of("runtime-disabled"),
                    RuntimeCapability.Access.READ_ONLY,
                    List.of("EventSpec#sourceSubsystem", "EventSpec#sourceLocation",
                            "EventSpec#correlationId", "AgentRuntime#beginDecision",
                            "ChangeCause#withMetadata"),
                    List.of("attributedChanges", "attributedEvents", "attributedDecisions"),
                    ATTRIBUTION_TOOLS, Map.of(
                            "sourceLocationLength",
                            (long) io.github.teemuki8.libgdx.agent.runtime.core.FactMetadata
                                    .MAX_SOURCE_LOCATION_LENGTH,
                            "queryResults", (long) limits.queryResults()),
                    List.of("explicit", "exact-filter", "unverified-source-label"),
                    List.of("changes", "events", "decisions")));
        }
        if (version.atLeast(6)) {
            boolean registered = !runtime.actions().list().isEmpty();
            boolean available = registered && runtime.commands().isPresent();
            details.add(new RuntimeCapability(
                    "semantic-actions", ProtocolVersion.V1_6,
                    available ? RuntimeCapability.Availability.AVAILABLE
                            : RuntimeCapability.Availability.UNAVAILABLE,
                    available ? Optional.empty() : Optional.of(registered
                            ? "dispatcher-not-registered" : "action-not-registered"),
                    RuntimeCapability.Access.MUTATING,
                    List.of("AgentRuntime#actions", "ActionRegistry#register",
                            "ActionRegistry#invoke"), List.of("actions", "action"), ACTION_TOOLS,
                    runtime.actions().limits().registeredActions() > 0 ? Map.of(
                            "registeredActions",
                            (long) runtime.actions().limits().registeredActions(),
                            "parametersPerAction",
                            (long) runtime.actions().limits().parametersPerAction(),
                            "retainedInvocations",
                            (long) runtime.actions().limits().retainedInvocations(),
                            "stringLength", (long) runtime.actions().limits().stringLength())
                            : Map.of(),
                    List.of("application-owned", "closed-schema", "at-most-once"),
                    List.of("command-dispatch", "explicit-fact-attribution")));
        }
        if (version.atLeast(7)) {
            boolean available = runtime.configuration().enabled();
            details.add(new RuntimeCapability(
                    "declarative-assertions", ProtocolVersion.V1_7,
                    available ? RuntimeCapability.Availability.AVAILABLE
                            : RuntimeCapability.Availability.UNAVAILABLE,
                    available ? Optional.empty() : Optional.of("runtime-disabled"),
                    RuntimeCapability.Access.READ_ONLY,
                    List.of("AgentRuntime#assertions", "AssertionEvaluator#evaluate"),
                    List.of("assert"), ASSERTION_TOOLS, Map.of(
                            "evaluatedFrames",
                            (long) io.github.teemuki8.libgdx.agent.runtime.core.AssertionScope
                                    .MAX_FRAMES,
                            "supportingEvidence",
                            (long) io.github.teemuki8.libgdx.agent.runtime.core.AssertionScope
                                    .MAX_EVIDENCE),
                    List.of("closed-schema", "deterministic", "bounded"),
                    List.of("execution-epochs", "completed-frames")));
        }
        if (version.atLeast(8)) {
            boolean registered = runtime.controls().available();
            boolean available = registered && runtime.commands().isPresent();
            var controlLimits = runtime.controls().limits();
            details.add(new RuntimeCapability(
                    "simulation-control", ProtocolVersion.V1_8,
                    available ? RuntimeCapability.Availability.AVAILABLE
                            : RuntimeCapability.Availability.UNAVAILABLE,
                    available ? Optional.empty() : Optional.of(registered
                            ? "dispatcher-not-registered" : "controller-not-registered"),
                    RuntimeCapability.Access.MUTATING,
                    List.of("AgentRuntime#controls", "SimulationControlRegistry#control",
                            "SimulationControlRegistry#advance",
                            "SimulationControlRegistry#waitForCondition",
                            "SimulationControlRegistry#waitForAssertion"),
                    List.of("control", "advance", "wait"), CONTROL_TOOLS, Map.of(
                            "registeredConditions", (long) controlLimits.registeredConditions(),
                            "ticksPerOperation", (long) controlLimits.ticksPerOperation(),
                            "retainedOperations", (long) controlLimits.retainedOperations(),
                            "maximumDeltaNanos", controlLimits.maximumDeltaNanos()),
                    List.of("application-owned", "paused-only-ticks", "bounded", "at-most-once"),
                    List.of("command-dispatch", "declarative-assertions")));
        }
        if (version.atLeast(9)) {
            boolean registered = !runtime.inputs().list().isEmpty();
            boolean dispatcher = runtime.commands().isPresent();
            boolean controller = runtime.controls().available();
            boolean available = registered && dispatcher && controller;
            var inputLimits = runtime.inputs().limits();
            details.add(new RuntimeCapability(
                    "registered-inputs", ProtocolVersion.V1_9,
                    available ? RuntimeCapability.Availability.AVAILABLE
                            : RuntimeCapability.Availability.UNAVAILABLE,
                    available ? Optional.empty() : Optional.of(!registered
                            ? "input-not-registered" : !dispatcher
                                    ? "dispatcher-not-registered" : "controller-not-registered"),
                    RuntimeCapability.Access.MUTATING,
                    List.of("AgentRuntime#inputs", "InputRegistry#register",
                            "InputRegistry#inject"),
                    List.of("inputs", "input"), INPUT_TOOLS, Map.of(
                            "registeredInputs", (long) inputLimits.registeredInputs(),
                            "parametersPerInput", (long) inputLimits.parametersPerInput(),
                            "queuedInputs", (long) inputLimits.queuedInputs(),
                            "retainedInjections", (long) inputLimits.retainedInjections(),
                            "futureTicks", (long) inputLimits.futureTicks(),
                            "stringLength", (long) inputLimits.stringLength()),
                    List.of("application-owned", "closed-schema", "controlled-tick",
                            "at-most-once", "redaction"),
                    List.of("command-dispatch", "simulation-control")));
        }
        if (version.atLeast(10)) {
            boolean registered = runtime.checkpoints().available();
            boolean available = registered && runtime.commands().isPresent();
            var checkpointLimits = runtime.checkpoints().limits();
            details.add(new RuntimeCapability(
                    "checkpoints", ProtocolVersion.V1_10,
                    available ? RuntimeCapability.Availability.AVAILABLE
                            : RuntimeCapability.Availability.UNAVAILABLE,
                    available ? Optional.empty() : Optional.of(registered
                            ? "dispatcher-not-registered" : "provider-not-registered"),
                    RuntimeCapability.Access.MUTATING,
                    List.of("AgentRuntime#checkpoints", "CheckpointRegistry#create",
                            "CheckpointRegistry#restore"),
                    List.of("checkpoints", "checkpoint-create", "checkpoint-restore"),
                    CHECKPOINT_TOOLS, Map.of(
                            "retainedCheckpoints",
                            (long) checkpointLimits.retainedCheckpoints(),
                            "retainedOperations",
                            (long) checkpointLimits.retainedOperations(),
                            "descriptionLength",
                            (long) checkpointLimits.descriptionLength()),
                    List.of("application-owned", "opaque-handles", "bounded", "at-most-once"),
                    List.of("command-dispatch", "execution-epochs")));
        }
        if (version.atLeast(11)) {
            boolean available = runtime.uiCorrelations().available();
            var uiLimits = runtime.uiCorrelations().limits();
            details.add(new RuntimeCapability(
                    "runtime-ui-correlation", ProtocolVersion.V1_11,
                    available ? RuntimeCapability.Availability.AVAILABLE
                            : RuntimeCapability.Availability.UNAVAILABLE,
                    available ? Optional.empty() : Optional.of("binding-not-registered"),
                    RuntimeCapability.Access.READ_ONLY,
                    List.of("AgentRuntime#uiCorrelations",
                            "UiCorrelationRegistry#runtimeToUi",
                            "UiCorrelationRegistry#uiToRuntime"),
                    List.of("ui-bindings", "ui-frames"), UI_CORRELATION_TOOLS, Map.of(
                            "registeredBindings", (long) uiLimits.registeredBindings(),
                            "queryResults", (long) uiLimits.queryResults(),
                            "retainedFrameCorrelations",
                            (long) uiLimits.retainedFrameCorrelations(),
                            "stringLength", (long) uiLimits.stringLength()),
                    List.of("application-provided", "bidirectional", "bounded",
                            "explicit-frame-mapping"),
                    List.of("execution-epochs", "explicit-correlation")));
        }
        if (version.atLeast(12)) {
            boolean available = runtime.commands().isPresent();
            var recordingLimits = runtime.recordings().limits();
            details.add(new RuntimeCapability(
                    "recording", ProtocolVersion.V1_12,
                    available ? RuntimeCapability.Availability.AVAILABLE
                            : RuntimeCapability.Availability.UNAVAILABLE,
                    available ? Optional.empty() : Optional.of("dispatcher-not-registered"),
                    RuntimeCapability.Access.MUTATING,
                    List.of("AgentRuntime#recordings", "RecordingRegistry#start",
                            "RecordingRegistry#stop", "RecordingRegistry#get"),
                    List.of("recording-start", "recording-stop", "recording-get"),
                    RECORDING_TOOLS, Map.of(
                            "retainedRecordings", (long) recordingLimits.retainedRecordings(),
                            "itemsPerRecording", (long) recordingLimits.itemsPerRecording(),
                            "maximumEncodedBytes", (long) recordingLimits.maximumEncodedBytes(),
                            "maximumDurationNanos", recordingLimits.maximumDurationNanos(),
                            "maximumTickSpan", recordingLimits.maximumTickSpan(),
                            "retainedOperations", (long) recordingLimits.retainedOperations(),
                            "chunkItems", (long) recordingLimits.chunkItems(),
                            "stringLength", (long) recordingLimits.stringLength()),
                    List.of("application-owned", "bounded", "immutable", "loss-explicit"),
                    List.of("command-dispatch", "execution-epochs")));
        }
        if (version.atLeast(13)) {
            boolean available = runtime.commands().isPresent()
                    && runtime.controls().available()
                    && runtime.scenarios().determinismAvailable();
            var determinismLimits = runtime.determinism().limits();
            details.add(new RuntimeCapability(
                    "determinism-comparison", ProtocolVersion.V1_13,
                    available ? RuntimeCapability.Availability.AVAILABLE
                            : RuntimeCapability.Availability.UNAVAILABLE,
                    available ? Optional.empty()
                            : Optional.of("deterministic-scenario-or-control-unavailable"),
                    RuntimeCapability.Access.MUTATING,
                    List.of("AgentRuntime#determinism", "DeterminismRegistry#check"),
                    List.of("determinism-check"), DETERMINISM_TOOLS, Map.of(
                            "retainedOperations", (long) determinismLimits.retainedOperations(),
                            "maximumRepeats", (long) determinismLimits.maximumRepeats(),
                            "maximumTicksPerRepeat",
                            (long) determinismLimits.maximumTicksPerRepeat(),
                            "maximumEntitiesPerFrame",
                            (long) determinismLimits.maximumEntitiesPerFrame(),
                            "maximumFactsPerFrame",
                            (long) determinismLimits.maximumFactsPerFrame(),
                            "maximumEncodedEvidenceBytes",
                            (long) determinismLimits.maximumEncodedEvidenceBytes(),
                            "maximumExecutionNanos",
                            determinismLimits.maximumExecutionNanos()),
                    List.of("application-owned", "bounded", "first-divergence", "inconclusive-safe"),
                    List.of("command-dispatch", "simulation-control", "execution-epochs")));
        }
        if (version.isV2()) {
            details.add(new RuntimeCapability(
                    "removed-entity-history", ProtocolVersion.V2,
                    RuntimeCapability.Availability.AVAILABLE, Optional.empty(),
                    RuntimeCapability.Access.READ_ONLY,
                    List.of("AgentRuntime#entityHistory"),
                    List.of("entityHistory"), V2_TOOLS, Map.of(
                            "queryResults", (long) limits.queryResults(),
                            "retainedFrames", (long) limits.retainedFrames()),
                    List.of("removed-entities", "retained-final-state", "version-pagination"),
                    List.of("entities", "frames")));
        }
        return List.copyOf(details);
    }

    private static List<String> toolsFor(AgentRuntime runtime, ProtocolVersion version) {
        Stream<String> tools = BASE_TOOLS.stream();
        if (version.atLeast(3)) {
            tools = Stream.concat(tools, EPOCH_TOOLS.stream());
        }
        if (runtime.commands().isPresent() && version.atLeast(2)) {
            tools = Stream.concat(tools, COMMAND_TOOLS.stream());
        }
        if (version.atLeast(4) && !runtime.scenarios().list().isEmpty()) {
            tools = Stream.concat(tools, SCENARIO_TOOLS.stream());
        }
        if (version.atLeast(5)) {
            tools = Stream.concat(tools, ATTRIBUTION_TOOLS.stream());
        }
        if (version.atLeast(6) && !runtime.actions().list().isEmpty()) {
            tools = Stream.concat(tools, ACTION_TOOLS.stream());
        }
        if (version.atLeast(7)) {
            tools = Stream.concat(tools, ASSERTION_TOOLS.stream());
        }
        if (version.atLeast(8) && runtime.controls().available()) {
            tools = Stream.concat(tools, CONTROL_TOOLS.stream());
        }
        if (version.atLeast(9) && !runtime.inputs().list().isEmpty()) {
            tools = Stream.concat(tools, INPUT_TOOLS.stream());
        }
        if (version.atLeast(10) && runtime.checkpoints().available()) {
            tools = Stream.concat(tools, CHECKPOINT_TOOLS.stream());
        }
        if (version.atLeast(11) && runtime.uiCorrelations().available()) {
            tools = Stream.concat(tools, UI_CORRELATION_TOOLS.stream());
        }
        if (version.atLeast(12) && runtime.commands().isPresent()) {
            tools = Stream.concat(tools, RECORDING_TOOLS.stream());
        }
        if (version.atLeast(13) && runtime.commands().isPresent()
                && runtime.controls().available()
                && runtime.scenarios().determinismAvailable()) {
            tools = Stream.concat(tools, DETERMINISM_TOOLS.stream());
        }
        if (version.isV2()) {
            tools = Stream.concat(tools, V2_TOOLS.stream());
        }
        return tools.toList();
    }

    private static RuntimeResponse.Result assertion(
            AgentRuntime runtime, RuntimeCommand.Assert command) {
        return new RuntimeResponse.Result.Assertion(runtime.assertions().evaluate(
                command.assertion(),
                new io.github.teemuki8.libgdx.agent.runtime.core.AssertionScope(
                        new ExecutionEpochId(command.executionEpochId()),
                        range(command.fromFrame(), command.toFrame()), command.evidenceLimit())));
    }

    private static RuntimeResponse.Result control(
            AgentRuntime runtime, RuntimeCommand.Control command) {
        if (command.action() == RuntimeCommand.ControlAction.STATUS) {
            return new RuntimeResponse.Result.Control(
                    runtime.controls().descriptor(), Optional.empty());
        }
        requireControl(runtime, command.timeoutNanos());
        boolean pause = command.action() == RuntimeCommand.ControlAction.PAUSE;
        return new RuntimeResponse.Result.Control(runtime.controls().descriptor(), Optional.of(
                runtime.controls().control(pause, command.controlRequestId(),
                        Duration.ofNanos(command.timeoutNanos()))));
    }

    private static RuntimeResponse.Result advance(
            AgentRuntime runtime, RuntimeCommand.Advance command) {
        requireControl(runtime, command.timeoutNanos());
        return new RuntimeResponse.Result.Control(runtime.controls().descriptor(), Optional.of(
                runtime.controls().advance(command.controlRequestId(), command.ticks(),
                        command.deltaNanos(), Duration.ofNanos(command.timeoutNanos()))));
    }

    private static RuntimeResponse.Result waitFor(
            AgentRuntime runtime, RuntimeCommand.Wait command) {
        requireControl(runtime, command.timeoutNanos());
        io.github.teemuki8.libgdx.agent.runtime.core.ControlOperation operation =
                command.conditionId() != null
                        ? runtime.controls().waitForCondition(
                                command.controlRequestId(), command.conditionId(),
                                command.maximumTicks(), command.deltaNanos(),
                                Duration.ofNanos(command.timeoutNanos()))
                        : runtime.controls().waitForAssertion(
                                command.controlRequestId(), command.assertion(),
                                command.maximumTicks(), command.deltaNanos(),
                                command.evidenceLimit(), Duration.ofNanos(command.timeoutNanos()));
        return new RuntimeResponse.Result.Control(
                runtime.controls().descriptor(), Optional.of(operation));
    }

    private static RuntimeResponse.Result input(
            AgentRuntime runtime, RuntimeCommand.Input command, ProtocolVersion version) {
        if (runtime.inputs().list().isEmpty()) {
            throw new ProtocolFailure(ProtocolErrorCode.CAPABILITY_UNAVAILABLE,
                    "input registry is unavailable",
                    Map.of("sessionId", runtime.sessionId().value(),
                            "capability", "registered-inputs"));
        }
        if (runtime.commands().isEmpty()) {
            throw capabilityUnavailable(runtime);
        }
        if (!runtime.controls().available()) {
            throw new ProtocolFailure(ProtocolErrorCode.CAPABILITY_UNAVAILABLE,
                    "simulation controller is unavailable",
                    Map.of("sessionId", runtime.sessionId().value(),
                            "capability", "registered-inputs"));
        }
        java.util.OptionalLong target = command.targetTick() == null
                ? java.util.OptionalLong.empty()
                : java.util.OptionalLong.of(command.targetTick());
        io.github.teemuki8.libgdx.agent.runtime.core.InputInjection injection = runtime.inputs()
                .inject(command.inputId(), command.inputRequestId(), command.parameters(), target,
                        Duration.ofNanos(command.timeoutNanos()));
        return new RuntimeResponse.Result.Input(injection,
                evidence(version, injection.applicationFailure()));
    }

    private static RuntimeResponse.Result checkpointCreate(
            AgentRuntime runtime, RuntimeCommand.CheckpointCreate command,
            ProtocolVersion version) {
        requireCheckpoints(runtime);
        io.github.teemuki8.libgdx.agent.runtime.core.CheckpointOperation operation =
                runtime.checkpoints().create(
                        command.checkpointId(), command.description(),
                        command.checkpointRequestId(),
                        Duration.ofNanos(command.timeoutNanos()));
        return new RuntimeResponse.Result.Checkpoint(operation,
                evidence(version, operation.applicationFailure()));
    }

    private static RuntimeResponse.Result checkpointRestore(
            AgentRuntime runtime, RuntimeCommand.CheckpointRestore command,
            ProtocolVersion version) {
        requireCheckpoints(runtime);
        io.github.teemuki8.libgdx.agent.runtime.core.CheckpointOperation operation =
                runtime.checkpoints().restore(
                        command.checkpointId(), command.checkpointRequestId(),
                        Duration.ofNanos(command.timeoutNanos()));
        return new RuntimeResponse.Result.Checkpoint(operation,
                evidence(version, operation.applicationFailure()));
    }

    private static void requireCheckpoints(AgentRuntime runtime) {
        if (!runtime.checkpoints().available()) {
            throw new ProtocolFailure(ProtocolErrorCode.CAPABILITY_UNAVAILABLE,
                    "checkpoint provider is unavailable",
                    Map.of("sessionId", runtime.sessionId().value(),
                            "capability", "checkpoints"));
        }
        if (runtime.commands().isEmpty()) {
            throw capabilityUnavailable(runtime);
        }
    }

    private static RuntimeResponse.Result uiBindings(
            AgentRuntime runtime, RuntimeCommand.UiBindings command) {
        requireUiCorrelations(runtime);
        java.util.Optional<String> generation =
                java.util.Optional.ofNullable(command.uiGeneration());
        io.github.teemuki8.libgdx.agent.runtime.core.UiBindingResult result =
                command.entityId() != null
                        ? runtime.uiCorrelations().runtimeToUi(
                                EntityId.of(command.entityId()),
                                java.util.Optional.ofNullable(command.property()),
                                new ExecutionEpochId(command.executionEpochId()),
                                new FrameId(command.runtimeFrameId()), generation, command.limit())
                        : runtime.uiCorrelations().uiToRuntime(
                                command.uiSessionId(), command.uiControlId(),
                                new ExecutionEpochId(command.executionEpochId()),
                                new FrameId(command.runtimeFrameId()), generation, command.limit());
        return new RuntimeResponse.Result.UiBindings(result);
    }

    private static RuntimeResponse.Result uiFrames(
            AgentRuntime runtime, RuntimeCommand.UiFrames command) {
        requireUiCorrelations(runtime);
        return new RuntimeResponse.Result.UiFrames(command.uiSessionId() != null
                ? runtime.uiCorrelations().framesForUiSession(
                        command.uiSessionId(), command.limit())
                : runtime.uiCorrelations().framesForToken(
                        command.correlationToken(), command.limit()));
    }

    private static void requireUiCorrelations(AgentRuntime runtime) {
        if (!runtime.uiCorrelations().available()) {
            throw new ProtocolFailure(ProtocolErrorCode.CAPABILITY_UNAVAILABLE,
                    "runtime/UI correlation is unavailable",
                    Map.of("sessionId", runtime.sessionId().value(),
                            "capability", "runtime-ui-correlation"));
        }
    }

    private static RuntimeResponse.Result determinism(
            AgentRuntime runtime, RuntimeCommand.DeterminismCheck command,
            ProtocolVersion version) {
        requireControl(runtime, command.timeoutNanos());
        if (runtime.commands().isEmpty() || !runtime.scenarios().determinismAvailable()) {
            throw new ProtocolFailure(ProtocolErrorCode.CAPABILITY_UNAVAILABLE,
                    "determinism comparison is unavailable",
                    Map.of("sessionId", runtime.sessionId().value(),
                            "capability", "determinism-comparison"));
        }
        var spec = new io.github.teemuki8.libgdx.agent.runtime.core.DeterminismSpec(
                command.scenarioId(), command.randomSeed(), command.configuration(),
                command.repeatCount(), command.ticksPerRepeat(), command.deltaNanos(),
                command.profile());
        io.github.teemuki8.libgdx.agent.runtime.core.DeterminismOperation operation =
                runtime.determinism().check(
                        spec, command.determinismRequestId(),
                        Duration.ofNanos(command.timeoutNanos()));
        return new RuntimeResponse.Result.Determinism(operation,
                evidence(version, operation.result().flatMap(
                        io.github.teemuki8.libgdx.agent.runtime.core.DeterminismResult
                                ::applicationFailure)));
    }

    private static RuntimeResponse.Result recordingStart(
            AgentRuntime runtime, RuntimeCommand.RecordingStart command) {
        requireRecordingDispatch(runtime, command.timeoutNanos());
        List<io.github.teemuki8.libgdx.agent.runtime.core.RecordingCapabilityVersion> versions =
                capabilityDetails(runtime, ProtocolVersion.V1_12).stream()
                        .map(capability ->
                                new io.github.teemuki8.libgdx.agent.runtime.core
                                        .RecordingCapabilityVersion(
                                                capability.id(),
                                                capability.capabilityVersion().major() + "."
                                                        + capability.capabilityVersion().minor()))
                        .toList();
        var spec = new io.github.teemuki8.libgdx.agent.runtime.core.RecordingSpec(
                command.recordingId(), "1.12", versions,
                Optional.ofNullable(command.scenarioId()),
                Optional.ofNullable(command.checkpointId()),
                command.randomSeed() == null ? java.util.OptionalLong.empty()
                        : java.util.OptionalLong.of(command.randomSeed()),
                command.configuration(),
                command.replayGuaranteed());
        return new RuntimeResponse.Result.RecordingOperationResult(
                runtime.recordings().start(spec, command.recordingRequestId(),
                        Duration.ofNanos(command.timeoutNanos())));
    }

    private static RuntimeResponse.Result recordingStop(
            AgentRuntime runtime, RuntimeCommand.RecordingStop command) {
        requireRecordingDispatch(runtime, command.timeoutNanos());
        return new RuntimeResponse.Result.RecordingOperationResult(
                runtime.recordings().stop(command.recordingId(),
                        command.recordingRequestId(), Duration.ofNanos(command.timeoutNanos())));
    }

    private static void requireRecordingDispatch(AgentRuntime runtime, long timeoutNanos) {
        if (runtime.commands().isEmpty()) {
            throw capabilityUnavailable(runtime);
        }
        long maximum = runtime.commands().orElseThrow().limits().maximumTimeoutNanos();
        if (timeoutNanos > maximum) {
            throw new ProtocolFailure(ProtocolErrorCode.LIMIT_EXCEEDED,
                    "recording timeout exceeds the configured limit",
                    Map.of("maximumTimeoutNanos", Long.toString(maximum)));
        }
    }

    private static void requireControl(AgentRuntime runtime, long timeoutNanos) {
        if (!runtime.controls().available()) {
            throw new ProtocolFailure(ProtocolErrorCode.CAPABILITY_UNAVAILABLE,
                    "simulation controller is unavailable",
                    Map.of("sessionId", runtime.sessionId().value(),
                            "capability", "simulation-control"));
        }
        if (runtime.commands().isEmpty()) {
            throw capabilityUnavailable(runtime);
        }
        long maximum = runtime.commands().orElseThrow().limits().maximumTimeoutNanos();
        if (timeoutNanos > maximum) {
            throw new ProtocolFailure(ProtocolErrorCode.LIMIT_EXCEEDED,
                    "control timeout exceeds the configured limit",
                    Map.of("maximumTimeoutNanos", Long.toString(maximum)));
        }
    }

    private static RuntimeResponse.Result epochFrames(
            AgentRuntime runtime, RuntimeCommand.EpochFrames command) {
        ExecutionEpochId epochId = new ExecutionEpochId(command.executionEpochId());
        if (epochId.compareTo(runtime.currentEpoch()) > 0) {
            throw new ProtocolFailure(ProtocolErrorCode.INVALID_QUERY,
                    "execution epoch does not exist", Map.of(
                            "executionEpochId", Long.toString(command.executionEpochId())));
        }
        return new RuntimeResponse.Result.EpochFrames(runtime.frames(epochId, command.limit()));
    }

    private static RuntimeResponse.Result reset(
            AgentRuntime runtime, RuntimeCommand.Reset command) {
        if (runtime.commands().isEmpty()) {
            throw capabilityUnavailable(runtime);
        }
        long maximum = runtime.commands().orElseThrow().limits().maximumTimeoutNanos();
        if (command.timeoutNanos() > maximum) {
            throw new ProtocolFailure(ProtocolErrorCode.LIMIT_EXCEEDED,
                    "reset timeout exceeds the configured limit",
                    Map.of("maximumTimeoutNanos", Long.toString(maximum)));
        }
        try {
            return new RuntimeResponse.Result.Reset(runtime.scenarios().reset(
                    command.scenarioId(), command.resetRequestId(),
                    Duration.ofNanos(command.timeoutNanos())));
        } catch (IllegalArgumentException failure) {
            throw new ProtocolFailure(ProtocolErrorCode.INVALID_QUERY,
                    failure.getMessage(), Map.of("scenarioId", command.scenarioId()));
        }
    }

    private static RuntimeResponse.Result action(
            AgentRuntime runtime, RuntimeCommand.Action command) {
        if (runtime.commands().isEmpty()) {
            throw capabilityUnavailable(runtime);
        }
        long maximum = runtime.commands().orElseThrow().limits().maximumTimeoutNanos();
        if (command.timeoutNanos() > maximum) {
            throw new ProtocolFailure(ProtocolErrorCode.LIMIT_EXCEEDED,
                    "action timeout exceeds the configured limit",
                    Map.of("maximumTimeoutNanos", Long.toString(maximum)));
        }
        try {
            return new RuntimeResponse.Result.Action(runtime.actions().invoke(
                    command.actionId(), command.actionRequestId(), command.parameters(),
                    optional(command.correlationId()), Duration.ofNanos(command.timeoutNanos())));
        } catch (AgentRuntimeException failure) {
            if (failure.code() == io.github.teemuki8.libgdx.agent.runtime.core.RuntimeErrorCode
                    .LIMIT_EXCEEDED) {
                throw new ProtocolFailure(ProtocolErrorCode.LIMIT_EXCEEDED,
                        failure.getMessage(), Map.of());
            }
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw new ProtocolFailure(ProtocolErrorCode.INVALID_QUERY,
                    failure.getMessage(), Map.of("actionId", command.actionId()));
        }
    }

    private static ProtocolFailure capabilityUnavailable(AgentRuntime runtime) {
        return new ProtocolFailure(ProtocolErrorCode.CAPABILITY_UNAVAILABLE,
                "application command dispatch is unavailable", Map.of(
                        "sessionId", runtime.sessionId().value(),
                        "capability", "command-dispatch"));
    }

    private static RuntimeCapability capability(
            AgentRuntime runtime,
            String id,
            List<String> javaApis,
            List<String> commands,
            List<String> tools,
            Map<String, Long> limits,
            List<String> modes,
            List<String> requirements) {
        boolean available = runtime.configuration().enabled();
        return new RuntimeCapability(
                id, ProtocolVersion.V1,
                available ? RuntimeCapability.Availability.AVAILABLE
                        : RuntimeCapability.Availability.UNAVAILABLE,
                available ? Optional.empty() : Optional.of("runtime-disabled"),
                RuntimeCapability.Access.READ_ONLY, javaApis, commands, tools, limits, modes,
                requirements);
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
                source.sessionId(), source.frameId(), source.executionEpochId(),
                source.baselineKind(), source.monotonicTimeNanos(),
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

    private static RuntimeResponse.Result commandStatus(
            AgentRuntime runtime, RuntimeCommand.CommandStatus command, ProtocolVersion version) {
        io.github.teemuki8.libgdx.agent.runtime.core.CommandLookup lookup =
                runtime.commands().orElseThrow(() -> capabilityUnavailable(runtime))
                        .status(command.commandRequestId());
        return new RuntimeResponse.Result.CommandStatus(lookup,
                evidence(version, lookup.status().flatMap(
                        io.github.teemuki8.libgdx.agent.runtime.core.CommandStatus
                                ::applicationFailure)));
    }

    private static Optional<ApplicationFailureEvidence> evidence(
            ProtocolVersion version, Optional<ApplicationFailureEvidence> nested) {
        return version.isV2() ? nested : Optional.empty();
    }

    private static RuntimeResponse.Result entityHistory(
            AgentRuntime runtime, RuntimeCommand.EntityHistory command) {
        EntityId id = EntityId.of(command.entityId());
        FrameRange range = range(command.fromFrame(), command.toFrame());
        return new RuntimeResponse.Result.EntityHistory(runtime.entityHistory(
                id, range, command.versionOffset(), command.versionLimit()));
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

    private static RuntimeResponse.Result attributedChanges(
            AgentRuntime runtime, RuntimeCommand.AttributedChanges command) {
        return new RuntimeResponse.Result.Changes(runtime.changes(new ChangeQuery(
                range(command.fromFrame(), command.toFrame()),
                optional(command.entityId()).map(EntityId::of),
                optional(command.entityType()).map(EntityType::of), optional(command.property()),
                optional(command.sourceSubsystem()), optional(command.correlationId()),
                command.limit())));
    }

    private static RuntimeResponse.Result attributedEvents(
            AgentRuntime runtime, RuntimeCommand.AttributedEvents command) {
        return new RuntimeResponse.Result.Events(runtime.events(new EventQuery(
                range(command.fromFrame(), command.toFrame()), optional(command.eventType()),
                command.eventTypePrefix(), optional(command.subject()).map(EntityId::of),
                optional(command.source()).map(EntityId::of), optional(command.sourceSubsystem()),
                optional(command.correlationId()), command.limit())));
    }

    private static RuntimeResponse.Result attributedDecisions(
            AgentRuntime runtime, RuntimeCommand.AttributedDecisions command) {
        return new RuntimeResponse.Result.Decisions(runtime.decisions(new DecisionQuery(
                range(command.fromFrame(), command.toFrame()),
                optional(command.decisionType()).map(DecisionType::of),
                optional(command.actor()).map(EntityId::of),
                optional(command.chosenCandidate()).map(EntityId::of),
                optional(command.reasonCode()), optional(command.sourceSubsystem()),
                optional(command.correlationId()), command.limit())));
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
        ProtocolVersion responseVersion;
        if (SUPPORTED_VERSIONS.contains(request.version())) {
            responseVersion = request.version();
        } else if (request.version().major() == 1) {
            responseVersion = ProtocolVersion.V1_13;
        } else {
            responseVersion = ProtocolVersion.CURRENT;
        }
        return new RuntimeResponse.Failure(
                responseVersion, request.requestId(), new ProtocolError(code, message, details));
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
