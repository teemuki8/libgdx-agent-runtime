package io.github.teemuki8.libgdx.agent.runtime.libgdx;

/** Canonical integer and float representations supplied to one libGDX simulation tick. */
public record LibGdxFixedStepTick(long fixedStepNanos, float fixedStepSeconds) {
    /** Validates positive finite step representations. */
    public LibGdxFixedStepTick {
        if (fixedStepNanos <= 0 || !Float.isFinite(fixedStepSeconds)
                || fixedStepSeconds <= 0) {
            throw new IllegalArgumentException("invalid libGDX fixed-step representations");
        }
    }
}
