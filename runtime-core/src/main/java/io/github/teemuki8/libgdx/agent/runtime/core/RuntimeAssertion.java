package io.github.teemuki8.libgdx.agent.runtime.core;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/** Closed, data-only declarative assertion union with no expression or code execution surface. */
public sealed interface RuntimeAssertion permits RuntimeAssertion.EntityExists,
        RuntimeAssertion.EntityDoesNotExist, RuntimeAssertion.PropertyEquals,
        RuntimeAssertion.PropertyChangesFrom, RuntimeAssertion.PropertyRemainsWithinRange,
        RuntimeAssertion.EventOccurs, RuntimeAssertion.EventDoesNotOccur,
        RuntimeAssertion.EventOccursExactly, RuntimeAssertion.DecisionSelected,
        RuntimeAssertion.DecisionRejected, RuntimeAssertion.EntityCountStaysBelow,
        RuntimeAssertion.SnapshotsEquivalent {

    /** Requires an entity at the final frame. */
    record EntityExists(EntityId entityId) implements RuntimeAssertion {
        public EntityExists {
            Objects.requireNonNull(entityId, "entityId");
        }
    }

    /** Requires an entity to be absent at the final frame. */
    record EntityDoesNotExist(EntityId entityId) implements RuntimeAssertion {
        public EntityDoesNotExist {
            Objects.requireNonNull(entityId, "entityId");
        }
    }

    /** Requires one property to equal the expected canonical value at the final frame. */
    record PropertyEquals(EntityId entityId, String property, RuntimeValue expected)
            implements RuntimeAssertion {
        public PropertyEquals {
            Objects.requireNonNull(entityId, "entityId");
            IdentifierSupport.validate(property, "property");
            Objects.requireNonNull(expected, "expected");
        }
    }

    /** Requires a retained structural change whose prior value equals the expected value. */
    record PropertyChangesFrom(EntityId entityId, String property, RuntimeValue from)
            implements RuntimeAssertion {
        public PropertyChangesFrom {
            Objects.requireNonNull(entityId, "entityId");
            IdentifierSupport.validate(property, "property");
            Objects.requireNonNull(from, "from");
        }
    }

    /** Requires every retained value of one numeric property to remain in an inclusive range. */
    record PropertyRemainsWithinRange(EntityId entityId, String property,
            BigDecimal minimum, BigDecimal maximum) implements RuntimeAssertion {
        public PropertyRemainsWithinRange {
            Objects.requireNonNull(entityId, "entityId");
            IdentifierSupport.validate(property, "property");
            minimum = canonical(minimum, "minimum");
            maximum = canonical(maximum, "maximum");
            if (minimum.compareTo(maximum) > 0) {
                throw new IllegalArgumentException("minimum must not exceed maximum");
            }
        }
    }

    /** Requires at least one event of the exact type. */
    record EventOccurs(EventType eventType) implements RuntimeAssertion {
        public EventOccurs {
            Objects.requireNonNull(eventType, "eventType");
        }
    }

    /** Requires no event of the exact type across the complete scope. */
    record EventDoesNotOccur(EventType eventType) implements RuntimeAssertion {
        public EventDoesNotOccur {
            Objects.requireNonNull(eventType, "eventType");
        }
    }

    /** Requires exactly the specified positive event count across the complete scope. */
    record EventOccursExactly(EventType eventType, int count) implements RuntimeAssertion {
        public EventOccursExactly {
            Objects.requireNonNull(eventType, "eventType");
            if (count <= 0 || count > 1_000_000) {
                throw new IllegalArgumentException("event count is outside the supported range");
            }
        }
    }

    /** Requires a decision to select one candidate. */
    record DecisionSelected(DecisionType decisionType, EntityId candidate)
            implements RuntimeAssertion {
        public DecisionSelected {
            Objects.requireNonNull(decisionType, "decisionType");
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    /** Requires a decision to explicitly reject one candidate. */
    record DecisionRejected(DecisionType decisionType, EntityId candidate)
            implements RuntimeAssertion {
        public DecisionRejected {
            Objects.requireNonNull(decisionType, "decisionType");
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    /** Requires every frame's matching entity count to stay strictly below a limit. */
    record EntityCountStaysBelow(Optional<EntityType> entityType, int limit)
            implements RuntimeAssertion {
        public EntityCountStaysBelow {
            entityType = Objects.requireNonNull(entityType, "entityType");
            if (limit <= 0 || limit > 1_000_000) {
                throw new IllegalArgumentException("entity count limit is outside the supported range");
            }
        }
    }

    /** Requires two retained snapshots to be equivalent for an explicit observable scope. */
    record SnapshotsEquivalent(FrameId leftFrameId, FrameId rightFrameId,
            SnapshotComparisonScope comparisonScope) implements RuntimeAssertion {
        public SnapshotsEquivalent {
            Objects.requireNonNull(leftFrameId, "leftFrameId");
            Objects.requireNonNull(rightFrameId, "rightFrameId");
            Objects.requireNonNull(comparisonScope, "comparisonScope");
        }
    }

    private static BigDecimal canonical(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        return new RuntimeValue.DecimalValue(value).value();
    }
}
