package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

/** Explicit evidence that a bounded dimension was not retained in full. */
public record Truncation(String dimension, long observed, long retained, long limit) {
    /** Validates internally consistent counts. */
    public Truncation {
        IdentifierSupport.validate(dimension, "truncation dimension");
        if (observed < 0 || retained < 0 || limit < 0 || retained > observed || retained > limit) {
            throw new IllegalArgumentException("invalid truncation counts");
        }
    }
}
