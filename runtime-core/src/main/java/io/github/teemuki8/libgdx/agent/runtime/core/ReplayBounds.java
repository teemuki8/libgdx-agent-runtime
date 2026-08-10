package io.github.teemuki8.libgdx.agent.runtime.core;

/** Observed replay execution and evidence counts plus the effective deadline. */
public record ReplayBounds(int requestedTicks, int completedTicks, int recordedInputs,
        long observedEntities, long observedFacts, long encodedEvidenceBytes,
        long executionDeadlineNanos) {
    /** Validates non-negative counters and a positive deadline. */
    public ReplayBounds {
        if (requestedTicks < 0 || completedTicks < 0 || completedTicks > requestedTicks
                || recordedInputs < 0 || observedEntities < 0 || observedFacts < 0
                || encodedEvidenceBytes < 0 || executionDeadlineNanos <= 0) {
            throw new IllegalArgumentException("invalid replay bounds evidence");
        }
    }
}
