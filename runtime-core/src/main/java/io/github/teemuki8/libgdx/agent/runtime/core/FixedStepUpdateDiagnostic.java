package io.github.teemuki8.libgdx.agent.runtime.core;

/** Closed diagnostic facts for one fixed-step accumulator update. */
public enum FixedStepUpdateDiagnostic {
    /** Incoming render time exceeded the accepted per-update limit. */
    RENDER_DELTA_CLAMPED,
    /** Render time was ignored because normal simulation was paused. */
    PAUSED_RENDER_TIME_IGNORED,
    /** Accumulated time exceeded the configured accumulator limit. */
    ACCUMULATOR_TIME_DROPPED,
    /** Whole steps were dropped after reaching the catch-up tick limit. */
    CATCH_UP_TICKS_DROPPED,
    /** Application simulation callback failed and its mutation outcome is unknown. */
    APPLICATION_CALLBACK_FAILED,
    /** Runtime capture failed after or alongside the application callback. */
    RUNTIME_CAPTURE_FAILED,
    /** Application-reported executed delta was outside the configured timeline limit. */
    EXECUTED_DELTA_INVALID,
    /** Application-reported simulation time exceeded the configured epoch limit. */
    SIMULATION_TIME_LIMIT_EXCEEDED,
    /** A simulation tick callback or its runtime capture failed. */
    TICK_FAILED,
    /** A legacy callback did not acknowledge its executed delta. */
    EXECUTED_DELTA_UNACKNOWLEDGED,
    /** The application-reported delta differed from the configured fixed step. */
    EXECUTED_DELTA_MISMATCH
}
