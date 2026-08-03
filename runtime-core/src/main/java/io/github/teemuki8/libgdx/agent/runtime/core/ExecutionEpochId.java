package io.github.teemuki8.libgdx.agent.runtime.core;

/** Monotonic execution-history segment within one runtime session. */
public record ExecutionEpochId(long value) implements Comparable<ExecutionEpochId> {
    public ExecutionEpochId {
        if (value < 0) {
            throw new IllegalArgumentException("execution epoch ID must be non-negative");
        }
    }

    @Override public int compareTo(ExecutionEpochId other) {
        return Long.compare(value, other.value);
    }
}
