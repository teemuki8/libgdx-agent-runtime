package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** Exact observed, retained, and configured recording truncation evidence. */
public record RecordingTruncation(RecordingTruncationDimension dimension, long observed,
        long retained, long limit, boolean reproductionEvidenceIncomplete) {
    /** Validates truncation counts. */
    public RecordingTruncation {
        Objects.requireNonNull(dimension, "dimension");
        if (observed <= limit || retained < 0 || retained > limit || retained > observed
                || limit <= 0) {
            throw new IllegalArgumentException("invalid recording truncation evidence");
        }
    }
}
