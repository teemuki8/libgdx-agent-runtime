package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Immutable bounded evidence for one checkpoint creation or restore request. */
public record CheckpointOperation(Kind kind, String checkpointId, String requestId,
        CommandLookup command, Optional<CheckpointDescriptor> descriptor,
        Optional<ExecutionEpochId> baselineEpochId, Optional<FrameId> baselineFrameId,
        boolean applicationStateMayBePartiallyChanged, Optional<String> diagnostic,
        Optional<ApplicationFailureEvidence> applicationFailure) {
    /** Checkpoint mutation kind. */
    public enum Kind { CREATE, RESTORE }

    /** Validates operation evidence consistency. */
    public CheckpointOperation {
        Objects.requireNonNull(kind, "kind");
        IdentifierSupport.validate(checkpointId, "checkpoint id");
        IdentifierSupport.validate(requestId, "request id");
        Objects.requireNonNull(command, "command");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        baselineEpochId = Objects.requireNonNull(baselineEpochId, "baselineEpochId");
        baselineFrameId = Objects.requireNonNull(baselineFrameId, "baselineFrameId");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        applicationFailure = applicationFailure == null
                ? Optional.empty() : applicationFailure;
        diagnostic.ifPresent(value -> {
            if (value.isBlank() || value.length() > 16_384) {
                throw new IllegalArgumentException("checkpoint diagnostic is invalid");
            }
        });
        if (baselineEpochId.isPresent() != baselineFrameId.isPresent()) {
            throw new IllegalArgumentException("checkpoint baseline epoch and frame must be paired");
        }
        if (baselineFrameId.isPresent() && kind != Kind.RESTORE) {
            throw new IllegalArgumentException("only restore operations may contain a baseline");
        }
    }
}
