package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Instant;
import java.util.Objects;

/** Compact completed-frame metadata. */
public record FrameSummary(
        FrameId frameId,
        Instant capturedAt,
        int entityCount,
        int changeCount,
        int eventCount,
        int decisionCount,
        int diagnosticCount) {
    /** Validates counts. */
    public FrameSummary {
        Objects.requireNonNull(frameId, "frameId");
        Objects.requireNonNull(capturedAt, "capturedAt");
        if (entityCount < 0 || changeCount < 0 || eventCount < 0 || decisionCount < 0
                || diagnosticCount < 0) {
            throw new IllegalArgumentException("frame summary counts must be non-negative");
        }
    }

    static FrameSummary from(FrameSnapshot frame) {
        return new FrameSummary(frame.frameId(), frame.capturedAt(), frame.entities().size(),
                frame.changes().size(), frame.events().size(), frame.decisions().size(),
                frame.stats().diagnostics().size());
    }
}
