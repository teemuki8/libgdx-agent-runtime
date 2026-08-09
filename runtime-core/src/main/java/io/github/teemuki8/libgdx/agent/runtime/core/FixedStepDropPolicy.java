package io.github.teemuki8.libgdx.agent.runtime.core;

/** Closed policy for excess whole accumulator steps. */
public enum FixedStepDropPolicy {
    /** Drops excess whole steps while retaining the sub-step remainder. */
    DROP_WHOLE_TICKS_KEEP_REMAINDER
}
