package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Bounded first-difference evidence from both repeated runs. */
public record DeterminismDifference(DeterminismDifferenceKind kind, Optional<String> fact,
        Optional<RuntimeValue> left, Optional<RuntimeValue> right) {
    /** Validates optional closed evidence. */
    public DeterminismDifference {
        Objects.requireNonNull(kind, "kind");
        fact = Objects.requireNonNull(fact, "fact");
        left = Objects.requireNonNull(left, "left");
        right = Objects.requireNonNull(right, "right");
        fact.ifPresent(value -> {
            if (value.isBlank() || value.length() > 512) {
                throw new IllegalArgumentException("difference fact is outside the public bound");
            }
        });
    }
}
