package io.github.teemuki8.libgdx.agent.runtime.core;

/** Lifecycle of one idempotently correlated registered input injection. */
public enum InputInjectionState {
    /** Application-thread scheduling has not completed. */
    QUEUED,
    /** Input is retained for its future controlled tick. */
    SCHEDULED,
    /** The application handler executed successfully exactly once. */
    EXECUTED,
    /** Scheduling or application handling failed without retrying the handler. */
    FAILED
}
