package io.github.teemuki8.libgdx.agent.runtime.core;

/** Observable bounded command lifecycle. */
public enum CommandState {
    REJECTED,
    QUEUED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED
}
