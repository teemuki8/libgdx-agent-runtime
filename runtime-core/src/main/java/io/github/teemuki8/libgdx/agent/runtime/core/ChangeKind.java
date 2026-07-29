package io.github.teemuki8.libgdx.agent.runtime.core;

/** Exact structural difference observed between completed snapshots. */
public enum ChangeKind {
    /** Entity did not exist in the previous snapshot. */
    ENTITY_ADDED,
    /** Entity no longer exists. */
    ENTITY_REMOVED,
    /** Property did not exist in the previous entity snapshot. */
    PROPERTY_ADDED,
    /** Property no longer exists. */
    PROPERTY_REMOVED,
    /** Property values differ structurally. */
    PROPERTY_CHANGED
}
