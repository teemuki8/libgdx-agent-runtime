package io.github.teemuki8.libgdx.agent.runtime.box2d;

import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;

/** Explicit stable registration of one application-owned native Box2D 3 ID. */
public interface Box2dRegistration<T> extends AutoCloseable {
    /** Returns the application-supplied stable ID. */
    String id();

    /** Returns the deterministic runtime entity ID. */
    EntityId runtimeEntityId();

    /**
     * Rebinds this stable registration to a recreated, live application-owned native ID.
     *
     * <p>The call requires the application/capture thread and no open runtime frame. World and
     * shape registrations retain their original copied declarations; unregister and register
     * again when that declaration changes.
     */
    void rebind(T value);

    /** Unregisters evidence without destroying the application-owned native resource. */
    @Override
    void close();
}
