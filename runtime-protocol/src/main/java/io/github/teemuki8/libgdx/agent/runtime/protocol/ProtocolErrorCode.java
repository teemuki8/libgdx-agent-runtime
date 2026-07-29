package io.github.teemuki8.libgdx.agent.runtime.protocol;

/** Stable remote failure contract. */
public enum ProtocolErrorCode {
    SESSION_NOT_FOUND,
    FRAME_NOT_FOUND,
    ENTITY_NOT_FOUND,
    INVALID_QUERY,
    INVALID_RANGE,
    LIMIT_EXCEEDED,
    RUNTIME_CLOSED,
    CAPTURE_NOT_AVAILABLE,
    PROTOCOL_VERSION_UNSUPPORTED,
    INTERNAL_ERROR
}
