package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Correlated reset status and, after success, its new baseline frame. */
public record ScenarioReset(
        String scenarioId,
        CommandLookup command,
        Optional<ExecutionEpochId> executionEpochId,
        Optional<FrameId> baselineFrameId) {
    public ScenarioReset {
        IdentifierSupport.validate(scenarioId, "scenario id");
        Objects.requireNonNull(command, "command");
        executionEpochId = Objects.requireNonNull(executionEpochId, "executionEpochId");
        baselineFrameId = Objects.requireNonNull(baselineFrameId, "baselineFrameId");
        if (executionEpochId.isPresent() != baselineFrameId.isPresent()) {
            throw new IllegalArgumentException("epoch and baseline frame must be present together");
        }
    }
}
