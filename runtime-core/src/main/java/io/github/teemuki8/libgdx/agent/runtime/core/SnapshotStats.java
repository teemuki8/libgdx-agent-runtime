package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;

/** Capture counts, diagnostics, and explicit truncation evidence. */
public record SnapshotStats(
        long observedEntities,
        long retainedEntities,
        List<CaptureDiagnostic> diagnostics,
        List<Truncation> truncations) {
    /** Validates and copies stats. */
    public SnapshotStats {
        if (observedEntities < 0 || retainedEntities < 0 || retainedEntities > observedEntities) {
            throw new IllegalArgumentException("invalid snapshot counts");
        }
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        truncations = List.copyOf(Objects.requireNonNull(truncations, "truncations"));
    }
}
