package io.github.teemuki8.libgdx.agent.runtime.core;

/** Hard bounds for explicit runtime-to-UI bindings and frame mappings. */
public record UiCorrelationLimits(int registeredBindings, int queryResults,
        int retainedFrameCorrelations, int stringLength) {
    /** Validates supported bounds. */
    public UiCorrelationLimits {
        if (registeredBindings <= 0 || registeredBindings > 10_000
                || queryResults <= 0 || queryResults > 1_000
                || retainedFrameCorrelations <= 0 || retainedFrameCorrelations > 100_000
                || stringLength <= 0 || stringLength > 16_384) {
            throw new IllegalArgumentException("UI correlation limit is outside supported range");
        }
    }

    /** Returns conservative development defaults. */
    public static UiCorrelationLimits developmentDefaults() {
        return new UiCorrelationLimits(256, 100, 1_024, 512);
    }
}
