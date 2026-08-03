package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Instant;
import java.util.Objects;

/** Compact completed-frame metadata. */
public record FrameSummary(
        FrameId frameId,
        ExecutionEpochId executionEpochId,
        java.util.Optional<BaselineKind> baselineKind,
        Instant capturedAt,
        int entityCount,
        int changeCount,
        int eventCount,
        int decisionCount,
        int diagnosticCount) {
    /** Validates counts. */
    public FrameSummary {
        Objects.requireNonNull(frameId, "frameId");
        Objects.requireNonNull(executionEpochId, "executionEpochId");
        baselineKind = Objects.requireNonNull(baselineKind, "baselineKind");
        Objects.requireNonNull(capturedAt, "capturedAt");
        if (entityCount < 0 || changeCount < 0 || eventCount < 0 || decisionCount < 0
                || diagnosticCount < 0) {
            throw new IllegalArgumentException("frame summary counts must be non-negative");
        }
    }

    /** Compatibility constructor for ordinary epoch-zero summaries. */
    public FrameSummary(FrameId frameId, Instant capturedAt, int entityCount, int changeCount,
            int eventCount, int decisionCount, int diagnosticCount) {
        this(frameId, new ExecutionEpochId(0), java.util.Optional.empty(), capturedAt, entityCount,
                changeCount, eventCount, decisionCount, diagnosticCount);
    }

    static FrameSummary from(FrameSnapshot frame) {
        return new FrameSummary(frame.frameId(), frame.executionEpochId(), frame.baselineKind(),
                frame.capturedAt(), frame.entities().size(),
                frame.changes().size(), frame.events().size(), frame.decisions().size(),
                frame.stats().diagnostics().size());
    }
}
