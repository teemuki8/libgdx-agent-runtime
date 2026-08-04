package io.github.teemuki8.libgdx.agent.runtime.core;

/** Explicit reason a bounded recording stopped accepting evidence. */
public enum RecordingStopReason {
    REQUESTED, ITEM_LIMIT, TICK_SPAN_LIMIT, DURATION_LIMIT, ENCODED_SIZE_LIMIT, RUNTIME_CLOSED
}
