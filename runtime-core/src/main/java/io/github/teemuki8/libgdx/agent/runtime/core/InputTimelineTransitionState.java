package io.github.teemuki8.libgdx.agent.runtime.core;

/** Closed execution state for one requested input timeline transition. */
public enum InputTimelineTransitionState {
    /** The registered application input handler completed successfully. */
    EXECUTED,
    /** The registered application input handler was attempted and failed. */
    FAILED,
    /** Execution stopped before the transition was attempted. */
    NOT_EXECUTED
}
