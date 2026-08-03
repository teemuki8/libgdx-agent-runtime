package io.github.teemuki8.libgdx.agent.runtime.protocol;

/** Stable transport-neutral protocol version. */
public record ProtocolVersion(int major, int minor) {
    /** Frozen original read-only protocol version. */
    public static final ProtocolVersion V1 = new ProtocolVersion(1, 0);
    /** Extension-aware capabilities protocol version. */
    public static final ProtocolVersion V1_1 = new ProtocolVersion(1, 1);
    /** Latest implemented protocol version. */
    public static final ProtocolVersion CURRENT = V1_1;

    /** Validates version components. */
    public ProtocolVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("protocol version must be non-negative");
        }
    }
}
