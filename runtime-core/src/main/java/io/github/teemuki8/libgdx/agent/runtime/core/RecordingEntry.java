package io.github.teemuki8.libgdx.agent.runtime.core;

/** One deterministically ordered item in a bounded recording manifest. */
public sealed interface RecordingEntry permits RecordingActionEntry, RecordingInputEntry,
        RecordingFrameEntry, RecordingTickEntry {
    /** Returns the monotonic order assigned within the recording. */
    long order();
}
