package io.github.teemuki8.libgdx.agent.runtime.libgdx;

/** Application simulation callback that acknowledges its actually executed delta in nanoseconds. */
@FunctionalInterface
public interface LibGdxFixedStepCallback {
    /** Executes one step and returns the delta actually used by application simulation. */
    long simulate(LibGdxFixedStepTick tick);
}
