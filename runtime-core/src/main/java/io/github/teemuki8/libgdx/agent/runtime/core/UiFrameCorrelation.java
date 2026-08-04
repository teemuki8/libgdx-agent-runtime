package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Explicit mapping from a runtime frame to a UI frame identifier or shared token. */
public record UiFrameCorrelation(ExecutionEpochId runtimeEpochId, FrameId runtimeFrameId,
        String uiSessionId, Optional<String> uiFrameId, Optional<String> correlationToken) {
    /** Validates explicit cross-system identifiers without assuming numeric equality. */
    public UiFrameCorrelation {
        Objects.requireNonNull(runtimeEpochId, "runtimeEpochId");
        Objects.requireNonNull(runtimeFrameId, "runtimeFrameId");
        IdentifierSupport.validate(uiSessionId, "UI session id");
        uiFrameId = Objects.requireNonNull(uiFrameId, "uiFrameId");
        correlationToken = Objects.requireNonNull(correlationToken, "correlationToken");
        uiFrameId.ifPresent(value -> IdentifierSupport.validate(value, "UI frame id"));
        correlationToken.ifPresent(value -> IdentifierSupport.validate(value, "correlation token"));
        if (uiFrameId.isEmpty() && correlationToken.isEmpty()) {
            throw new IllegalArgumentException(
                    "UI frame correlation requires a UI frame id or shared token");
        }
    }
}
