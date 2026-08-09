package io.github.teemuki8.libgdx.agent.runtime.box2d;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Immutable application testimony for Box2D world settings not universally readable.
 *
 * <p>The optional inverse step must remain positive and finite when narrowed to Box2D's float API.
 */
public record Box2dWorldSpec(boolean sleepingAllowed, boolean warmStarting,
        boolean continuousPhysics, int velocityIterations, int positionIterations,
        OptionalDouble inverseStep, Box2dUnitTransform unitTransform) {
    /** Validates solver settings, optional reaction-force input, and unit transform. */
    public Box2dWorldSpec {
        inverseStep = Objects.requireNonNull(inverseStep, "inverseStep");
        unitTransform = Objects.requireNonNull(unitTransform, "unitTransform");
        if (velocityIterations <= 0 || velocityIterations > 1_000
                || positionIterations <= 0 || positionIterations > 1_000) {
            throw new IllegalArgumentException("Box2D world specification is outside range");
        }
        if (inverseStep.isPresent()) {
            double value = inverseStep.orElseThrow();
            float box2dValue = (float) value;
            if (!Double.isFinite(value) || !Float.isFinite(box2dValue) || box2dValue <= 0) {
                throw new IllegalArgumentException("Box2D world specification is outside range");
            }
        }
    }
}
