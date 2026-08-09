package io.github.teemuki8.libgdx.agent.runtime.core;

/** Session-monotonic simulation tick identity that is never reused. */
public record SimulationTickId(long value) implements Comparable<SimulationTickId> {
    /** Validates a positive tick identity. */
    public SimulationTickId {
        if (value <= 0) {
            throw new IllegalArgumentException("simulation tick ID must be positive");
        }
    }

    @Override
    public int compareTo(SimulationTickId other) {
        return Long.compare(value, other.value);
    }
}
