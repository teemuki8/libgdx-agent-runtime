package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** One registered input fact and latest retained tick/frame outcome evidence. */
public record RecordingInputEntry(long order, InputInjection injection)
        implements RecordingEntry {
    /** Validates input recording evidence. */
    public RecordingInputEntry {
        if (order < 0) {
            throw new IllegalArgumentException("recording order must be non-negative");
        }
        Objects.requireNonNull(injection, "injection");
    }
}
