package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;

/** Bounded frame-correlation page with retention eviction evidence. */
public record UiFrameCorrelationPage(List<UiFrameCorrelation> items, boolean hasMore,
        long evictedCount) {
    /** Validates and copies page values. */
    public UiFrameCorrelationPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (evictedCount < 0) {
            throw new IllegalArgumentException("evictedCount must be non-negative");
        }
    }
}
