package io.github.teemuki8.libgdx.agent.runtime.core;

/** Closed terminal reason for one bounded input timeline operation. */
public enum InputTimelineStopReason {
    /** Every requested transition and tick completed within all bounds. */
    COMPLETED,
    /** The effective monotonic execution deadline expired. */
    TIMED_OUT,
    /** Required epoch, controller, pause, or fixed-step state changed. */
    LIFECYCLE_CHANGED,
    /** A registered input transition failed. */
    INPUT_FAILED,
    /** An exact application-owned simulation tick failed. */
    TICK_FAILED,
    /** Complete bounded terminal evidence could not be retained. */
    EVIDENCE_LIMIT,
    /** Exclusive input-execution cleanup failed. */
    CLEANUP_FAILED
}
