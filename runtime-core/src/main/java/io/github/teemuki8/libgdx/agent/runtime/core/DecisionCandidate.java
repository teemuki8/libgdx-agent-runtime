package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** One explicitly accepted or rejected decision candidate. */
public record DecisionCandidate(
        EntityId entityId,
        Status status,
        Reason reason,
        List<RuntimeValue.Field> attributes) {
    /** Candidate evaluation outcome. */
    public enum Status {
        /** Candidate remained eligible. */
        ACCEPTED,
        /** Candidate was excluded. */
        REJECTED
    }

    /** Validates and orders candidate evidence. */
    public DecisionCandidate {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        var copy = new ArrayList<>(Objects.requireNonNull(attributes, "attributes"));
        copy.sort(Comparator.comparing(RuntimeValue.Field::name));
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).name().equals(copy.get(index).name())) {
                throw new IllegalArgumentException(
                        "duplicate candidate attribute: " + copy.get(index).name());
            }
        }
        attributes = List.copyOf(copy);
    }
}
