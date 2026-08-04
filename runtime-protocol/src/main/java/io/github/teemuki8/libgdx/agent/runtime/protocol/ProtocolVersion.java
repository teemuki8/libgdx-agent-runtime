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
    /** Explicit fact-attribution and metadata-query protocol version. */
    public static final ProtocolVersion V1_5 = new ProtocolVersion(1, 5);
    /** Explicit typed semantic-action protocol version. */
    public static final ProtocolVersion V1_6 = new ProtocolVersion(1, 6);
    /** Closed declarative runtime assertion protocol version. */
    public static final ProtocolVersion V1_7 = new ProtocolVersion(1, 7);
    /** Application-owned bounded simulation-control protocol version. */
    public static final ProtocolVersion V1_8 = new ProtocolVersion(1, 8);
    /** Explicit registered input scheduling protocol version. */
    public static final ProtocolVersion V1_9 = new ProtocolVersion(1, 9);
    /** Application-owned opaque checkpoint protocol version. */
    public static final ProtocolVersion V1_10 = new ProtocolVersion(1, 10);
    /** Latest implemented protocol version. */
    public static final ProtocolVersion CURRENT = V1_10;

    /** Validates version components. */
    public ProtocolVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("protocol version must be non-negative");
        }
    }
}
