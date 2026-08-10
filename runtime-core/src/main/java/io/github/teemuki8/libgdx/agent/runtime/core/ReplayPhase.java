package io.github.teemuki8.libgdx.agent.runtime.core;

/** Closed replay comparison phase for first-divergence evidence. */
public enum ReplayPhase {
    /** Recreated origin evidence before simulation tick one. */
    BASELINE,
    /** Evidence captured after one positive epoch-relative simulation tick. */
    SIMULATION_TICK
}
