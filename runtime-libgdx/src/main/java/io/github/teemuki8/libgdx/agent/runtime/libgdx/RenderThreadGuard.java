package io.github.teemuki8.libgdx.agent.runtime.libgdx;

import java.util.Objects;

/** Explicit thread-affinity check for render-loop integration. */
public final class RenderThreadGuard {
    private final Thread owner;

    /** Creates a guard for the supplied render thread. */
    public RenderThreadGuard(Thread owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /** Creates a guard owned by the calling thread. */
    public static RenderThreadGuard currentThread() {
        return new RenderThreadGuard(Thread.currentThread());
    }

    /** Fails when called from any other thread. */
    public void check() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("operation requires the configured libGDX render thread");
        }
    }

    /** Returns the configured owner without exposing mutable game state. */
    public Thread owner() {
        return owner;
    }
}
