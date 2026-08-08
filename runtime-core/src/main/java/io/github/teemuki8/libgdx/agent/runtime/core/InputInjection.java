package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Immutable bounded status, target, epoch, frame, and recording evidence for one input. */
public record InputInjection(String inputId, String requestId, CommandLookup command,
        InputInjectionState state, long targetTick, OptionalLong actualTick,
        ExecutionEpochId executionEpochId, Optional<FrameId> submittedFrameId,
        Optional<FrameId> resultingFrameId, Optional<RuntimeValue.ObjectValue> recordedParameters,
        boolean parametersRedacted, Optional<String> diagnostic,
        Optional<ApplicationFailureEvidence> applicationFailure) {
    /** Validates immutable correlated input evidence. */
    public InputInjection {
        IdentifierSupport.validate(inputId, "input id");
        IdentifierSupport.validate(requestId, "request id");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(state, "state");
        if (targetTick <= 0 || actualTick.isPresent() && actualTick.orElseThrow() <= 0) {
            throw new IllegalArgumentException("input tick must be positive");
        }
        Objects.requireNonNull(executionEpochId, "executionEpochId");
        submittedFrameId = Objects.requireNonNull(submittedFrameId, "submittedFrameId");
        resultingFrameId = Objects.requireNonNull(resultingFrameId, "resultingFrameId");
        recordedParameters = Objects.requireNonNull(recordedParameters, "recordedParameters");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        applicationFailure = applicationFailure == null
                ? Optional.empty() : applicationFailure;
        if (parametersRedacted && recordedParameters.isPresent()) {
            throw new IllegalArgumentException("redacted input cannot expose parameters");
        }
    }
}
