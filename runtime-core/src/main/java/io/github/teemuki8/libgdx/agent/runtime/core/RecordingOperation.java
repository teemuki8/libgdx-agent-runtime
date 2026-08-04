package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** At-most-once application-dispatched recording lifecycle operation. */
public record RecordingOperation(Kind kind, String recordingId, String requestId,
        CommandLookup command, Optional<RecordingStopReason> stopReason) {
    /** Recording lifecycle operation kind. */
    public enum Kind { START, STOP }

    /** Validates immutable operation evidence. */
    public RecordingOperation {
        Objects.requireNonNull(kind, "kind");
        IdentifierSupport.validate(recordingId, "recording id");
        IdentifierSupport.validate(requestId, "recording request id");
        Objects.requireNonNull(command, "command");
        stopReason = Objects.requireNonNull(stopReason, "stopReason");
    }
}
