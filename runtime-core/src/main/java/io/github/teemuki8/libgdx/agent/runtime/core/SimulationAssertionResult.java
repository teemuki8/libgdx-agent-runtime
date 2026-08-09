package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable bounded result for one simulation-scoped assertion evaluation. */
public record SimulationAssertionResult(AssertionStatus status, String assertionType,
        SimulationAssertionScope scope, Optional<RuntimeValue> expected,
        Optional<RuntimeValue> observed, List<SimulationAssertionEvidence> evidence,
        boolean evidenceIncomplete, String message) {
    private static final Set<String> ASSERTION_TYPES = Set.of(
            "allOf", "entityExists", "eventCount", "objectListContains", "propertyEquals",
            "scalarApproximatelyEquals", "vectorApproximatelyEquals",
            "vectorDistanceApproximatelyEquals", "vectorInArea", "vectorMagnitudeAtMost",
            "wrappedAngleApproximatelyEquals");

    /** Validates and defensively copies result evidence. */
    public SimulationAssertionResult {
        Objects.requireNonNull(status, "status");
        IdentifierSupport.validate(assertionType, "assertion type");
        if (!ASSERTION_TYPES.contains(assertionType)) {
            throw new IllegalArgumentException("simulation assertion type is unknown");
        }
        Objects.requireNonNull(scope, "scope");
        expected = Objects.requireNonNull(expected, "expected");
        observed = Objects.requireNonNull(observed, "observed");
        expected.ifPresent(SimulationAssertionValueBounds::validate);
        observed.ifPresent(SimulationAssertionValueBounds::validate);
        ArrayList<SimulationAssertionEvidence> ordered = new ArrayList<>(
                Objects.requireNonNull(evidence, "evidence"));
        ordered.forEach(value -> {
            Objects.requireNonNull(value, "evidence value");
            if (!value.executionEpochId().equals(scope.executionEpochId())
                    || value.epochTick() < scope.fromEpochTick()
                    || value.epochTick() > scope.toEpochTick()) {
                throw new IllegalArgumentException(
                        "simulation assertion evidence is outside its scope");
            }
        });
        ordered.sort(Comparator.comparingLong(SimulationAssertionEvidence::epochTick)
                .thenComparing(value -> value.entityId().map(EntityId::value).orElse(""))
                .thenComparing(value -> value.property().orElse(""))
                .thenComparing(SimulationAssertionEvidence::kind));
        evidence = List.copyOf(ordered);
        if (evidence.size() > scope.evidenceLimit()) {
            throw new IllegalArgumentException("simulation assertion evidence exceeds requested limit");
        }
        if (status == AssertionStatus.INCONCLUSIVE && !evidenceIncomplete) {
            throw new IllegalArgumentException(
                    "inconclusive simulation assertion must report incomplete evidence");
        }
        message = Objects.requireNonNull(message, "message");
        if (message.isBlank() || message.length() > 1_024) {
            throw new IllegalArgumentException("simulation assertion message is invalid");
        }
    }
}
