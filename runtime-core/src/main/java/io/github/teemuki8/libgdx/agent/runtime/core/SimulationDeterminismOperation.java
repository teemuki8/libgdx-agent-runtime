package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Submitted or completed application-dispatched simulation determinism operation. */
public record SimulationDeterminismOperation(SimulationDeterminismSpec spec, String requestId,
        CommandLookup command, Optional<SimulationDeterminismResult> result) {
    /** Validates immutable at-most-once operation evidence. */
    public SimulationDeterminismOperation {
        Objects.requireNonNull(spec, "spec");
        IdentifierSupport.validate(requestId, "simulation determinism request id");
        Objects.requireNonNull(command, "command");
        result = Objects.requireNonNull(result, "result");
    }
}
