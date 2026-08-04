package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;

/** Explicit observable selection and fixed normalization contract. */
public record DeterminismProfile(SnapshotComparisonScope comparisonScope,
        boolean includeUiCorrelations) {
    private static final List<DeterminismNormalization> NORMALIZATION_RULES = List.of(
            DeterminismNormalization.EPOCH_RELATIVE_TICK,
            DeterminismNormalization.EXCLUDE_RUNTIME_IDENTIFIERS,
            DeterminismNormalization.EXCLUDE_WALL_CLOCK);
    private static final List<String> EXCLUDED_VOLATILE_FIELDS = List.of(
            "executionEpochId", "frameId", "eventId", "decisionId", "capturedAt");

    /** Validates the explicit snapshot scope. */
    public DeterminismProfile {
        Objects.requireNonNull(comparisonScope, "comparisonScope");
    }

    /** Returns the stable normalization rules applied to every comparison. */
    public List<DeterminismNormalization> normalizationRules() {
        return NORMALIZATION_RULES;
    }

    /** Returns runtime-generated volatile fields excluded from comparison. */
    public List<String> excludedVolatileFields() {
        return EXCLUDED_VOLATILE_FIELDS;
    }
}
