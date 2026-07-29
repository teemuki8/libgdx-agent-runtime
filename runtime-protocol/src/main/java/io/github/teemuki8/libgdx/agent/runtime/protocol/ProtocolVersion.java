package io.github.teemuki8.libgdx.agent.runtime.protocol;

/** Stable transport-neutral protocol version. */
public record ProtocolVersion(int major, int minor) {
    /** Only implemented V1 version. */
    public static final ProtocolVersion V1 = new ProtocolVersion(1, 0);

    /** Validates version components. */
    public ProtocolVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("protocol version must be non-negative");
        }
    }
}
