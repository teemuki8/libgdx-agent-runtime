package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** One simulation assertion plus bounded generic evidence-completeness requirements. */
public record SimulationAssertionSpec(SimulationAssertion assertion,
        List<SimulationEvidenceRequirement> evidenceRequirements) {
    /** Maximum completeness requirements per assertion. */
    public static final int MAX_REQUIREMENTS = 8;

    /** Validates, orders, and defensively copies the specification. */
    public SimulationAssertionSpec {
        Objects.requireNonNull(assertion, "assertion");
        Objects.requireNonNull(evidenceRequirements, "evidenceRequirements");
        if (evidenceRequirements.size() > MAX_REQUIREMENTS) {
            throw new IllegalArgumentException("simulation assertion requirements exceed the hard bound");
        }
        ArrayList<SimulationEvidenceRequirement> copy = new ArrayList<>(evidenceRequirements);
        copy.forEach(value -> Objects.requireNonNull(value, "evidence requirement"));
        copy.sort(Comparator.comparing((SimulationEvidenceRequirement value) ->
                value.entityId().value()).thenComparing(SimulationEvidenceRequirement::property));
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).equals(copy.get(index))) {
                throw new IllegalArgumentException("duplicate simulation evidence requirement");
            }
        }
        evidenceRequirements = List.copyOf(copy);
    }

    /** Creates a specification without additional completeness requirements. */
    public static SimulationAssertionSpec of(SimulationAssertion assertion) {
        return new SimulationAssertionSpec(assertion, List.of());
    }
}
