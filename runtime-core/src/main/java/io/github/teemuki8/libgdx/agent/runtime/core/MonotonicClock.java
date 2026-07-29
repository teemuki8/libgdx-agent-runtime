package io.github.teemuki8.libgdx.agent.runtime.core;

/** Injectable non-decreasing time source used for ordering and elapsed time. */
@FunctionalInterface
public interface MonotonicClock {
    /** Returns non-negative monotonic nanoseconds. */
    long nanoTime();

    /** Returns the JDK monotonic clock. */
    static MonotonicClock system() {
        long origin = System.nanoTime();
        return () -> System.nanoTime() - origin;
    }
}
