package io.github.teemuki8.libgdx.agent.runtime.core;

/** All hard in-memory capture and value bounds. */
public record RuntimeLimits(
        int retainedFrames,
        int retainedEvents,
        int entitiesPerSnapshot,
        int propertiesPerEntity,
        int decisionsPerFrame,
        int candidatesPerDecision,
        int attributesPerItem,
        int stringLength,
        int collectionLength,
        int nestingDepth,
        int queryResults) {
    private static final RuntimeLimits DEVELOPMENT = new RuntimeLimits(
            240, 2_000, 5_000, 128, 256, 256, 64, 4_096, 256, 16, 1_000);

    /** Validates that every configured limit is positive and diagnostic text admits the envelope. */
    public RuntimeLimits {
        int[] values = {retainedFrames, retainedEvents, entitiesPerSnapshot,
            propertiesPerEntity, decisionsPerFrame, candidatesPerDecision, attributesPerItem,
            collectionLength, nestingDepth, queryResults};
        for (int value : values) {
            if (value <= 0) {
                throw new IllegalArgumentException("runtime limits must be positive");
            }
        }
        if (stringLength < ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY) {
            throw new IllegalArgumentException("stringLength must be at least "
                    + ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY);
        }
    }

    /** Returns conservative development defaults. */
    public static RuntimeLimits developmentDefaults() {
        return DEVELOPMENT;
    }
}
