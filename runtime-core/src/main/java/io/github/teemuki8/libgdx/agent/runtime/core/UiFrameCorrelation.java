package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/**
 * Explicit mapping from a runtime frame to a UI frame identifier or shared token.
 *
 * <p>The harness resolves runtime bindings against recorded correlations and reports an
 * observation only on a proven frame; it never guesses a frame. Record one correlation per
 * rendered frame on the capture thread (after the frame completes) under a stable,
 * non-secret token, and attach the same token to the harness-side binding. A token
 * mismatch or a missing correlation degrades comparisons to {@code UNCORRELATED}/
 * {@code STALE} rather than raising an error, so keep the token stable and identical on
 * both sides. Exactly one of {@code uiFrameId} and {@code correlationToken} must be
 * present.
 *
 * @param runtimeEpochId the runtime execution epoch the frame belongs to
 * @param runtimeFrameId the completed runtime frame being correlated
 * @param uiSessionId the harness UI session identifier
 * @param uiFrameId optional harness-side frame identifier for this runtime frame
 * @param correlationToken optional shared token pairing this correlation with harness bindings
 */
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
