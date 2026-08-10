package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Immutable terminal result for one bounded replay execution. */
public record ReplayResult(DeterminismStatus status, String message, String recordingId,
        DeterminismProfile profile, Optional<ReplayDivergence> divergence, ReplayBounds bounds,
        Optional<ApplicationFailureEvidence> applicationFailure) {
    /** Validates closed status evidence and bounded messaging. */
    public ReplayResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
        IdentifierSupport.validate(recordingId, "replay recording id");
        Objects.requireNonNull(profile, "profile");
        divergence = Objects.requireNonNull(divergence, "divergence");
        Objects.requireNonNull(bounds, "bounds");
        applicationFailure = applicationFailure == null
                ? Optional.empty() : applicationFailure;
        if (message.isBlank()
                || message.length() > ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY) {
            throw new IllegalArgumentException("replay message is outside the public bound");
        }
        if ((status == DeterminismStatus.DIVERGED) != divergence.isPresent()) {
            throw new IllegalArgumentException("replay divergence evidence is inconsistent");
        }
        if (status != DeterminismStatus.INCONCLUSIVE && applicationFailure.isPresent()) {
            throw new IllegalArgumentException(
                    "only inconclusive replay results may contain application failure evidence");
        }
    }
}
