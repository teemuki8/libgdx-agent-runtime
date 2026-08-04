package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Completed frame timing and capture-quality evidence retained by a recording. */
public record RecordingFrameEntry(long order, ExecutionEpochId executionEpochId, FrameId frameId,
        long deltaNanos, Optional<BaselineKind> baselineKind, int diagnosticCount,
        int truncationCount) implements RecordingEntry {
    /** Validates immutable frame recording evidence. */
    public RecordingFrameEntry {
        if (order < 0 || deltaNanos < 0 || diagnosticCount < 0 || truncationCount < 0) {
            throw new IllegalArgumentException("invalid recorded frame evidence");
        }
        Objects.requireNonNull(executionEpochId, "executionEpochId");
        Objects.requireNonNull(frameId, "frameId");
        baselineKind = Objects.requireNonNull(baselineKind, "baselineKind");
    }
}
