package io.github.teemuki8.libgdx.agent.runtime.box2d;

import java.util.Objects;
import java.util.OptionalDouble;

/** Immutable application testimony for Box2D world settings not universally readable. */
public record Box2dWorldSpec(boolean sleepingAllowed, boolean warmStarting,
        boolean continuousPhysics, int velocityIterations, int positionIterations,
        OptionalDouble inverseStep, Box2dUnitTransform unitTransform) {
    /** Validates solver settings, optional reaction-force input, and unit transform. */
    public Box2dWorldSpec {
        inverseStep = Objects.requireNonNull(inverseStep, "inverseStep");
        unitTransform = Objects.requireNonNull(unitTransform, "unitTransform");
        if (velocityIterations <= 0 || velocityIterations > 1_000
                || positionIterations <= 0 || positionIterations > 1_000
                || inverseStep.isPresent() && (!Double.isFinite(inverseStep.orElseThrow())
                        || inverseStep.orElseThrow() <= 0)) {
            throw new IllegalArgumentException("Box2D world specification is outside range");
        }
    }
}
