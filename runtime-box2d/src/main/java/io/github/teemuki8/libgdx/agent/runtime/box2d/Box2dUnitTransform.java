package io.github.teemuki8.libgdx.agent.runtime.box2d;

import java.util.Objects;

/** Explicit conversion between authoritative physics metres and application render units. */
public record Box2dUnitTransform(double renderUnitsPerMeter) {
    /** Requires one positive finite scale. */
    public Box2dUnitTransform {
        if (!Double.isFinite(renderUnitsPerMeter) || renderUnitsPerMeter <= 0) {
            throw new IllegalArgumentException("render units per metre must be positive and finite");
        }
    }

    /** Converts one physics-metre scalar to render units. */
    public double physicsToRender(double meters) {
        return finite(meters * renderUnitsPerMeter);
    }

    /** Converts one render-unit scalar to physics metres. */
    public double renderToPhysics(double renderUnits) {
        return finite(renderUnits / renderUnitsPerMeter);
    }

    /** Converts an immutable physics vector to render units. */
    public Box2dVector physicsToRender(Box2dVector meters) {
        Objects.requireNonNull(meters, "meters");
        return new Box2dVector(physicsToRender(meters.x()), physicsToRender(meters.y()));
    }

    /** Converts an immutable render vector to physics metres. */
    public Box2dVector renderToPhysics(Box2dVector renderUnits) {
        Objects.requireNonNull(renderUnits, "renderUnits");
        return new Box2dVector(renderToPhysics(renderUnits.x()), renderToPhysics(renderUnits.y()));
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("unit conversion result must be finite");
        }
        return value;
    }
}
