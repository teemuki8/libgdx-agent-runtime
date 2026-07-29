package io.github.teemuki8.libgdx.agent.runtime.libgdx;

import com.badlogic.gdx.Gdx;
import io.github.teemuki8.libgdx.agent.runtime.core.MonotonicClock;

/** Explicit libGDX/JDK timing helpers. */
public final class LibGdxTime {
    private LibGdxTime() {}

    /** Returns current libGDX graphics delta as non-negative nanoseconds. */
    public static long deltaNanos() {
        float delta = Gdx.graphics.getDeltaTime();
        if (!Float.isFinite(delta) || delta < 0) {
            throw new IllegalStateException("libGDX returned an invalid delta time");
        }
        return Math.round(delta * 1_000_000_000.0);
    }

    /** Returns a process-local monotonic JDK clock. */
    public static MonotonicClock monotonicClock() {
        return MonotonicClock.system();
    }
}
