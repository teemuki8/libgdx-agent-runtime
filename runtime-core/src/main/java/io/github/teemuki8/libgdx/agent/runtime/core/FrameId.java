package io.github.teemuki8.libgdx.agent.runtime.core;

/** Monotonically increasing frame identifier within a session. */
public record FrameId(long value) implements Comparable<FrameId> {
    /** Validates a non-negative frame number. */
    public FrameId {
        if (value < 0) {
            throw new IllegalArgumentException("frameId must be non-negative");
        }
    }

    @Override
    public int compareTo(FrameId other) {
        return Long.compare(value, other.value);
    }
}
