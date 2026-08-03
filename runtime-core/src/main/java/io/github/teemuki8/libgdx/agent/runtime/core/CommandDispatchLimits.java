package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;

/** Hard bounds for application command dispatch and retained outcomes. */
public record CommandDispatchLimits(
        int queuedCommands,
        int retainedResults,
        int retainedRequestIds,
        long maximumTimeoutNanos,
        int diagnosticLength) {
    /** Absolute public bound for diagnostic text. */
    public static final int MAX_DIAGNOSTIC_LENGTH = 16_384;
    /** Absolute public bound for a configured command timeout. */
    public static final long MAXIMUM_TIMEOUT_NANOS = Duration.ofHours(24).toNanos();
    private static final int MAX_COUNT = 100_000;

    /** Validates all bounds. */
    public CommandDispatchLimits {
        requireBound(queuedCommands, MAX_COUNT, "queuedCommands");
        requireBound(retainedResults, MAX_COUNT, "retainedResults");
        requireBound(retainedRequestIds, MAX_COUNT, "retainedRequestIds");
        if (maximumTimeoutNanos <= 0 || maximumTimeoutNanos > MAXIMUM_TIMEOUT_NANOS) {
            throw new IllegalArgumentException("maximumTimeoutNanos must be between 1 and "
                    + MAXIMUM_TIMEOUT_NANOS);
        }
        requireBound(diagnosticLength, MAX_DIAGNOSTIC_LENGTH, "diagnosticLength");
    }

    /** Conservative development defaults. */
    public static CommandDispatchLimits developmentDefaults() {
        return new CommandDispatchLimits(
                256, 1_000, 1_000, Duration.ofSeconds(30).toNanos(), 512);
    }

    private static void requireBound(int value, int maximum, String name) {
        if (value <= 0 || value > maximum) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maximum);
        }
    }
}
