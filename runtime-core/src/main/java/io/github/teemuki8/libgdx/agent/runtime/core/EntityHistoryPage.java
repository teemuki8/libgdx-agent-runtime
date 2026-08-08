package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded paginated retained history for one entity, including entities removed from the latest
 * frame while their pre-removal evidence remains retained.
 *
 * <p>{@code current} mirrors the newest retained frame's presence, so it is empty once an entity
 * is removed. {@code finalRetainedState} carries the newest retained immutable snapshot for the
 * entity across all retained frames — the bounded final pre-removal state — and never synthesizes
 * properties. Version pagination uses its own positional cursor ({@code nextVersionOffset} and
 * {@code hasMoreVersions}) independent from any change pagination, with retention bounds mirroring
 * {@link QueryPage}.
 */
public record EntityHistoryPage(
        EntityId entityId,
        Optional<EntitySnapshot> current,
        Optional<EntitySnapshot> finalRetainedState,
        List<EntityHistory.Version> versions,
        long nextVersionOffset,
        boolean hasMoreVersions,
        boolean requestedRangePartiallyEvicted,
        Optional<FrameId> oldestRetainedFrame,
        Optional<FrameId> newestRetainedFrame) {
    /** Validates and defensively copies the page. */
    public EntityHistoryPage {
        Objects.requireNonNull(entityId, "entityId");
        current = Objects.requireNonNull(current, "current");
        finalRetainedState = Objects.requireNonNull(finalRetainedState, "finalRetainedState");
        versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
        oldestRetainedFrame = Objects.requireNonNull(oldestRetainedFrame, "oldestRetainedFrame");
        newestRetainedFrame = Objects.requireNonNull(newestRetainedFrame, "newestRetainedFrame");
        if (nextVersionOffset < 0) {
            throw new IllegalArgumentException("nextVersionOffset must be non-negative");
        }
        if (versions.isEmpty() && hasMoreVersions) {
            throw new IllegalArgumentException("hasMoreVersions requires retained versions");
        }
        if (current.isPresent() && finalRetainedState.isEmpty()) {
            throw new IllegalArgumentException(
                    "current presence requires a retained final state");
        }
        if (finalRetainedState.isEmpty() && !versions.isEmpty()) {
            throw new IllegalArgumentException(
                    "retained versions require a retained final state");
        }
        current.ifPresent(snapshot -> requireSameEntity(entityId, snapshot, "current"));
        finalRetainedState.ifPresent(
                snapshot -> requireSameEntity(entityId, snapshot, "finalRetainedState"));
        for (EntityHistory.Version version : versions) {
            requireSameEntity(entityId, version.snapshot(), "version snapshot");
        }
    }

    private static void requireSameEntity(
            EntityId entityId, EntitySnapshot snapshot, String component) {
        if (!entityId.equals(snapshot.id())) {
            throw new IllegalArgumentException(component + " belongs to a different entity");
        }
    }
}
