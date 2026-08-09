package io.github.teemuki8.libgdx.agent.runtime.box2d;

import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTickId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded retained Box2D contact-tick page. */
public record Box2dContactTickPage(List<Box2dContactTick> ticks, boolean hasMore,
        RangeStatus rangeStatus, Optional<SimulationTickId> oldestRetainedTickId,
        Optional<SimulationTickId> newestRetainedTickId) {
    /** Validates stable order, range metadata, and defensive copies. */
    public Box2dContactTickPage {
        ticks = List.copyOf(ticks);
        Objects.requireNonNull(rangeStatus, "rangeStatus");
        oldestRetainedTickId = Objects.requireNonNull(
                oldestRetainedTickId, "oldestRetainedTickId");
        newestRetainedTickId = Objects.requireNonNull(
                newestRetainedTickId, "newestRetainedTickId");
        for (int index = 1; index < ticks.size(); index++) {
            if (ticks.get(index - 1).simulationTickId()
                    .compareTo(ticks.get(index).simulationTickId()) >= 0) {
                throw new IllegalArgumentException("contact ticks are not strictly sorted");
            }
        }
        if (oldestRetainedTickId.isPresent() != newestRetainedTickId.isPresent()
                || rangeStatus == RangeStatus.COMPLETE && hasMore
                || !ticks.isEmpty() && oldestRetainedTickId.isEmpty()) {
            throw new IllegalArgumentException("contact tick page metadata is inconsistent");
        }
    }

    /** Completeness of the requested contact-tick range. */
    public enum RangeStatus {
        /** Every requested retained contact tick is present. */ COMPLETE,
        /** More matching ticks remain after the page. */ PAGINATED,
        /** Earlier matching contact ticks were evicted. */ PARTIALLY_EVICTED,
        /** The requested tick has not been captured. */ NOT_YET_CAPTURED
    }
}
