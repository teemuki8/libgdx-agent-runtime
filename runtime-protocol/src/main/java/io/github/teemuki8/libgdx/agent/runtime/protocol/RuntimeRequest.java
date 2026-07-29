package io.github.teemuki8.libgdx.agent.runtime.protocol;

import java.util.Objects;

/** One bounded request envelope. */
public record RuntimeRequest(
        ProtocolVersion version,
        String requestId,
        String sessionId,
        RuntimeCommand command) {
    /** Validates correlation and optional session fields. */
    public RuntimeRequest {
        version = Objects.requireNonNull(version, "version");
        ProtocolJson.requireIdentifier(requestId, "requestId");
        command = Objects.requireNonNull(command, "command");
        if (!(command instanceof RuntimeCommand.Sessions)) {
            ProtocolJson.requireIdentifier(sessionId, "sessionId");
        } else if (sessionId != null) {
            ProtocolJson.requireIdentifier(sessionId, "sessionId");
        }
    }
}
