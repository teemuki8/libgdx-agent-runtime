package io.github.teemuki8.libgdx.agent.runtime.core;

/** Observable runtime lifecycle. */
public enum RuntimeStatus {
    /** Built but not started. */
    CREATED,
    /** Capturing completed frames. */
    RUNNING,
    /** Disabled and retaining no snapshots. */
    DISABLED,
    /** Closed; retained completed history remains readable. */
    CLOSED
}
