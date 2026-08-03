package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded completed-frame summaries for one execution epoch. */
public record EpochFramePage(
        ExecutionEpochId executionEpochId,
        List<FrameSummary> items,
        boolean hasMore,
        boolean requestedEpochPartiallyEvicted,
        Optional<FrameId> oldestRetainedFrame,
        Optional<FrameId> newestRetainedFrame) {
    public EpochFramePage {
        Objects.requireNonNull(executionEpochId, "executionEpochId");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        oldestRetainedFrame = Objects.requireNonNull(oldestRetainedFrame, "oldestRetainedFrame");
        newestRetainedFrame = Objects.requireNonNull(newestRetainedFrame, "newestRetainedFrame");
    }
}
