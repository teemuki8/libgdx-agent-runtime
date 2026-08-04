package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** One semantic action submission, its closed parameters, and latest retained outcome evidence. */
public record RecordingActionEntry(long order, ActionInvocation invocation,
        RuntimeValue.ObjectValue parameters)
        implements RecordingEntry {
    /** Validates action recording evidence. */
    public RecordingActionEntry {
        if (order < 0) {
            throw new IllegalArgumentException("recording order must be non-negative");
        }
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(parameters, "parameters");
    }
}
