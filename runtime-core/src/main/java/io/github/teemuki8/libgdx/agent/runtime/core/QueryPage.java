package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded deterministic query result with retention and result-limit metadata. */
public record QueryPage<T>(
        List<T> items,
        boolean hasMore,
        boolean requestedRangePartiallyEvicted,
        Optional<FrameId> oldestRetainedFrame,
        Optional<FrameId> newestRetainedFrame) {
    /** Copies result items. */
    public QueryPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        oldestRetainedFrame = Objects.requireNonNull(oldestRetainedFrame, "oldestRetainedFrame");
        newestRetainedFrame = Objects.requireNonNull(newestRetainedFrame, "newestRetainedFrame");
    }
}
