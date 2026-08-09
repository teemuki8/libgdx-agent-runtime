package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded immutable simulation tick query page with explicit completeness evidence. */
public record SimulationTickPage(SimulationTickQuery query, List<SimulationTick> ticks,
        boolean hasMore, SimulationTickRangeStatus rangeStatus,
        Optional<SimulationTickId> oldestRetainedTickId,
        Optional<SimulationTickId> newestRetainedTickId) {
    /** Copies and validates page evidence. */
    public SimulationTickPage {
        Objects.requireNonNull(query, "query");
        ticks = List.copyOf(ticks);
        if (ticks.size() > query.limit()) {
            throw new IllegalArgumentException("simulation tick page exceeds query limit");
        }
        Objects.requireNonNull(rangeStatus, "rangeStatus");
        oldestRetainedTickId = Objects.requireNonNull(
                oldestRetainedTickId, "oldestRetainedTickId");
        newestRetainedTickId = Objects.requireNonNull(
                newestRetainedTickId, "newestRetainedTickId");
        SimulationTick previous = null;
        for (SimulationTick tick : ticks) {
            if (!tick.executionEpochId().equals(query.executionEpochId())
                    || tick.epochTick() < query.fromEpochTick()
                    || tick.epochTick() > query.toEpochTick()) {
                throw new IllegalArgumentException("simulation tick does not match its page query");
            }
            if (previous != null
                    && (tick.simulationTickId().compareTo(previous.simulationTickId()) <= 0
                            || tick.epochTick() <= previous.epochTick())) {
                throw new IllegalArgumentException("simulation tick page ordering is invalid");
            }
            previous = tick;
        }
        if (rangeStatus == SimulationTickRangeStatus.COMPLETE && hasMore) {
            throw new IllegalArgumentException("complete simulation tick page cannot have more items");
        }
        if (oldestRetainedTickId.isPresent() != newestRetainedTickId.isPresent()
                || oldestRetainedTickId.isPresent()
                        && oldestRetainedTickId.orElseThrow().compareTo(
                                newestRetainedTickId.orElseThrow()) > 0) {
            throw new IllegalArgumentException("simulation tick retention bounds are invalid");
        }
    }

    /** Returns whether the entire requested range is retained in this page. */
    public boolean complete() {
        return rangeStatus == SimulationTickRangeStatus.COMPLETE;
    }
}
