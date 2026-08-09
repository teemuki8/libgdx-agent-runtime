package io.github.teemuki8.libgdx.agent.runtime.core;

/** Closed outcome for one attempted simulation tick. */
public enum SimulationTickOutcome {
    /** Callback, acknowledgement, and capture completed with matching timing. */
    COMPLETED,
    /** Callback and capture completed but supplied/configured and executed deltas differ. */
    DELTA_MISMATCH,
    /** A legacy callback completed without acknowledging its executed delta. */
    UNACKNOWLEDGED,
    /** The application callback failed; a resulting frame may still have completed. */
    CALLBACK_FAILED,
    /** Capture failed after the application callback completed. */
    CAPTURE_FAILED,
    /** Both the application callback and capture failed. */
    CALLBACK_AND_CAPTURE_FAILED,
    /** The reported delta was outside configured bounds. */
    REPORTED_DELTA_INVALID,
    /** Accumulating the reported delta exceeded the configured epoch-time bound. */
    TIME_LIMIT_EXCEEDED
}
