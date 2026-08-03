package io.github.teemuki8.libgdx.agent.runtime.core;

/** Configurable hard bounds for semantic action registration and evidence. */
public record ActionLimits(int registeredActions, int parametersPerAction,
        int retainedInvocations, int stringLength) {
    public ActionLimits {
        if (registeredActions <= 0 || registeredActions > 1_000
                || parametersPerAction <= 0 || parametersPerAction > 100
                || retainedInvocations <= 0 || retainedInvocations > 10_000
                || stringLength <= 0 || stringLength > 16_384) {
            throw new IllegalArgumentException("action limit is outside the supported range");
        }
    }

    public static ActionLimits developmentDefaults() {
        return new ActionLimits(128, 32, 256, 1_024);
    }
}
