package io.github.teemuki8.libgdx.agent.runtime.core;

/** Explicit reason that a completed frame begins a new execution epoch. */
public enum BaselineKind {
    INITIAL,
    SCENARIO_RESET,
    CHECKPOINT_RESTORE
}
