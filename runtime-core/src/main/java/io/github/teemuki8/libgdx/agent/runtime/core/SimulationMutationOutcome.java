package io.github.teemuki8.libgdx.agent.runtime.core;

/** Whether completion of application mutation is known for one attempted tick. */
public enum SimulationMutationOutcome {
    /** The application callback returned normally. */
    KNOWN_COMPLETED,
    /** A throwing callback may have partially mutated application state. */
    UNKNOWN
}
