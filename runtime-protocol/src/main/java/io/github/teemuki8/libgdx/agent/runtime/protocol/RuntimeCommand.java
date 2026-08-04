package io.github.teemuki8.libgdx.agent.runtime.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Explicit allowlisted V1 command union. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RuntimeCommand.Sessions.class, name = "sessions"),
    @JsonSubTypes.Type(value = RuntimeCommand.Capabilities.class, name = "capabilities"),
    @JsonSubTypes.Type(value = RuntimeCommand.Frames.class, name = "frames"),
    @JsonSubTypes.Type(value = RuntimeCommand.Snapshot.class, name = "snapshot"),
    @JsonSubTypes.Type(value = RuntimeCommand.Entity.class, name = "entity"),
    @JsonSubTypes.Type(value = RuntimeCommand.Changes.class, name = "changes"),
    @JsonSubTypes.Type(value = RuntimeCommand.Events.class, name = "events"),
    @JsonSubTypes.Type(value = RuntimeCommand.Decisions.class, name = "decisions"),
    @JsonSubTypes.Type(value = RuntimeCommand.CommandStatus.class, name = "commandStatus"),
    @JsonSubTypes.Type(value = RuntimeCommand.CommandCancel.class, name = "commandCancel"),
    @JsonSubTypes.Type(value = RuntimeCommand.EpochFrames.class, name = "epochFrames"),
    @JsonSubTypes.Type(value = RuntimeCommand.Scenarios.class, name = "scenarios"),
    @JsonSubTypes.Type(value = RuntimeCommand.Reset.class, name = "reset"),
    @JsonSubTypes.Type(value = RuntimeCommand.AttributedChanges.class, name = "attributedChanges"),
    @JsonSubTypes.Type(value = RuntimeCommand.AttributedEvents.class, name = "attributedEvents"),
    @JsonSubTypes.Type(value = RuntimeCommand.AttributedDecisions.class, name = "attributedDecisions"),
    @JsonSubTypes.Type(value = RuntimeCommand.Actions.class, name = "actions"),
    @JsonSubTypes.Type(value = RuntimeCommand.Action.class, name = "action"),
    @JsonSubTypes.Type(value = RuntimeCommand.Assert.class, name = "assert"),
    @JsonSubTypes.Type(value = RuntimeCommand.Control.class, name = "control"),
    @JsonSubTypes.Type(value = RuntimeCommand.Advance.class, name = "advance"),
    @JsonSubTypes.Type(value = RuntimeCommand.Wait.class, name = "wait"),
    @JsonSubTypes.Type(value = RuntimeCommand.Inputs.class, name = "inputs"),
    @JsonSubTypes.Type(value = RuntimeCommand.Input.class, name = "input"),
    @JsonSubTypes.Type(value = RuntimeCommand.Checkpoints.class, name = "checkpoints"),
    @JsonSubTypes.Type(value = RuntimeCommand.CheckpointCreate.class, name = "checkpointCreate"),
    @JsonSubTypes.Type(value = RuntimeCommand.CheckpointRestore.class, name = "checkpointRestore"),
    @JsonSubTypes.Type(value = RuntimeCommand.UiBindings.class, name = "uiBindings"),
    @JsonSubTypes.Type(value = RuntimeCommand.UiFrames.class, name = "uiFrames")
})
public sealed interface RuntimeCommand permits RuntimeCommand.Sessions, RuntimeCommand.Capabilities,
        RuntimeCommand.Frames, RuntimeCommand.Snapshot, RuntimeCommand.Entity,
        RuntimeCommand.Changes, RuntimeCommand.Events, RuntimeCommand.Decisions,
        RuntimeCommand.CommandStatus, RuntimeCommand.CommandCancel, RuntimeCommand.EpochFrames,
        RuntimeCommand.Scenarios, RuntimeCommand.Reset, RuntimeCommand.AttributedChanges,
        RuntimeCommand.AttributedEvents, RuntimeCommand.AttributedDecisions,
        RuntimeCommand.Actions, RuntimeCommand.Action, RuntimeCommand.Assert,
        RuntimeCommand.Control, RuntimeCommand.Advance, RuntimeCommand.Wait,
        RuntimeCommand.Inputs, RuntimeCommand.Input, RuntimeCommand.Checkpoints,
        RuntimeCommand.CheckpointCreate, RuntimeCommand.CheckpointRestore,
        RuntimeCommand.UiBindings, RuntimeCommand.UiFrames {
    /** Lists published sessions. */
    record Sessions() implements RuntimeCommand {}

    /** Reads one session's capabilities. */
    record Capabilities() implements RuntimeCommand {}

    /** Reads bounded frame summaries. */
    record Frames(long fromFrame, long toFrame, int limit) implements RuntimeCommand {
        /** Validates range and limit. */
        public Frames {
            validateRange(fromFrame, toFrame);
            validateLimit(limit);
        }
    }

    /** Reads one completed snapshot, optionally filtering its entities. */
    record Snapshot(
            Long frameId,
            String entityId,
            boolean entityIdPrefix,
            String entityType,
            boolean entityTypePrefix,
            int limit) implements RuntimeCommand {
        /** Validates optional filters. */
        public Snapshot {
            if (frameId != null && frameId < 0) {
                throw new IllegalArgumentException("frameId must be non-negative");
            }
            if (entityId != null) {
                ProtocolJson.requireIdentifier(entityId, "entityId");
            }
            if (entityType != null) {
                ProtocolJson.requireIdentifier(entityType, "entityType");
            }
            validateLimit(limit);
        }
    }

    /** Reads latest state and bounded history for one exact entity ID. */
    record Entity(String entityId, long fromFrame, long toFrame, int limit)
            implements RuntimeCommand {
        /** Validates fields. */
        public Entity {
            ProtocolJson.requireIdentifier(entityId, "entityId");
            validateRange(fromFrame, toFrame);
            validateLimit(limit);
        }
    }

    /** Reads property changes using exact filters. */
    record Changes(
            long fromFrame,
            long toFrame,
            String entityId,
            String entityType,
            String property,
            int limit) implements RuntimeCommand {
        /** Validates fields. */
        public Changes {
            validateRange(fromFrame, toFrame);
            requireOptionalIdentifier(entityId, "entityId");
            requireOptionalIdentifier(entityType, "entityType");
            requireOptionalIdentifier(property, "property");
            validateLimit(limit);
        }
    }

    /** Reads structured events with exact or prefix type filtering. */
    record Events(
            long fromFrame,
            long toFrame,
            String eventType,
            boolean eventTypePrefix,
            String subject,
            String source,
            int limit) implements RuntimeCommand {
        /** Validates fields. */
        public Events {
            validateRange(fromFrame, toFrame);
            requireOptionalIdentifier(eventType, "eventType");
            requireOptionalIdentifier(subject, "subject");
            requireOptionalIdentifier(source, "source");
            validateLimit(limit);
        }
    }

    /** Reads semantic decisions with exact filters. */
    record Decisions(
            long fromFrame,
            long toFrame,
            String decisionType,
            String actor,
            String chosenCandidate,
            String reasonCode,
            int limit) implements RuntimeCommand {
        /** Validates fields. */
        public Decisions {
            validateRange(fromFrame, toFrame);
            requireOptionalIdentifier(decisionType, "decisionType");
            requireOptionalIdentifier(actor, "actor");
            requireOptionalIdentifier(chosenCandidate, "chosenCandidate");
            requireOptionalIdentifier(reasonCode, "reasonCode");
            validateLimit(limit);
        }
    }

    /** Reads one retained application command status. */
    record CommandStatus(String commandRequestId) implements RuntimeCommand {
        public CommandStatus {
            ProtocolJson.requireIdentifier(commandRequestId, "commandRequestId");
        }
    }

    /** Requests cancellation before application-thread dispatch. */
    record CommandCancel(String commandRequestId) implements RuntimeCommand {
        public CommandCancel {
            ProtocolJson.requireIdentifier(commandRequestId, "commandRequestId");
        }
    }

    /** Reads retained completed frames belonging to one execution epoch. */
    record EpochFrames(long executionEpochId, int limit) implements RuntimeCommand {
        public EpochFrames {
            if (executionEpochId < 0) {
                throw new IllegalArgumentException("executionEpochId must be non-negative");
            }
            validateLimit(limit);
        }
    }

    /** Lists explicitly registered resettable scenarios. */
    record Scenarios() implements RuntimeCommand {}

    /** Submits or polls one idempotently correlated scenario reset. */
    record Reset(String scenarioId, String resetRequestId, long timeoutNanos)
            implements RuntimeCommand {
        public Reset {
            ProtocolJson.requireIdentifier(scenarioId, "scenarioId");
            ProtocolJson.requireIdentifier(resetRequestId, "resetRequestId");
            if (timeoutNanos <= 0) {
                throw new IllegalArgumentException("timeoutNanos must be positive");
            }
        }
    }

    /** Queries property changes with exact explicit-metadata filters. */
    record AttributedChanges(long fromFrame, long toFrame, String entityId, String entityType,
            String property, String sourceSubsystem, String correlationId, int limit)
            implements RuntimeCommand {
        public AttributedChanges {
            validateRange(fromFrame, toFrame);
            requireOptionalIdentifier(entityId, "entityId");
            requireOptionalIdentifier(entityType, "entityType");
            requireOptionalIdentifier(property, "property");
            requireOptionalIdentifier(sourceSubsystem, "sourceSubsystem");
            requireOptionalIdentifier(correlationId, "correlationId");
            validateLimit(limit);
        }
    }

    /** Queries events with exact explicit-metadata filters. */
    record AttributedEvents(long fromFrame, long toFrame, String eventType,
            boolean eventTypePrefix, String subject, String source, String sourceSubsystem,
            String correlationId, int limit) implements RuntimeCommand {
        public AttributedEvents {
            validateRange(fromFrame, toFrame);
            requireOptionalIdentifier(eventType, "eventType");
            requireOptionalIdentifier(subject, "subject");
            requireOptionalIdentifier(source, "source");
            requireOptionalIdentifier(sourceSubsystem, "sourceSubsystem");
            requireOptionalIdentifier(correlationId, "correlationId");
            validateLimit(limit);
        }
    }

    /** Queries decisions with exact explicit-metadata filters. */
    record AttributedDecisions(long fromFrame, long toFrame, String decisionType, String actor,
            String chosenCandidate, String reasonCode, String sourceSubsystem,
            String correlationId, int limit) implements RuntimeCommand {
        public AttributedDecisions {
            validateRange(fromFrame, toFrame);
            requireOptionalIdentifier(decisionType, "decisionType");
            requireOptionalIdentifier(actor, "actor");
            requireOptionalIdentifier(chosenCandidate, "chosenCandidate");
            requireOptionalIdentifier(reasonCode, "reasonCode");
            requireOptionalIdentifier(sourceSubsystem, "sourceSubsystem");
            requireOptionalIdentifier(correlationId, "correlationId");
            validateLimit(limit);
        }
    }

    /** Lists explicitly registered typed semantic actions. */
    record Actions() implements RuntimeCommand {}

    /** Submits or polls one idempotently correlated semantic action. */
    record Action(String actionId, String actionRequestId,
            io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue.ObjectValue parameters,
            String correlationId, long timeoutNanos) implements RuntimeCommand {
        public Action {
            ProtocolJson.requireIdentifier(actionId, "actionId");
            ProtocolJson.requireIdentifier(actionRequestId, "actionRequestId");
            java.util.Objects.requireNonNull(parameters, "parameters");
            requireOptionalIdentifier(correlationId, "correlationId");
            if (timeoutNanos <= 0) {
                throw new IllegalArgumentException("timeoutNanos must be positive");
            }
        }
    }

    /** Evaluates one closed declarative assertion over a bounded completed epoch range. */
    record Assert(io.github.teemuki8.libgdx.agent.runtime.core.RuntimeAssertion assertion,
            long fromFrame, long toFrame, long executionEpochId, int evidenceLimit)
            implements RuntimeCommand {
        public Assert {
            java.util.Objects.requireNonNull(assertion, "assertion");
            validateRange(fromFrame, toFrame);
            if (executionEpochId < 0) {
                throw new IllegalArgumentException("executionEpochId must be non-negative");
            }
            new io.github.teemuki8.libgdx.agent.runtime.core.AssertionScope(
                    new io.github.teemuki8.libgdx.agent.runtime.core.ExecutionEpochId(
                            executionEpochId),
                    io.github.teemuki8.libgdx.agent.runtime.core.FrameRange.of(fromFrame, toFrame),
                    evidenceLimit);
        }
    }

    /** Simulation control action. */
    enum ControlAction {
        /** Read control availability, state, conditions, and limits. */
        STATUS,
        /** Gate normal application simulation updates. */
        PAUSE,
        /** Resume normal application simulation updates. */
        RESUME
    }

    /** Reads or mutates explicit simulation pause state. */
    record Control(ControlAction action, String controlRequestId, long timeoutNanos)
            implements RuntimeCommand {
        public Control {
            java.util.Objects.requireNonNull(action, "action");
            if (action == ControlAction.STATUS) {
                if (controlRequestId != null || timeoutNanos != 0) {
                    throw new IllegalArgumentException("status does not accept mutation fields");
                }
            } else {
                ProtocolJson.requireIdentifier(controlRequestId, "controlRequestId");
                requirePositive(timeoutNanos, "timeoutNanos");
            }
        }
    }

    /** Advances an exact bounded number of application-defined ticks while paused. */
    record Advance(String controlRequestId, int ticks, long deltaNanos, long timeoutNanos)
            implements RuntimeCommand {
        public Advance {
            ProtocolJson.requireIdentifier(controlRequestId, "controlRequestId");
            requirePositive(ticks, "ticks");
            if (deltaNanos < 0) {
                throw new IllegalArgumentException("deltaNanos must be non-negative");
            }
            requirePositive(timeoutNanos, "timeoutNanos");
        }
    }

    /** Advances until one registered condition or closed assertion is satisfied. */
    record Wait(String controlRequestId, String conditionId,
            io.github.teemuki8.libgdx.agent.runtime.core.RuntimeAssertion assertion,
            int maximumTicks, long deltaNanos, int evidenceLimit, long timeoutNanos)
            implements RuntimeCommand {
        public Wait {
            ProtocolJson.requireIdentifier(controlRequestId, "controlRequestId");
            requireOptionalIdentifier(conditionId, "conditionId");
            if ((conditionId == null) == (assertion == null)) {
                throw new IllegalArgumentException(
                        "wait requires exactly one conditionId or assertion");
            }
            requirePositive(maximumTicks, "maximumTicks");
            if (deltaNanos < 0) {
                throw new IllegalArgumentException("deltaNanos must be non-negative");
            }
            if (evidenceLimit <= 0
                    || evidenceLimit
                            > io.github.teemuki8.libgdx.agent.runtime.core.AssertionScope.MAX_EVIDENCE) {
                throw new IllegalArgumentException("evidenceLimit is outside the supported range");
            }
            requirePositive(timeoutNanos, "timeoutNanos");
        }
    }

    /** Lists explicitly registered closed input types. */
    record Inputs() implements RuntimeCommand {}

    /** Schedules or polls one registered input for a controlled tick. */
    record Input(String inputId, String inputRequestId,
            io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue.ObjectValue parameters,
            Long targetTick, long timeoutNanos) implements RuntimeCommand {
        public Input {
            ProtocolJson.requireIdentifier(inputId, "inputId");
            ProtocolJson.requireIdentifier(inputRequestId, "inputRequestId");
            java.util.Objects.requireNonNull(parameters, "parameters");
            if (targetTick != null && targetTick <= 0) {
                throw new IllegalArgumentException("targetTick must be positive");
            }
            requirePositive(timeoutNanos, "timeoutNanos");
        }
    }

    /** Lists retained application-owned checkpoint descriptors. */
    record Checkpoints() implements RuntimeCommand {}

    /** Creates or polls one opaque application-owned checkpoint. */
    record CheckpointCreate(String checkpointId, String description,
            String checkpointRequestId, long timeoutNanos) implements RuntimeCommand {
        public CheckpointCreate {
            ProtocolJson.requireIdentifier(checkpointId, "checkpointId");
            if (description != null && (description.isBlank()
                    || description.length() > ProtocolJson.MAX_STRING_LENGTH)) {
                throw new IllegalArgumentException("description is invalid");
            }
            ProtocolJson.requireIdentifier(checkpointRequestId, "checkpointRequestId");
            requirePositive(timeoutNanos, "timeoutNanos");
        }
    }

    /** Restores or polls one retained application-owned checkpoint. */
    record CheckpointRestore(String checkpointId, String checkpointRequestId,
            long timeoutNanos) implements RuntimeCommand {
        public CheckpointRestore {
            ProtocolJson.requireIdentifier(checkpointId, "checkpointId");
            ProtocolJson.requireIdentifier(checkpointRequestId, "checkpointRequestId");
            requirePositive(timeoutNanos, "timeoutNanos");
        }
    }

    /** Resolves explicit runtime-to-UI or UI-to-runtime bindings at one validity point. */
    record UiBindings(String entityId, String property, String uiSessionId, String uiControlId,
            long executionEpochId, long runtimeFrameId, String uiGeneration, int limit)
            implements RuntimeCommand {
        public UiBindings {
            requireOptionalIdentifier(entityId, "entityId");
            requireOptionalIdentifier(property, "property");
            requireOptionalIdentifier(uiSessionId, "uiSessionId");
            requireOptionalIdentifier(uiControlId, "uiControlId");
            requireOptionalIdentifier(uiGeneration, "uiGeneration");
            boolean runtimeDirection = entityId != null;
            boolean uiDirection = uiSessionId != null && uiControlId != null;
            if (runtimeDirection == uiDirection
                    || (!runtimeDirection && (entityId != null || property != null))
                    || (!uiDirection && (uiSessionId != null || uiControlId != null))) {
                throw new IllegalArgumentException(
                        "UI binding query requires exactly one complete direction selector");
            }
            if (executionEpochId < 0 || runtimeFrameId < 0) {
                throw new IllegalArgumentException("UI binding validity point must be non-negative");
            }
            validateLimit(limit);
        }
    }

    /** Queries explicit cross-system frame mappings by UI session or shared token. */
    record UiFrames(String uiSessionId, String correlationToken, int limit)
            implements RuntimeCommand {
        public UiFrames {
            requireOptionalIdentifier(uiSessionId, "uiSessionId");
            requireOptionalIdentifier(correlationToken, "correlationToken");
            if ((uiSessionId == null) == (correlationToken == null)) {
                throw new IllegalArgumentException(
                        "UI frame query requires exactly one UI session or correlation token");
            }
            validateLimit(limit);
        }
    }

    private static void validateRange(long from, long to) {
        if (from < 0 || to < from) {
            throw new IllegalArgumentException("frame range must be non-negative and ascending");
        }
    }

    private static void validateLimit(int limit) {
        if (limit <= 0 || limit > ProtocolJson.MAX_RESULT_ITEMS) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + ProtocolJson.MAX_RESULT_ITEMS);
        }
    }

    private static void requireOptionalIdentifier(String value, String name) {
        if (value != null) {
            ProtocolJson.requireIdentifier(value, name);
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
