package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Submitted or completed application-dispatched determinism operation. */
public record DeterminismOperation(DeterminismSpec spec, String requestId, CommandLookup command,
        Optional<DeterminismResult> result) {
    /** Validates immutable operation evidence. */
    public DeterminismOperation {
        Objects.requireNonNull(spec, "spec");
        IdentifierSupport.validate(requestId, "determinism request id");
        Objects.requireNonNull(command, "command");
        result = Objects.requireNonNull(result, "result");
    }
}
