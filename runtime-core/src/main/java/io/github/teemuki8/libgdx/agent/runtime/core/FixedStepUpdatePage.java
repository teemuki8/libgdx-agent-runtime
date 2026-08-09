package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** Bounded immutable page of fixed-step accumulator update evidence. */
public record FixedStepUpdatePage(FixedStepUpdateQuery query,
        List<FixedStepUpdateReport> reports, boolean hasMore, boolean partiallyEvicted,
        OptionalLong oldestRetainedSequence, OptionalLong newestRetainedSequence) {
    /** Copies and validates ordered page evidence. */
    public FixedStepUpdatePage {
        Objects.requireNonNull(query, "query");
        reports = List.copyOf(reports);
        if (reports.size() > query.limit()) {
            throw new IllegalArgumentException("fixed-step update page exceeds query limit");
        }
        long previous = 0;
        for (FixedStepUpdateReport report : reports) {
            if (report.updateSequence() < query.fromSequence()
                    || report.updateSequence() > query.toSequence()
                    || report.updateSequence() <= previous) {
                throw new IllegalArgumentException("fixed-step update page ordering is invalid");
            }
            previous = report.updateSequence();
        }
        oldestRetainedSequence = Objects.requireNonNull(
                oldestRetainedSequence, "oldestRetainedSequence");
        newestRetainedSequence = Objects.requireNonNull(
                newestRetainedSequence, "newestRetainedSequence");
        if (oldestRetainedSequence.isPresent() != newestRetainedSequence.isPresent()
                || oldestRetainedSequence.isPresent()
                        && oldestRetainedSequence.orElseThrow()
                                > newestRetainedSequence.orElseThrow()) {
            throw new IllegalArgumentException("fixed-step retention bounds are invalid");
        }
    }
}
