package io.github.teemuki8.libgdx.agent.runtime.core;

/** Application-owned simulation mutation that returns the delta it actually executed. */
@FunctionalInterface
public interface SimulationTickCallback {
    /** Executes one application simulation step and reports its actual delta in nanoseconds. */
    long simulate(long runtimeSuppliedDeltaNanos);
}
