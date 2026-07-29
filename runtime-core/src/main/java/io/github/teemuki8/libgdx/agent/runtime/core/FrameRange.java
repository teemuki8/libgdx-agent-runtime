package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** Inclusive frame range. */
public record FrameRange(FrameId from, FrameId to) {
    /** Validates ascending endpoints. */
    public FrameRange {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.compareTo(to) > 0) {
            throw new IllegalArgumentException("frame range must be ascending");
        }
    }

    /** Creates an inclusive range from raw frame numbers. */
    public static FrameRange of(long from, long to) {
        return new FrameRange(new FrameId(from), new FrameId(to));
    }
}
