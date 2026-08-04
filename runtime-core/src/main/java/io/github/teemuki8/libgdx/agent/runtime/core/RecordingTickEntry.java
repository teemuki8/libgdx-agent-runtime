package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** One completed controlled tick with applied delta and resulting frame evidence. */
public record RecordingTickEntry(long order, long tick, long deltaNanos,
        ExecutionEpochId executionEpochId, FrameId resultingFrameId) implements RecordingEntry {
    /** Validates immutable controlled-tick evidence. */
    public RecordingTickEntry {
        if (order < 0 || tick <= 0 || deltaNanos < 0) {
            throw new IllegalArgumentException("invalid recorded tick evidence");
        }
        Objects.requireNonNull(executionEpochId, "executionEpochId");
        Objects.requireNonNull(resultingFrameId, "resultingFrameId");
    }
}
