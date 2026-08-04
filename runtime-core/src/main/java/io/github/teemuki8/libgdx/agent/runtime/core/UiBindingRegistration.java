package io.github.teemuki8.libgdx.agent.runtime.core;

/** Idempotent application-owned lifecycle handle for one registered UI binding. */
@FunctionalInterface
public interface UiBindingRegistration extends AutoCloseable {
    /** Unregisters the binding. */
    @Override
    void close();
}
