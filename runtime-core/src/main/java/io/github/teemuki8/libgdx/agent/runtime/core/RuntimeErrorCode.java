package io.github.teemuki8.libgdx.agent.runtime.core;

/** Stable local failure categories. */
public enum RuntimeErrorCode {
    /** Lifecycle method was called in an invalid state. */
    INVALID_LIFECYCLE,
    /** Operation ran on a thread other than the capture owner. */
    WRONG_THREAD,
    /** Registration would create an ambiguous identity. */
    DUPLICATE_ENTITY,
    /** Runtime no longer accepts capture operations. */
    RUNTIME_CLOSED,
    /** Requested retained frame does not exist. */
    FRAME_NOT_FOUND,
    /** Requested retained entity does not exist. */
    ENTITY_NOT_FOUND,
    /** Requested entity history is not retained by the bounded runtime. */
    ENTITY_HISTORY_NOT_RETAINED,
    /** Query fields are invalid. */
    INVALID_QUERY,
    /** A configured hard limit was exceeded by caller input. */
    LIMIT_EXCEEDED,
    /** Requested recording existed but its bounded retention entry was evicted. */
    RECORDING_EVICTED
}
