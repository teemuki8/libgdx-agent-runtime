package io.github.teemuki8.libgdx.agent.runtime.core;

/** Hard bounds for application command dispatch and retained outcomes. */
public record CommandDispatchLimits(
        int queuedCommands,
        int retainedResults,
        int retainedRequestIds,
        int diagnosticLength) {
    private static final int MAX_BOUND = 100_000;

    /** Validates all bounds. */
    public CommandDispatchLimits {
        requireBound(queuedCommands, "queuedCommands");
        requireBound(retainedResults, "retainedResults");
        requireBound(retainedRequestIds, "retainedRequestIds");
        requireBound(diagnosticLength, "diagnosticLength");
    }

    /** Conservative development defaults. */
    public static CommandDispatchLimits developmentDefaults() {
        return new CommandDispatchLimits(256, 1_000, 1_000, 512);
    }

    private static void requireBound(int value, String name) {
        if (value <= 0 || value > MAX_BOUND) {
            throw new IllegalArgumentException(name + " must be between 1 and " + MAX_BOUND);
        }
    }
}
