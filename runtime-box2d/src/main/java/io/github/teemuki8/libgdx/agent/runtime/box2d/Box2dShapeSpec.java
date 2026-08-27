package io.github.teemuki8.libgdx.agent.runtime.box2d;

/** Immutable declaration for a registered Box2D 3 shape. */
public record Box2dShapeSpec() {
    /** Returns the declaration used when all evidence is readable from the native shape ID. */
    public static Box2dShapeSpec defaults() {
        return new Box2dShapeSpec();
    }
}
