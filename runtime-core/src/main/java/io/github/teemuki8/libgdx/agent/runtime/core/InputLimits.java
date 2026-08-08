package io.github.teemuki8.libgdx.agent.runtime.core;

/** Configurable hard bounds for registered input types, scheduling, and retained evidence. */
public record InputLimits(int registeredInputs, int parametersPerInput, int queuedInputs,
        int retainedInjections, int futureTicks, int stringLength) {
    /** Validates supported input bounds. */
    public InputLimits {
        if (registeredInputs <= 0 || registeredInputs > 1_000
                || parametersPerInput <= 0 || parametersPerInput > 100
                || queuedInputs <= 0 || queuedInputs > 10_000
                || retainedInjections <= 0 || retainedInjections > 10_000
                || futureTicks <= 0 || futureTicks > 1_000_000
                || stringLength <= 0 || stringLength > 16_384) {
            throw new IllegalArgumentException("input limit is outside the supported range");
        }
        if (stringLength < ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY) {
            throw new IllegalArgumentException("stringLength must be at least "
                    + ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY);
        }
    }

    /** Returns conservative development defaults. */
    public static InputLimits developmentDefaults() {
        return new InputLimits(128, 32, 256, 256, 1_000, 1_024);
    }
}
