package io.github.teemuki8.libgdx.agent.runtime.core;

/** Explicit owner of the single capture frame belonging to each simulation tick. */
public enum SimulationFrameOwnership {
    /** The runtime opens and completes a frame around the application callback (the default). */
    RUNTIME,
    /**
     * The application callback opens and completes exactly one frame with the supplied delta.
     * Registered inputs run before the callback, outside capture, so they can enqueue intent
     * before a gameplay world drains its commands. Events are emitted by the tick inside its frame.
     */
    CALLBACK
}
