package io.github.teemuki8.libgdx.agent.runtime.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Closed, data-only assertion union evaluated over exact simulation-tick evidence. */
public sealed interface SimulationAssertion permits SimulationAssertion.EntityExists,
        SimulationAssertion.PropertyEquals, SimulationAssertion.ScalarApproximatelyEquals,
        SimulationAssertion.VectorApproximatelyEquals, SimulationAssertion.VectorInArea,
        SimulationAssertion.VectorMagnitudeAtMost,
        SimulationAssertion.VectorDistanceApproximatelyEquals,
        SimulationAssertion.WrappedAngleApproximatelyEquals, SimulationAssertion.EventCount,
        SimulationAssertion.ObjectListContains, SimulationAssertion.AllOf {
    /** Maximum direct terms in one conjunction. */
    int MAX_TERMS = 8;

    /** Assertion evaluation extent. */
    enum Extent { FINAL, EVERY_TICK }

    /** Vector approximation metric. */
    enum VectorToleranceMode { COMPONENT, EUCLIDEAN }

    /** Relation to a closed axis-aligned area. */
    enum AreaRelation { INSIDE, OUTSIDE }

    /** Event count expectation. */
    enum EventExpectation { AT_LEAST_ONE, NONE, EXACT }

    /** Closed inclusive axis-aligned decimal area. */
    record Area(BigDecimal minimumX, BigDecimal minimumY,
            BigDecimal maximumX, BigDecimal maximumY) {
        /** Canonicalizes and validates the area. */
        public Area {
            minimumX = decimal(minimumX, "minimumX");
            minimumY = decimal(minimumY, "minimumY");
            maximumX = decimal(maximumX, "maximumX");
            maximumY = decimal(maximumY, "maximumY");
            if (minimumX.compareTo(maximumX) > 0 || minimumY.compareTo(maximumY) > 0) {
                throw new IllegalArgumentException("area minimum must not exceed maximum");
            }
        }
    }

    /** Exact bounded event selector without property paths. */
    record EventSelector(EventType eventType, Optional<EntityId> subject,
            Optional<EntityId> source, RuntimeValue.ObjectValue attributes) {
        /** Validates immutable selector fields. */
        public EventSelector {
            Objects.requireNonNull(eventType, "eventType");
            subject = Objects.requireNonNull(subject, "subject");
            source = Objects.requireNonNull(source, "source");
            Objects.requireNonNull(attributes, "attributes");
            validateSelector(attributes);
        }
    }

    /** Requires an entity at the final simulation tick. */
    record EntityExists(EntityId entityId) implements SimulationAssertion {
        public EntityExists { Objects.requireNonNull(entityId, "entityId"); }
    }

    /** Requires exact canonical property equality at the final simulation tick. */
    record PropertyEquals(EntityId entityId, String property, RuntimeValue expected)
            implements SimulationAssertion {
        public PropertyEquals {
            Objects.requireNonNull(entityId, "entityId");
            validateProperty(property);
            Objects.requireNonNull(expected, "expected");
        }
    }

    /** Requires approximate scalar equality at the final simulation tick. */
    record ScalarApproximatelyEquals(EntityId entityId, String property,
            BigDecimal expected, BigDecimal absoluteTolerance) implements SimulationAssertion {
        public ScalarApproximatelyEquals {
            Objects.requireNonNull(entityId, "entityId");
            validateProperty(property);
            expected = decimal(expected, "expected");
            absoluteTolerance = nonNegative(absoluteTolerance, "absoluteTolerance");
        }
    }

    /** Requires approximate vector equality at the final simulation tick. */
    record VectorApproximatelyEquals(EntityId entityId, String property,
            RuntimeValue.Vector2Value expected, BigDecimal absoluteTolerance,
            VectorToleranceMode toleranceMode) implements SimulationAssertion {
        public VectorApproximatelyEquals {
            Objects.requireNonNull(entityId, "entityId");
            validateProperty(property);
            Objects.requireNonNull(expected, "expected");
            absoluteTolerance = nonNegative(absoluteTolerance, "absoluteTolerance");
            Objects.requireNonNull(toleranceMode, "toleranceMode");
        }
    }

    /** Requires a vector to be inside or outside one closed area. */
    record VectorInArea(EntityId entityId, String property, Area area,
            AreaRelation relation, Extent extent) implements SimulationAssertion {
        public VectorInArea {
            Objects.requireNonNull(entityId, "entityId");
            validateProperty(property);
            Objects.requireNonNull(area, "area");
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(extent, "extent");
        }
    }

    /** Requires a vector magnitude not to exceed an inclusive maximum. */
    record VectorMagnitudeAtMost(EntityId entityId, String property,
            BigDecimal maximum, Extent extent) implements SimulationAssertion {
        public VectorMagnitudeAtMost {
            Objects.requireNonNull(entityId, "entityId");
            validateProperty(property);
            maximum = nonNegative(maximum, "maximum");
            Objects.requireNonNull(extent, "extent");
        }
    }

    /** Requires final vector distance to approximate one expected distance. */
    record VectorDistanceApproximatelyEquals(EntityId leftEntityId, String leftProperty,
            EntityId rightEntityId, String rightProperty, BigDecimal expectedDistance,
            BigDecimal absoluteTolerance) implements SimulationAssertion {
        public VectorDistanceApproximatelyEquals {
            Objects.requireNonNull(leftEntityId, "leftEntityId");
            validateProperty(leftProperty);
            Objects.requireNonNull(rightEntityId, "rightEntityId");
            validateProperty(rightProperty);
            expectedDistance = nonNegative(expectedDistance, "expectedDistance");
            absoluteTolerance = nonNegative(absoluteTolerance, "absoluteTolerance");
        }
    }

    /** Requires final wrapped-angle equality for an explicit positive period. */
    record WrappedAngleApproximatelyEquals(EntityId entityId, String property,
            BigDecimal expected, BigDecimal period, BigDecimal absoluteTolerance)
            implements SimulationAssertion {
        public WrappedAngleApproximatelyEquals {
            Objects.requireNonNull(entityId, "entityId");
            validateProperty(property);
            expected = decimal(expected, "expected");
            period = decimal(period, "period");
            if (period.signum() <= 0) {
                throw new IllegalArgumentException("period must be positive");
            }
            absoluteTolerance = nonNegative(absoluteTolerance, "absoluteTolerance");
            if (absoluteTolerance.multiply(BigDecimal.TWO).compareTo(period) > 0) {
                throw new IllegalArgumentException("angle tolerance must not exceed half-period");
            }
        }
    }

    /** Requires a selected exact event count relation across the simulation range. */
    record EventCount(EventSelector selector, EventExpectation expectation, int exactCount)
            implements SimulationAssertion {
        public EventCount {
            Objects.requireNonNull(selector, "selector");
            Objects.requireNonNull(expectation, "expectation");
            if (expectation == EventExpectation.EXACT
                    ? exactCount <= 0 || exactCount > 1_000_000 : exactCount != 0) {
                throw new IllegalArgumentException("event exact count is inconsistent");
            }
        }
    }

    /** Requires a list property to contain a recursively selected object. */
    record ObjectListContains(EntityId entityId, String property,
            RuntimeValue.ObjectValue selector, Extent extent) implements SimulationAssertion {
        public ObjectListContains {
            Objects.requireNonNull(entityId, "entityId");
            validateProperty(property);
            Objects.requireNonNull(selector, "selector");
            validateSelector(selector);
            Objects.requireNonNull(extent, "extent");
        }
    }

    /** Requires every bounded non-composite term to hold. */
    record AllOf(List<SimulationAssertion> terms) implements SimulationAssertion {
        public AllOf {
            Objects.requireNonNull(terms, "terms");
            if (terms.size() < 2 || terms.size() > MAX_TERMS) {
                throw new IllegalArgumentException("allOf term count is outside the supported range");
            }
            ArrayList<SimulationAssertion> copy = new ArrayList<>(terms.size());
            for (SimulationAssertion term : terms) {
                Objects.requireNonNull(term, "term");
                if (term instanceof AllOf) {
                    throw new IllegalArgumentException("nested allOf assertions are not supported");
                }
                copy.add(term);
            }
            terms = List.copyOf(copy);
        }
    }

    private static void validateProperty(String value) {
        IdentifierSupport.validate(value, "property");
    }

    private static BigDecimal decimal(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        return new RuntimeValue.DecimalValue(value).value();
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        BigDecimal canonical = decimal(value, name);
        if (canonical.signum() < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return canonical;
    }

    private static void validateSelector(RuntimeValue.ObjectValue selector) {
        int[] nodes = {0};
        validateSelectorValue(selector, 1, nodes);
    }

    private static void validateSelectorValue(RuntimeValue value, int depth, int[] nodes) {
        if (depth > 4 || ++nodes[0] > 32) {
            throw new IllegalArgumentException("assertion selector exceeds its hard bound");
        }
        switch (value) {
            case RuntimeValue.ObjectValue object -> {
                if (object.fields().size() > 16) {
                    throw new IllegalArgumentException("assertion selector has too many fields");
                }
                object.fields().forEach(field -> validateSelectorValue(
                        field.value(), depth + 1, nodes));
            }
            case RuntimeValue.ListValue _ ->
                    throw new IllegalArgumentException("assertion selector lists are not supported");
            case RuntimeValue.StringValue string -> validateSelectorString(string.value());
            case RuntimeValue.EnumValue enumeration -> validateSelectorString(enumeration.value());
            default -> {
                // Remaining canonical scalar and vector values are closed and bounded.
            }
        }
    }

    private static void validateSelectorString(String value) {
        if (value.length() > 1_024) {
            throw new IllegalArgumentException("assertion selector string exceeds its hard bound");
        }
    }
}
