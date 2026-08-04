package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** Immutable request for bounded repeated scenario execution and comparison. */
public record DeterminismSpec(String scenarioId, long randomSeed,
        RuntimeValue.ObjectValue configuration, int repeatCount, int ticksPerRepeat,
        long deltaNanos, DeterminismProfile profile) {
    /** Validates request-local invariants; registry limits apply additional hard bounds. */
    public DeterminismSpec {
        IdentifierSupport.validate(scenarioId, "determinism scenario id");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(profile, "profile");
        if (repeatCount < 2 || ticksPerRepeat <= 0 || deltaNanos <= 0) {
            throw new IllegalArgumentException("invalid determinism execution dimensions");
        }
    }
}
