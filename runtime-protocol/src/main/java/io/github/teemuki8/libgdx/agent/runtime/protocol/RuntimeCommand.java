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
    @JsonSubTypes.Type(value = RuntimeCommand.AttributedDecisions.class, name = "attributedDecisions")
})
public sealed interface RuntimeCommand permits RuntimeCommand.Sessions, RuntimeCommand.Capabilities,
        RuntimeCommand.Frames, RuntimeCommand.Snapshot, RuntimeCommand.Entity,
        RuntimeCommand.Changes, RuntimeCommand.Events, RuntimeCommand.Decisions,
        RuntimeCommand.CommandStatus, RuntimeCommand.CommandCancel, RuntimeCommand.EpochFrames,
        RuntimeCommand.Scenarios, RuntimeCommand.Reset, RuntimeCommand.AttributedChanges,
        RuntimeCommand.AttributedEvents, RuntimeCommand.AttributedDecisions {
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
}
