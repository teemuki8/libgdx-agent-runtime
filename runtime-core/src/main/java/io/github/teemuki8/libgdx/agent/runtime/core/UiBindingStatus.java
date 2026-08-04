package io.github.teemuki8.libgdx.agent.runtime.core;

/** Explicit outcome of one bounded runtime/UI binding lookup. */
public enum UiBindingStatus {
    MATCHED,
    MISSING,
    EXPIRED,
    AMBIGUOUS
}
