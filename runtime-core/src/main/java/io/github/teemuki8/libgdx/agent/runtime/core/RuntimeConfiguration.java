package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** Runtime feature switch and hard bounds. */
public record RuntimeConfiguration(boolean enabled, RuntimeLimits limits,
        FrameStagingLimits frameStagingLimits) {
    /** Validates configuration. */
    public RuntimeConfiguration {
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(frameStagingLimits, "frameStagingLimits");
    }

    /**
     * Compatibility constructor using development frame staging bounds.
     *
     * <p>Callers that configure only the runtime limits receive
     * {@link FrameStagingLimits#developmentDefaults()} for the per-frame cause ceiling.
     */
    public RuntimeConfiguration(boolean enabled, RuntimeLimits limits) {
        this(enabled, limits, FrameStagingLimits.developmentDefaults());
    }

    /** Returns enabled development defaults. */
    public static RuntimeConfiguration developmentDefaults() {
        return new RuntimeConfiguration(true, RuntimeLimits.developmentDefaults(),
                FrameStagingLimits.developmentDefaults());
    }

    /** Returns a cheap disabled configuration. */
    public static RuntimeConfiguration disabled() {
        return new RuntimeConfiguration(false, RuntimeLimits.developmentDefaults(),
                FrameStagingLimits.developmentDefaults());
    }
}
