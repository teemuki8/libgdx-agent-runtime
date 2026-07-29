package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;

/** Bounded chronological entity states and changes. */
public record EntityHistory(
        EntityId entityId,
        List<Version> versions,
        QueryPage<PropertyChange> changes) {
    /** Validates and copies history. */
    public EntityHistory {
        Objects.requireNonNull(entityId, "entityId");
        versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
        Objects.requireNonNull(changes, "changes");
    }

    /** One frame-local entity state. */
    public record Version(FrameId frameId, EntitySnapshot snapshot) {
        /** Validates state. */
        public Version {
            Objects.requireNonNull(frameId, "frameId");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
