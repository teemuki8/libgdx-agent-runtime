package io.github.teemuki8.libgdx.agent.runtime.protocol;

/** Stable transport-neutral protocol version. */
public record ProtocolVersion(int major, int minor) {
    /** Frozen original read-only protocol version. */
    public static final ProtocolVersion V1 = new ProtocolVersion(1, 0);
    /** Extension-aware capabilities protocol version. */
    public static final ProtocolVersion V1_1 = new ProtocolVersion(1, 1);
    /** Application-owned command dispatch protocol version. */
    public static final ProtocolVersion V1_2 = new ProtocolVersion(1, 2);
    /** Execution-epoch and baseline protocol version. */
    public static final ProtocolVersion V1_3 = new ProtocolVersion(1, 3);
    /** Explicit resettable-scenario protocol version. */
    public static final ProtocolVersion V1_4 = new ProtocolVersion(1, 4);
    /** Latest implemented protocol version. */
    public static final ProtocolVersion CURRENT = V1_4;

    /** Validates version components. */
    public ProtocolVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("protocol version must be non-negative");
        }
    }
}
