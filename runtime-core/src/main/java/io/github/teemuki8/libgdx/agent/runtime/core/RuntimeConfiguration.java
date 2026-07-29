package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** Runtime feature switch and hard bounds. */
public record RuntimeConfiguration(boolean enabled, RuntimeLimits limits) {
    /** Validates configuration. */
    public RuntimeConfiguration {
        Objects.requireNonNull(limits, "limits");
    }

    /** Returns enabled development defaults. */
    public static RuntimeConfiguration developmentDefaults() {
        return new RuntimeConfiguration(true, RuntimeLimits.developmentDefaults());
    }

    /** Returns a cheap disabled configuration. */
    public static RuntimeConfiguration disabled() {
        return new RuntimeConfiguration(false, RuntimeLimits.developmentDefaults());
    }
}
