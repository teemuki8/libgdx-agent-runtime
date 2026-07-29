package io.github.teemuki8.libgdx.agent.runtime.protocol;

import java.util.Map;
import java.util.Objects;

/** Remotely safe typed error without a stack trace. */
public record ProtocolError(ProtocolErrorCode code, String message, Map<String, String> details) {
    /** Validates and copies error evidence. */
    public ProtocolError {
        Objects.requireNonNull(code, "code");
        ProtocolJson.requireText(message, "message");
        details = Map.copyOf(Objects.requireNonNull(details, "details"));
        if (details.size() > 64) {
            throw new IllegalArgumentException("too many protocol error details");
        }
        details.forEach((key, value) -> {
            ProtocolJson.requireText(key, "detail key");
            ProtocolJson.requireText(value, "detail value");
        });
    }
}
