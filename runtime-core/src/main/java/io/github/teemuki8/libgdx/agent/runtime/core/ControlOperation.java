package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Correlated bounded simulation-control outcome and completed-frame evidence. */
public record ControlOperation(String requestId, Kind kind, CommandLookup command,
        int requestedTicks, int completedTicks, Optional<FrameId> firstFrameId,
        Optional<FrameId> finalFrameId, ControlStopReason stopReason, boolean paused,
        Optional<String> conditionId, Optional<AssertionResult> assertionResult) {
    /** Control operation kind. */
    public enum Kind {
        /** Pause normal application simulation updates. */
        PAUSE,
        /** Resume normal application simulation updates. */
        RESUME,
        /** Advance a fixed number of application-defined ticks. */
        ADVANCE,
        /** Advance until a bounded semantic condition or assertion is satisfied. */
        WAIT
    }

    /** Validates immutable operation evidence. */
    public ControlOperation {
        IdentifierSupport.validate(requestId, "control request id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(command, "command");
        if (requestedTicks < 0 || completedTicks < 0 || completedTicks > requestedTicks) {
            throw new IllegalArgumentException("control tick counts are invalid");
        }
        firstFrameId = Objects.requireNonNull(firstFrameId, "firstFrameId");
        finalFrameId = Objects.requireNonNull(finalFrameId, "finalFrameId");
        if (firstFrameId.isPresent() != finalFrameId.isPresent()
                || firstFrameId.isPresent() != (completedTicks > 0)) {
            throw new IllegalArgumentException("control frame evidence is inconsistent");
        }
        Objects.requireNonNull(stopReason, "stopReason");
        conditionId = Objects.requireNonNull(conditionId, "conditionId");
        conditionId.ifPresent(value -> IdentifierSupport.validate(value, "condition id"));
        assertionResult = Objects.requireNonNull(assertionResult, "assertionResult");
    }
}
