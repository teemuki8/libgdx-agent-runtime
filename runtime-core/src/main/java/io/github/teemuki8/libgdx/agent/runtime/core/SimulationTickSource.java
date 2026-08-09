package io.github.teemuki8.libgdx.agent.runtime.core;

/** Application-reported pause state at one simulation tick boundary. */
public enum SimulationTickSource {
    /** The application reported the simulation as running. */
    RUNNING,
    /** The application reported the simulation as paused for controlled execution. */
    PAUSED
}
