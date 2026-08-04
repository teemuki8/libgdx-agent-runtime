package io.github.teemuki8.libgdx.agent.runtime.core;

/** Configurable hard bounds for retained checkpoint handles and operation evidence. */
public record CheckpointLimits(int retainedCheckpoints, int retainedOperations,
        int descriptionLength) {
    /** Validates supported checkpoint bounds. */
    public CheckpointLimits {
        if (retainedCheckpoints <= 0 || retainedCheckpoints > 1_000
                || retainedOperations <= 0 || retainedOperations > 100_000
                || descriptionLength <= 0 || descriptionLength > 16_384) {
            throw new IllegalArgumentException("checkpoint limit is outside the supported range");
        }
    }

    /** Returns conservative development defaults. */
    public static CheckpointLimits developmentDefaults() {
        return new CheckpointLimits(32, 256, 512);
    }
}
