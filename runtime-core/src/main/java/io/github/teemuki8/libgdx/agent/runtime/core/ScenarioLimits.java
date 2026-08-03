package io.github.teemuki8.libgdx.agent.runtime.core;

/** Configurable hard bounds for registered scenarios and reset evidence. */
public record ScenarioLimits(int registeredScenarios, int retainedResetResults) {
    public static final int MAX_REGISTERED_SCENARIOS = 1_000;
    public static final int MAX_RETAINED_RESULTS = 10_000;

    public ScenarioLimits {
        if (registeredScenarios <= 0 || registeredScenarios > MAX_REGISTERED_SCENARIOS) {
            throw new IllegalArgumentException("registeredScenarios is outside the supported range");
        }
        if (retainedResetResults <= 0 || retainedResetResults > MAX_RETAINED_RESULTS) {
            throw new IllegalArgumentException("retainedResetResults is outside the supported range");
        }
    }

    public static ScenarioLimits developmentDefaults() {
        return new ScenarioLimits(128, 256);
    }
}
