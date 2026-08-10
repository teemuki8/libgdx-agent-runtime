package io.github.teemuki8.libgdx.agent.runtime.core;

/** Requested, completed, outcome, byte, limit, and deadline testimony for one timeline. */
public record InputTimelineBounds(int requestedTicks, int completedTicks,
        int requestedTransitions, int executedTransitions, int failedTransitions,
        int notExecutedTransitions, long encodedEvidenceBytes,
        int maximumTicks, int maximumTransitions, int maximumEncodedEvidenceBytes,
        long executionDeadlineNanos) {
    /** Validates complete non-negative bounded count testimony and a positive deadline. */
    public InputTimelineBounds {
        long outcomeTransitions = (long) executedTransitions + failedTransitions
                + notExecutedTransitions;
        if (requestedTicks <= 0 || completedTicks < 0 || completedTicks > requestedTicks
                || requestedTransitions <= 0 || executedTransitions < 0
                || failedTransitions < 0 || notExecutedTransitions < 0
                || outcomeTransitions != requestedTransitions
                || encodedEvidenceBytes < 0 || maximumTicks <= 0
                || maximumTicks > InputTimelineLimits.MAXIMUM_TICKS
                || maximumTransitions <= 0
                || maximumTransitions > InputTimelineLimits.MAXIMUM_TRANSITIONS
                || maximumEncodedEvidenceBytes <= 0
                || maximumEncodedEvidenceBytes
                        > InputTimelineLimits.MAXIMUM_ENCODED_EVIDENCE_BYTES
                || requestedTicks > maximumTicks || requestedTransitions > maximumTransitions
                || encodedEvidenceBytes > maximumEncodedEvidenceBytes
                || executionDeadlineNanos <= 0) {
            throw new IllegalArgumentException("invalid input timeline bounds evidence");
        }
    }
}
