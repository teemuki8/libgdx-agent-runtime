package io.github.teemuki8.libgdx.agent.runtime.core;

/** Observed execution/evidence counts and the configured deadline. */
public record DeterminismBounds(int completedRepeats, int ticksPerRepeat,
        long observedEntities, long observedFacts, long encodedEvidenceBytes,
        long executionDeadlineNanos) {
    /** Validates non-negative bounded counters. */
    public DeterminismBounds {
        if (completedRepeats < 0 || ticksPerRepeat < 0 || observedEntities < 0
                || observedFacts < 0 || encodedEvidenceBytes < 0 || executionDeadlineNanos <= 0) {
            throw new IllegalArgumentException("invalid determinism bounds evidence");
        }
    }
}
