package io.github.teemuki8.libgdx.agent.runtime.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.teemuki8.libgdx.agent.runtime.core.ApplicationFailureEvidence;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionTrace;
import io.github.teemuki8.libgdx.agent.runtime.core.CommandLookup;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointDescriptor;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointOperation;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityHistory;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityHistoryPage;
import io.github.teemuki8.libgdx.agent.runtime.core.EpochFramePage;
import io.github.teemuki8.libgdx.agent.runtime.core.EntitySnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameSnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameSummary;
import io.github.teemuki8.libgdx.agent.runtime.core.PropertyChange;
import io.github.teemuki8.libgdx.agent.runtime.core.QueryPage;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeEvent;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeLimits;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.ScenarioDescriptor;
import io.github.teemuki8.libgdx.agent.runtime.core.ScenarioReset;
import io.github.teemuki8.libgdx.agent.runtime.core.ActionDescriptor;
import io.github.teemuki8.libgdx.agent.runtime.core.ActionInvocation;
import io.github.teemuki8.libgdx.agent.runtime.core.AssertionResult;
import io.github.teemuki8.libgdx.agent.runtime.core.ControlOperation;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationControlDescriptor;
import io.github.teemuki8.libgdx.agent.runtime.core.UiBindingResult;
import io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelationPage;
import io.github.teemuki8.libgdx.agent.runtime.core.InputDescriptor;
import io.github.teemuki8.libgdx.agent.runtime.core.InputInjection;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingChunk;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingOperation;
import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismOperation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Explicit correlated success/error response union. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "status")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RuntimeResponse.Success.class, name = "ok"),
    @JsonSubTypes.Type(value = RuntimeResponse.Failure.class, name = "error")
})
public sealed interface RuntimeResponse permits RuntimeResponse.Success, RuntimeResponse.Failure {
    /** Response protocol version. */
    ProtocolVersion version();

    /** Request correlation ID. */
    String requestId();

    /** Successful response. */
    record Success(ProtocolVersion version, String requestId, Result result)
            implements RuntimeResponse {
        /** Validates response. */
        public Success {
            Objects.requireNonNull(version, "version");
            ProtocolJson.requireIdentifier(requestId, "requestId");
            Objects.requireNonNull(result, "result");
        }
    }

    /** Failed response. */
    record Failure(ProtocolVersion version, String requestId, ProtocolError error)
            implements RuntimeResponse {
        /** Validates response. */
        public Failure {
            Objects.requireNonNull(version, "version");
            ProtocolJson.requireIdentifier(requestId, "requestId");
            Objects.requireNonNull(error, "error");
        }
    }

    /** Explicit result union for base queries and registered command-dispatch operations. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Result.Sessions.class, name = "sessions"),
        @JsonSubTypes.Type(value = Result.Capabilities.class, name = "capabilities"),
        @JsonSubTypes.Type(value = Result.Frames.class, name = "frames"),
        @JsonSubTypes.Type(value = Result.Snapshot.class, name = "snapshot"),
        @JsonSubTypes.Type(value = Result.Entity.class, name = "entity"),
        @JsonSubTypes.Type(value = Result.Changes.class, name = "changes"),
        @JsonSubTypes.Type(value = Result.Events.class, name = "events"),
        @JsonSubTypes.Type(value = Result.Decisions.class, name = "decisions"),
        @JsonSubTypes.Type(value = Result.CommandStatus.class, name = "commandStatus"),
        @JsonSubTypes.Type(value = Result.CommandCancellation.class, name = "commandCancellation"),
        @JsonSubTypes.Type(value = Result.EpochFrames.class, name = "epochFrames"),
        @JsonSubTypes.Type(value = Result.Scenarios.class, name = "scenarios"),
        @JsonSubTypes.Type(value = Result.Reset.class, name = "reset"),
        @JsonSubTypes.Type(value = Result.Actions.class, name = "actions"),
        @JsonSubTypes.Type(value = Result.Action.class, name = "action"),
        @JsonSubTypes.Type(value = Result.Assertion.class, name = "assertion"),
        @JsonSubTypes.Type(value = Result.Control.class, name = "control"),
        @JsonSubTypes.Type(value = Result.Inputs.class, name = "inputs"),
        @JsonSubTypes.Type(value = Result.Input.class, name = "input"),
        @JsonSubTypes.Type(value = Result.Checkpoints.class, name = "checkpoints"),
        @JsonSubTypes.Type(value = Result.Checkpoint.class, name = "checkpoint"),
        @JsonSubTypes.Type(value = Result.UiBindings.class, name = "uiBindings"),
        @JsonSubTypes.Type(value = Result.UiFrames.class, name = "uiFrames"),
        @JsonSubTypes.Type(value = Result.RecordingOperationResult.class,
                name = "recordingOperation"),
        @JsonSubTypes.Type(value = Result.RecordingChunkResult.class, name = "recordingChunk"),
        @JsonSubTypes.Type(value = Result.Determinism.class, name = "determinism"),
        @JsonSubTypes.Type(value = Result.EntityHistory.class, name = "entityHistory")
    })
    sealed interface Result permits Result.Sessions, Result.Capabilities, Result.Frames,
            Result.Snapshot, Result.Entity, Result.Changes, Result.Events, Result.Decisions,
            Result.CommandStatus, Result.CommandCancellation, Result.EpochFrames,
            Result.Scenarios, Result.Reset, Result.Actions, Result.Action, Result.Assertion,
            Result.Control, Result.Inputs, Result.Input, Result.Checkpoints, Result.Checkpoint,
            Result.UiBindings, Result.UiFrames, Result.RecordingOperationResult,
            Result.RecordingChunkResult, Result.Determinism, Result.EntityHistory {
        /** Published session catalog. */
        record Sessions(List<SessionInfo> sessions) implements Result {
            /** Copies sessions. */
            public Sessions {
                sessions = List.copyOf(sessions);
                if (sessions.size() > ProtocolJson.MAX_RESULT_ITEMS) {
                    throw new IllegalArgumentException("too many protocol sessions");
                }
            }
        }

        /** Session capabilities and limits. */
        record Capabilities(
                ProtocolVersion protocolVersion,
                List<String> supportedTools,
                List<String> enabledFeatures,
                RuntimeLimits limits,
                Optional<Long> currentFrame,
                RuntimeStatus runtimeStatus,
                @JsonInclude(JsonInclude.Include.NON_ABSENT)
                Optional<CapabilityReport> capabilityReport) implements Result {
            /** Creates a frozen V1.0 capabilities result. */
            public Capabilities(
                    ProtocolVersion protocolVersion,
                    List<String> supportedTools,
                    List<String> enabledFeatures,
                    RuntimeLimits limits,
                    Optional<Long> currentFrame,
                    RuntimeStatus runtimeStatus) {
                this(protocolVersion, supportedTools, enabledFeatures, limits, currentFrame,
                        runtimeStatus, Optional.empty());
            }

            /** Copies values. */
            public Capabilities {
                Objects.requireNonNull(protocolVersion, "protocolVersion");
                supportedTools = List.copyOf(supportedTools);
                enabledFeatures = List.copyOf(enabledFeatures);
                Objects.requireNonNull(limits, "limits");
                currentFrame = Objects.requireNonNull(currentFrame, "currentFrame");
                Objects.requireNonNull(runtimeStatus, "runtimeStatus");
                capabilityReport = Objects.requireNonNull(capabilityReport, "capabilityReport");
            }
        }

        /** Bounded frame summaries. */
        record Frames(QueryPage<FrameSummary> page) implements Result {}

        /** One filtered immutable completed frame. */
        record Snapshot(FrameSnapshot snapshot, boolean filtered, boolean hasMore) implements Result {}

        /** Latest and historical entity evidence. */
        record Entity(EntitySnapshot latest,
                io.github.teemuki8.libgdx.agent.runtime.core.EntityHistory history)
                implements Result {}

        /** Paginated retained history page for one entity, including removed entities. */
        record EntityHistory(EntityHistoryPage page) implements Result {
            public EntityHistory {
                Objects.requireNonNull(page, "page");
            }
        }

        /** Bounded change results. */
        record Changes(QueryPage<PropertyChange> page) implements Result {}

        /** Bounded event results. */
        record Events(QueryPage<RuntimeEvent> page) implements Result {}

        /** Bounded decision results. */
        record Decisions(QueryPage<DecisionTrace> page) implements Result {}

        /** Retained, expired, or unknown application command status. */
        record CommandStatus(CommandLookup command,
                @JsonInclude(JsonInclude.Include.NON_ABSENT)
                Optional<ApplicationFailureEvidence> applicationFailure) implements Result {
            /** Creates a protocol-1.x command status without structured evidence. */
            public CommandStatus(CommandLookup command) {
                this(command, Optional.empty());
            }

            public CommandStatus {
                Objects.requireNonNull(command, "command");
                applicationFailure = Objects.requireNonNull(
                        applicationFailure, "applicationFailure");
            }
        }

        /** Result of a cancellation attempt. */
        record CommandCancellation(
                io.github.teemuki8.libgdx.agent.runtime.core.CommandCancellation cancellation)
                implements Result {
            public CommandCancellation {
                Objects.requireNonNull(cancellation, "cancellation");
            }
        }

        /** Completed frame summaries for one execution epoch. */
        record EpochFrames(EpochFramePage page) implements Result {
            public EpochFrames {
                Objects.requireNonNull(page, "page");
            }
        }

        /** Stable metadata for registered scenarios. */
        record Scenarios(List<ScenarioDescriptor> scenarios) implements Result {
            public Scenarios {
                scenarios = List.copyOf(scenarios);
                if (scenarios.size() > ProtocolJson.MAX_RESULT_ITEMS) {
                    throw new IllegalArgumentException("too many protocol scenarios");
                }
            }
        }

        /** Correlated reset status and optional completed baseline evidence. */
        record Reset(ScenarioReset reset) implements Result {
            public Reset {
                Objects.requireNonNull(reset, "reset");
            }
        }

        /** Stable action metadata and closed parameter schemas. */
        record Actions(List<ActionDescriptor> actions) implements Result {
            public Actions {
                actions = List.copyOf(actions);
                if (actions.size() > ProtocolJson.MAX_RESULT_ITEMS) {
                    throw new IllegalArgumentException("too many protocol actions");
                }
            }
        }

        /** Correlated semantic-action outcome and frame evidence. */
        record Action(ActionInvocation invocation) implements Result {
            public Action {
                Objects.requireNonNull(invocation, "invocation");
            }
        }

        /** Deterministic bounded declarative assertion outcome. */
        record Assertion(AssertionResult result) implements Result {
            public Assertion {
                Objects.requireNonNull(result, "result");
            }
        }

        /** Discoverable control state and optional correlated mutation outcome. */
        record Control(SimulationControlDescriptor descriptor,
                Optional<ControlOperation> operation) implements Result {
            public Control {
                Objects.requireNonNull(descriptor, "descriptor");
                operation = Objects.requireNonNull(operation, "operation");
            }
        }

        /** Explicitly registered input catalog. */
        record Inputs(List<InputDescriptor> inputs) implements Result {
            public Inputs {
                inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
                if (inputs.size() > ProtocolJson.MAX_RESULT_ITEMS) {
                    throw new IllegalArgumentException("too many protocol inputs");
                }
            }
        }

        /** Correlated controlled-tick input scheduling and execution evidence. */
        record Input(InputInjection injection,
                @JsonInclude(JsonInclude.Include.NON_ABSENT)
                Optional<ApplicationFailureEvidence> applicationFailure) implements Result {
            /** Creates a protocol-1.x input result without structured evidence. */
            public Input(InputInjection injection) {
                this(injection, Optional.empty());
            }

            public Input {
                Objects.requireNonNull(injection, "injection");
                applicationFailure = Objects.requireNonNull(
                        applicationFailure, "applicationFailure");
            }
        }

        /** Retained checkpoint descriptors without opaque application handles. */
        record Checkpoints(List<CheckpointDescriptor> checkpoints) implements Result {
            public Checkpoints {
                checkpoints = List.copyOf(Objects.requireNonNull(checkpoints, "checkpoints"));
                if (checkpoints.size() > ProtocolJson.MAX_RESULT_ITEMS) {
                    throw new IllegalArgumentException("too many protocol checkpoints");
                }
            }
        }

        /** Correlated checkpoint mutation and optional baseline evidence. */
        record Checkpoint(CheckpointOperation operation,
                @JsonInclude(JsonInclude.Include.NON_ABSENT)
                Optional<ApplicationFailureEvidence> applicationFailure) implements Result {
            /** Creates a protocol-1.x checkpoint result without structured evidence. */
            public Checkpoint(CheckpointOperation operation) {
                this(operation, Optional.empty());
            }

            public Checkpoint {
                Objects.requireNonNull(operation, "operation");
                applicationFailure = Objects.requireNonNull(
                        applicationFailure, "applicationFailure");
            }
        }

        /** Explicit runtime/UI binding lookup outcome and truncation evidence. */
        record UiBindings(UiBindingResult result) implements Result {
            public UiBindings {
                Objects.requireNonNull(result, "result");
            }
        }

        /** Bounded cross-system frame mappings with retention evidence. */
        record UiFrames(UiFrameCorrelationPage page) implements Result {
            public UiFrames {
                Objects.requireNonNull(page, "page");
            }
        }

        /** Recording lifecycle command evidence. */
        record RecordingOperationResult(RecordingOperation operation) implements Result {
            public RecordingOperationResult {
                Objects.requireNonNull(operation, "operation");
            }
        }

        /** One bounded recording manifest chunk. */
        record RecordingChunkResult(RecordingChunk chunk) implements Result {
            public RecordingChunkResult {
                Objects.requireNonNull(chunk, "chunk");
            }
        }

        /** Bounded repeated-scenario determinism operation evidence. */
        record Determinism(DeterminismOperation operation,
                @JsonInclude(JsonInclude.Include.NON_ABSENT)
                Optional<ApplicationFailureEvidence> applicationFailure) implements Result {
            /** Creates a protocol-1.x determinism result without structured evidence. */
            public Determinism(DeterminismOperation operation) {
                this(operation, Optional.empty());
            }

            public Determinism {
                Objects.requireNonNull(operation, "operation");
                applicationFailure = Objects.requireNonNull(
                        applicationFailure, "applicationFailure");
            }
        }
    }

    /** Minimal session metadata. */
    record SessionInfo(String sessionId, RuntimeStatus status, Optional<Long> currentFrame) {
        /** Validates metadata. */
        public SessionInfo {
            ProtocolJson.requireIdentifier(sessionId, "sessionId");
            Objects.requireNonNull(status, "status");
            currentFrame = Objects.requireNonNull(currentFrame, "currentFrame");
        }
    }
}
