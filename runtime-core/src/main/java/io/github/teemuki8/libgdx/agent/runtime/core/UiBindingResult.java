package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;

/** Bounded deterministic UI binding result with explicit truncation evidence. */
public record UiBindingResult(UiBindingStatus status, List<UiBinding> bindings,
        int observedCount, int retainedCount, int limit, boolean truncated) {
    /** Validates and copies result evidence. */
    public UiBindingResult {
        Objects.requireNonNull(status, "status");
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        if (observedCount < 0 || retainedCount < 0 || retainedCount > observedCount
                || retainedCount != bindings.size() || limit <= 0 || retainedCount > limit
                || truncated != (observedCount > retainedCount)) {
            throw new IllegalArgumentException("UI binding result evidence is inconsistent");
        }
    }
}
