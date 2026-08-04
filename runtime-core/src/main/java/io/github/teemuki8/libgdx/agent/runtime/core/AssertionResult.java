package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable bounded result for one declarative assertion evaluation. */
public record AssertionResult(AssertionStatus status, String assertionType, AssertionScope scope,
        Optional<RuntimeValue> expected, Optional<RuntimeValue> observed,
        List<AssertionEvidence> evidence, boolean evidenceIncomplete, String message) {
    /** Validates and defensively copies result evidence. */
    public AssertionResult {
        Objects.requireNonNull(status, "status");
        IdentifierSupport.validate(assertionType, "assertion type");
        Objects.requireNonNull(scope, "scope");
        expected = Objects.requireNonNull(expected, "expected");
        observed = Objects.requireNonNull(observed, "observed");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.size() > scope.evidenceLimit()) {
            throw new IllegalArgumentException("assertion evidence exceeds the requested limit");
        }
        message = Objects.requireNonNull(message, "message");
        if (message.isBlank() || message.length() > 1_024) {
            throw new IllegalArgumentException("assertion message is invalid");
        }
    }
}
