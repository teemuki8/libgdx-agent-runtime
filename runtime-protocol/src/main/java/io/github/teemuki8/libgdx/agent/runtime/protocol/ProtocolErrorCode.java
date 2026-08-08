package io.github.teemuki8.libgdx.agent.runtime.protocol;

/** Stable remote failure contract. */
public enum ProtocolErrorCode {
    SESSION_NOT_FOUND,
    FRAME_NOT_FOUND,
    ENTITY_NOT_FOUND,
    ENTITY_HISTORY_NOT_RETAINED,
    INVALID_QUERY,
    INVALID_RANGE,
    LIMIT_EXCEEDED,
    RUNTIME_CLOSED,
    CAPTURE_NOT_AVAILABLE,
    CAPABILITY_UNAVAILABLE,
    PROTOCOL_VERSION_UNSUPPORTED,
    RECORDING_EVICTED,
    INTERNAL_ERROR
}
