package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** At-most-once application-dispatched replay execution operation. */
public record ReplayOperation(String recordingId, String requestId, CommandLookup command,
        Optional<ReplayResult> result) {
    /** Validates immutable operation evidence. */
    public ReplayOperation {
        IdentifierSupport.validate(recordingId, "replay recording id");
        IdentifierSupport.validate(requestId, "replay request id");
        Objects.requireNonNull(command, "command");
        result = Objects.requireNonNull(result, "result");
    }
}
