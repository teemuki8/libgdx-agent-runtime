package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable bounded result for one simulation-scoped assertion evaluation. */
public record SimulationAssertionResult(AssertionStatus status, String assertionType,
        SimulationAssertionScope scope, Optional<RuntimeValue> expected,
        Optional<RuntimeValue> observed, List<SimulationAssertionEvidence> evidence,
        boolean evidenceIncomplete, String message) {
    /** Validates and defensively copies result evidence. */
    public SimulationAssertionResult {
        Objects.requireNonNull(status, "status");
        IdentifierSupport.validate(assertionType, "assertion type");
        Objects.requireNonNull(scope, "scope");
        expected = Objects.requireNonNull(expected, "expected");
        observed = Objects.requireNonNull(observed, "observed");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.size() > scope.evidenceLimit()) {
            throw new IllegalArgumentException("simulation assertion evidence exceeds requested limit");
        }
        message = Objects.requireNonNull(message, "message");
        if (message.isBlank() || message.length() > 1_024) {
            throw new IllegalArgumentException("simulation assertion message is invalid");
        }
    }
}
