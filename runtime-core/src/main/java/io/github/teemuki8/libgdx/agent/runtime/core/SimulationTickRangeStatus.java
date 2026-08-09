package io.github.teemuki8.libgdx.agent.runtime.core;

/** Completeness status for a requested epoch-relative simulation tick range. */
public enum SimulationTickRangeStatus {
    /** Every requested attempted tick is present in the page. */
    COMPLETE,
    /** More retained ticks match than the requested page limit permits. */
    PAGINATED,
    /** Earlier matching tick evidence was evicted from bounded retention. */
    PARTIALLY_EVICTED,
    /** The requested range extends beyond attempted ticks in the epoch. */
    NOT_YET_EXECUTED
}
