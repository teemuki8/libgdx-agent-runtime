package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** At-most-once application-dispatched replay-ready recording start operation. */
public record ReplayCaptureOperation(String recordingId, String requestId, CommandLookup command,
        Optional<ExecutionEpochId> baselineExecutionEpochId, Optional<FrameId> baselineFrameId) {
    /** Validates immutable command and paired baseline evidence. */
    public ReplayCaptureOperation {
        IdentifierSupport.validate(recordingId, "replay recording id");
        IdentifierSupport.validate(requestId, "replay capture request id");
        Objects.requireNonNull(command, "command");
        baselineExecutionEpochId = Objects.requireNonNull(
                baselineExecutionEpochId, "baselineExecutionEpochId");
        baselineFrameId = Objects.requireNonNull(baselineFrameId, "baselineFrameId");
        if (baselineExecutionEpochId.isPresent() != baselineFrameId.isPresent()) {
            throw new IllegalArgumentException(
                    "replay baseline execution epoch and frame must be paired");
        }
    }
}
