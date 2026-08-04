package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;

/** Independent hard bounds for recording evidence, retention, and retrieval. */
public record RecordingLimits(int retainedRecordings, int retainedOperations,
        int itemsPerRecording, long maximumTickSpan, long maximumDurationNanos,
        int maximumEncodedBytes, int chunkItems, int stringLength) {
    /** Validates supported recording bounds. */
    public RecordingLimits {
        if (retainedRecordings <= 0 || retainedRecordings > 1_000
                || retainedOperations <= 0 || retainedOperations > 100_000
                || itemsPerRecording <= 0 || itemsPerRecording > 100_000
                || maximumTickSpan <= 0 || maximumTickSpan > 1_000_000_000L
                || maximumDurationNanos <= 0
                || maximumDurationNanos > Duration.ofDays(7).toNanos()
                || maximumEncodedBytes <= 0 || maximumEncodedBytes > 16_777_216
                || chunkItems <= 0 || chunkItems > 1_000
                || stringLength <= 0 || stringLength > 16_384) {
            throw new IllegalArgumentException("recording limit is outside the supported range");
        }
    }

    /** Returns conservative development defaults. */
    public static RecordingLimits developmentDefaults() {
        return new RecordingLimits(16, 64, 4_096, 100_000, Duration.ofHours(1).toNanos(),
                1_048_576, 64, 512);
    }
}
