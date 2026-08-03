package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Completed immutable trace of an explicitly instrumented semantic choice. */
public record DecisionTrace(
        DecisionId id,
        FrameId frameId,
        DecisionType type,
        EntityId actor,
        List<DecisionCandidate> candidates,
        Optional<EntityId> chosenCandidate,
        Optional<Reason> choiceReason,
        FactMetadata metadata,
        Completion completion,
        List<Truncation> truncations) {
    /** Scope completion state. */
    public enum Completion {
        /** Scope closed normally. */
        COMPLETED,
        /** Scope did not close normally or was open at frame end. */
        ABORTED
    }

    /** Validates and copies trace contents. */
    public DecisionTrace {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(frameId, "frameId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(actor, "actor");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        chosenCandidate = Objects.requireNonNull(chosenCandidate, "chosenCandidate");
        choiceReason = Objects.requireNonNull(choiceReason, "choiceReason");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(completion, "completion");
        truncations = List.copyOf(Objects.requireNonNull(truncations, "truncations"));
        if (chosenCandidate.isPresent() != choiceReason.isPresent()) {
            throw new IllegalArgumentException("chosen candidate and reason must appear together");
        }
    }

    /** Compatibility constructor for a decision without fact metadata. */
    public DecisionTrace(DecisionId id, FrameId frameId, DecisionType type, EntityId actor,
            List<DecisionCandidate> candidates, Optional<EntityId> chosenCandidate,
            Optional<Reason> choiceReason, Completion completion, List<Truncation> truncations) {
        this(id, frameId, type, actor, candidates, chosenCandidate, choiceReason,
                FactMetadata.empty(), completion, truncations);
    }
}
