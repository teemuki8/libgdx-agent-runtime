package io.github.teemuki8.libgdx.agent.runtime.core;

/**
 * Application-owned bridge that schedules bounded runtime commands on the capture thread.
 *
 * <p>The runtime never creates a scheduler or worker for commands. Implementations normally enqueue
 * the supplied task into the application's existing render-loop queue.
 */
@FunctionalInterface
public interface ApplicationCommandDispatcher {
    /**
     * Enqueues one runtime-owned task for execution on the configured capture thread.
     * Implementations must preserve invocation order.
     */
    void dispatch(Runnable task);
}
