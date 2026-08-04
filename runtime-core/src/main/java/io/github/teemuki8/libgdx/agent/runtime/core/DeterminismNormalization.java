package io.github.teemuki8.libgdx.agent.runtime.core;

/** Fixed safe normalization applied by determinism comparison. */
public enum DeterminismNormalization {
    EPOCH_RELATIVE_TICK, EXCLUDE_RUNTIME_IDENTIFIERS, EXCLUDE_WALL_CLOCK
}
