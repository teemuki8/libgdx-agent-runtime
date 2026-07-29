package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable completed frame; safe for concurrent reads. */
public record FrameSnapshot(
        SessionId sessionId,
        FrameId frameId,
        long monotonicTimeNanos,
        long deltaNanos,
        Instant capturedAt,
        List<EntitySnapshot> entities,
        List<PropertyChange> changes,
        List<RuntimeEvent> events,
        List<DecisionTrace> decisions,
        SnapshotStats stats) {
    /** Validates and copies all frame-owned collections. */
    public FrameSnapshot {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(frameId, "frameId");
        if (monotonicTimeNanos < 0 || deltaNanos < 0) {
            throw new IllegalArgumentException("frame times must be non-negative");
        }
        Objects.requireNonNull(capturedAt, "capturedAt");
        entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
        Objects.requireNonNull(stats, "stats");
    }

    /** Finds one entity in this frame. */
    public java.util.Optional<EntitySnapshot> entity(EntityId id) {
        Objects.requireNonNull(id, "id");
        return entities.stream().filter(entity -> entity.id().equals(id)).findFirst();
    }
}
