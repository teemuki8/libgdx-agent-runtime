package io.github.teemuki8.libgdx.agent.runtime.core;

/**
 * Hard per-frame staging bounds for facts retained while a frame is open.
 *
 * <p>Events and decisions are staged against {@link RuntimeLimits#retainedEvents()} and
 * {@link RuntimeLimits#decisionsPerFrame()} respectively; this record supplies the separate
 * ceiling for explicit change causes registered through
 * {@link AgentRuntime#causeNextChange(EntityId, String, ChangeCause)}. Exceeding a ceiling at
 * insertion time drops the fact without constructing or copying retained objects, and the frame
 * snapshot reports the saturating observed count through its truncation evidence.
 */
public record FrameStagingLimits(int causesPerFrame) {
    private static final FrameStagingLimits DEVELOPMENT = new FrameStagingLimits(256);

    /** Validates that every configured limit is positive. */
    public FrameStagingLimits {
        if (causesPerFrame <= 0) {
            throw new IllegalArgumentException("causesPerFrame must be positive");
        }
    }

    /** Returns conservative development defaults. */
    public static FrameStagingLimits developmentDefaults() {
        return DEVELOPMENT;
    }
}
