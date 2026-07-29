package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Exact filters for decision traces. */
public record DecisionQuery(
        FrameRange range,
        Optional<DecisionType> type,
        Optional<EntityId> actor,
        Optional<EntityId> chosenCandidate,
        Optional<String> reasonCode,
        int limit) {
    /** Validates filters and limit. */
    public DecisionQuery {
        Objects.requireNonNull(range, "range");
        type = Objects.requireNonNull(type, "type");
        actor = Objects.requireNonNull(actor, "actor");
        chosenCandidate = Objects.requireNonNull(chosenCandidate, "chosenCandidate");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        reasonCode.ifPresent(value -> IdentifierSupport.validate(value, "reason code"));
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}
