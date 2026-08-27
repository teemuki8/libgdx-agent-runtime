package io.github.teemuki8.libgdx.agent.runtime.box2d;

import java.util.Objects;

/** Immutable application testimony for one Box2D 3 world. */
public record Box2dWorldSpec(int subStepCount, Box2dUnitTransform unitTransform) {
    /** Validates the bounded solver substep count and unit transform. */
    public Box2dWorldSpec {
        unitTransform = Objects.requireNonNull(unitTransform, "unitTransform");
        if (subStepCount < 1 || subStepCount > 16) {
            throw new IllegalArgumentException("Box2D substep count is outside range");
        }
    }
}
