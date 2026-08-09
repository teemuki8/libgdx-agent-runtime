package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Immutable result for one bounded exact simulation-tick determinism comparison. */
public record SimulationDeterminismResult(DeterminismStatus status, String message,
        DeterminismProfile profile, Optional<SimulationDeterminismDivergence> divergence,
        DeterminismBounds bounds, Optional<ApplicationFailureEvidence> applicationFailure) {
    /** Validates closed status evidence and bounded messaging. */
    public SimulationDeterminismResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(profile, "profile");
        divergence = Objects.requireNonNull(divergence, "divergence");
        Objects.requireNonNull(bounds, "bounds");
        applicationFailure = applicationFailure == null
                ? Optional.empty() : applicationFailure;
        if (message.isBlank()
                || message.length() > ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY) {
            throw new IllegalArgumentException(
                    "simulation determinism message is outside the public bound");
        }
        if ((status == DeterminismStatus.DIVERGED) != divergence.isPresent()) {
            throw new IllegalArgumentException(
                    "simulation determinism divergence evidence is inconsistent");
        }
    }
}
