package io.github.teemuki8.libgdx.agent.runtime.box2d;

import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;

/** Explicit stable registration of one application-owned native Box2D object. */
public interface Box2dRegistration<T> extends AutoCloseable {
    /** Returns the application-supplied stable ID. */
    String id();

    /** Returns the deterministic runtime entity ID. */
    EntityId runtimeEntityId();

    /** Rebinds this stable registration to a recreated application-owned native object. */
    void rebind(T value);

    /** Unregisters evidence without destroying or disposing the native object. */
    @Override
    void close();
}
