package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Immutable result for one bounded repeated-scenario comparison. */
public record DeterminismResult(DeterminismStatus status, String message,
        DeterminismProfile profile, OptionalInt epochRelativeTick,
        Optional<ExecutionEpochId> leftExecutionEpochId,
        Optional<ExecutionEpochId> rightExecutionEpochId, Optional<FrameId> leftFrameId,
        Optional<FrameId> rightFrameId, Optional<DeterminismDifference> difference,
        DeterminismBounds bounds, Optional<ApplicationFailureEvidence> applicationFailure) {
    /** Validates complete divergence evidence and bounded messaging. */
    public DeterminismResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(profile, "profile");
        epochRelativeTick = Objects.requireNonNull(epochRelativeTick, "epochRelativeTick");
        leftExecutionEpochId = Objects.requireNonNull(leftExecutionEpochId, "leftExecutionEpochId");
        rightExecutionEpochId = Objects.requireNonNull(rightExecutionEpochId, "rightExecutionEpochId");
        leftFrameId = Objects.requireNonNull(leftFrameId, "leftFrameId");
        rightFrameId = Objects.requireNonNull(rightFrameId, "rightFrameId");
        difference = Objects.requireNonNull(difference, "difference");
        Objects.requireNonNull(bounds, "bounds");
        applicationFailure = applicationFailure == null
                ? Optional.empty() : applicationFailure;
        if (message.isBlank()
                || message.length() > ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY) {
            throw new IllegalArgumentException("determinism message is outside the public bound");
        }
        boolean divergentEvidence = epochRelativeTick.isPresent()
                && leftExecutionEpochId.isPresent() && rightExecutionEpochId.isPresent()
                && leftFrameId.isPresent() && rightFrameId.isPresent() && difference.isPresent();
        if ((status == DeterminismStatus.DIVERGED) != divergentEvidence) {
            throw new IllegalArgumentException("determinism divergence evidence is inconsistent");
        }
    }
}
