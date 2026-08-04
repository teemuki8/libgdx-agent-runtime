package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** Explicit bounded execution-epoch and frame scope for one declarative assertion. */
public record AssertionScope(ExecutionEpochId executionEpochId, FrameRange range, int evidenceLimit) {
    /** Maximum completed frames evaluated by one assertion. */
    public static final int MAX_FRAMES = 1_000;
    /** Maximum supporting evidence items returned by one assertion. */
    public static final int MAX_EVIDENCE = 100;

    /** Validates hard query and evidence bounds. */
    public AssertionScope {
        Objects.requireNonNull(executionEpochId, "executionEpochId");
        Objects.requireNonNull(range, "range");
        long difference = range.to().value() - range.from().value();
        if (difference >= MAX_FRAMES) {
            throw new IllegalArgumentException("assertion frame span exceeds the hard limit");
        }
        if (evidenceLimit <= 0 || evidenceLimit > MAX_EVIDENCE) {
            throw new IllegalArgumentException("assertion evidence limit is outside the supported range");
        }
    }
}
