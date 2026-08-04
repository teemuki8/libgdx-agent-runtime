package io.github.teemuki8.libgdx.agent.runtime.core;

/** Deterministic declarative assertion outcome. */
public enum AssertionStatus {
    /** Complete or decisive evidence satisfies the assertion. */
    PASS,
    /** Complete or decisive evidence contradicts the assertion. */
    FAIL,
    /** Missing, truncated, or failed capture evidence could change the outcome. */
    INCONCLUSIVE
}
