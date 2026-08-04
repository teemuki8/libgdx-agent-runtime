package io.github.teemuki8.libgdx.agent.runtime.core;

/** Application-owned checkpoint creation, restore, and cleanup callbacks. */
public interface CheckpointProvider {
    /** Creates one opaque handle for the application's current quiescent state. */
    CheckpointHandle create();

    /** Restores application state from one handle previously returned by this provider. */
    void restore(CheckpointHandle handle);

    /** Releases one handle after eviction or runtime close. */
    void dispose(CheckpointHandle handle);
}
