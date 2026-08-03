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
    @JsonSubTypes.Type(value = RuntimeCommand.CommandCancel.class, name = "commandCancel")
})
public sealed interface RuntimeCommand permits RuntimeCommand.Sessions, RuntimeCommand.Capabilities,
        RuntimeCommand.Frames, RuntimeCommand.Snapshot, RuntimeCommand.Entity,
        RuntimeCommand.Changes, RuntimeCommand.Events, RuntimeCommand.Decisions,
        RuntimeCommand.CommandStatus, RuntimeCommand.CommandCancel {
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
