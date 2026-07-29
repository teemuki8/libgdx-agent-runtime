package io.github.teemuki8.libgdx.agent.runtime.libgdx;

import com.badlogic.gdx.math.Vector2;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import java.util.Objects;

/** Explicit converters from common libGDX types into immutable runtime values. */
public final class LibGdxValues {
    private LibGdxValues() {}

    /** Copies a libGDX vector without retaining the mutable instance. */
    public static RuntimeValue.Vector2Value vector2(Vector2 vector) {
        Objects.requireNonNull(vector, "vector");
        return RuntimeValues.vector2(vector.x, vector.y);
    }
}
