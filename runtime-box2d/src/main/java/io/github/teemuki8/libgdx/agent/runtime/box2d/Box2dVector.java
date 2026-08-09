package io.github.teemuki8.libgdx.agent.runtime.box2d;

/** Immutable finite two-dimensional value used by unit conversion helpers. */
public record Box2dVector(double x, double y) {
    /** Rejects non-finite components. */
    public Box2dVector {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Box2D vector components must be finite");
        }
    }
}
