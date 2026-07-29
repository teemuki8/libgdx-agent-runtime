package io.github.teemuki8.libgdx.agent.runtime.libgdx;

import com.badlogic.gdx.Gdx;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityRegistration;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import java.util.Objects;

/** Optional explicitly registered basic application metrics. */
public final class LibGdxMetrics {
    private LibGdxMetrics() {}

    /** Registers one allowlisted metrics entity and returns its removal handle. */
    public static EntityRegistration register(AgentRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return runtime.entities().register(
                EntityId.of("libgdx-application"),
                EntityType.of("libgdx.metrics"),
                () -> "libGDX application",
                inspector -> inspector
                        .property("applicationType", () ->
                                RuntimeValues.enumValue(Gdx.app.getType().name()))
                        .property("deltaTime", () ->
                                RuntimeValues.decimal(Gdx.graphics.getDeltaTime()))
                        .property("framesPerSecond",
                                () -> (long) Gdx.graphics.getFramesPerSecond())
                        .property("viewport", () -> RuntimeValues.vector2(
                                Gdx.graphics.getWidth(), Gdx.graphics.getHeight())));
    }
}
