package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable metadata for one retained opaque application checkpoint. */
public record CheckpointDescriptor(String id, ExecutionEpochId sourceEpochId,
        FrameId sourceFrameId, Optional<String> description, Instant createdAt,
        String creationRequestId) {
    /** Validates bounded checkpoint metadata. */
    public CheckpointDescriptor {
        IdentifierSupport.validate(id, "checkpoint id");
        Objects.requireNonNull(sourceEpochId, "sourceEpochId");
        Objects.requireNonNull(sourceFrameId, "sourceFrameId");
        description = Objects.requireNonNull(description, "description");
        description.ifPresent(value -> {
            if (value.isBlank() || value.length() > 16_384) {
                throw new IllegalArgumentException("checkpoint description is invalid");
            }
        });
        Objects.requireNonNull(createdAt, "createdAt");
        IdentifierSupport.validate(creationRequestId, "creation request id");
    }
}
