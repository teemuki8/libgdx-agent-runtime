package io.github.teemuki8.libgdx.agent.runtime.core;

/** Explicit reason a bounded simulation-control operation stopped. */
public enum ControlStopReason {
    /** The operation is queued or still executing. */
    PENDING,
    /** The requested state transition or tick count completed. */
    COMPLETED,
    /** A registered semantic condition became true. */
    CONDITION_SATISFIED,
    /** A declarative assertion passed. */
    ASSERTION_SATISFIED,
    /** The operation reached its maximum tick bound without satisfaction. */
    TICK_LIMIT,
    /** The operation reached its monotonic execution deadline. */
    TIMED_OUT,
    /** An application callback failed after zero or more successful ticks. */
    CALLBACK_FAILED,
    /** The requested operation was invalid for the current control state. */
    INVALID_STATE
}
