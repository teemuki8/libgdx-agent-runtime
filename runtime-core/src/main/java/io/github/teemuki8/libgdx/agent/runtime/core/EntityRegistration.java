package io.github.teemuki8.libgdx.agent.runtime.core;

/** Idempotent handle that removes a registered provider without owning the game object. */
@FunctionalInterface
public interface EntityRegistration extends AutoCloseable {
    /** Unregisters the provider. */
    @Override
    void close();
}
