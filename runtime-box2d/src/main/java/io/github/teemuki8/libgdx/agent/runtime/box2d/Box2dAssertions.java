package io.github.teemuki8.libgdx.agent.runtime.box2d;

import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EventType;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertion;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertionSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationEvidenceRequirement;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Data-only factories for assertions over immutable registered Box2D evidence. */
public final class Box2dAssertions {
    private static final BigDecimal RADIANS_PERIOD = new BigDecimal("6.283185307179586");

    private Box2dAssertions() {}

    /** Stable registered endpoint used by contact assertion factories. */
    public record ContactEndpoint(String bodyId, String fixtureId, int childIndex)
            implements Comparable<ContactEndpoint> {
        /** Validates stable application-supplied identity. */
        public ContactEndpoint {
            validateId(bodyId, "bodyId");
            validateId(fixtureId, "fixtureId");
            if (childIndex < 0) {
                throw new IllegalArgumentException("childIndex must be non-negative");
            }
        }

        /** Orders endpoints exactly like captured contact keys. */
        @Override public int compareTo(ContactEndpoint other) {
            int fixtureOrder = fixtureId.compareTo(other.fixtureId);
            return fixtureOrder != 0 ? fixtureOrder : Integer.compare(childIndex, other.childIndex);
        }
    }

    /** Requires one registered body at the final tick. */
    public static SimulationAssertionSpec bodyExists(String bodyId) {
        return SimulationAssertionSpec.of(new SimulationAssertion.EntityExists(body(bodyId)));
    }

    /** Requires final body position within the selected vector tolerance. */
    public static SimulationAssertionSpec bodyPositionApproximately(String bodyId,
            Box2dVector expected, double absoluteTolerance,
            SimulationAssertion.VectorToleranceMode toleranceMode) {
        return vectorApproximately(bodyId, "position", expected,
                absoluteTolerance, toleranceMode);
    }

    /** Requires final body linear velocity within the selected vector tolerance. */
    public static SimulationAssertionSpec bodyVelocityApproximately(String bodyId,
            Box2dVector expected, double absoluteTolerance,
            SimulationAssertion.VectorToleranceMode toleranceMode) {
        return vectorApproximately(bodyId, "linearVelocity", expected,
                absoluteTolerance, toleranceMode);
    }

    /** Requires the registered body to be sleeping at the final tick. */
    public static SimulationAssertionSpec bodySleeping(String bodyId) {
        return SimulationAssertionSpec.of(new SimulationAssertion.PropertyEquals(
                body(bodyId), "awake", RuntimeValues.bool(false)));
    }

    /** Requires the registered body to be awake at the final tick. */
    public static SimulationAssertionSpec bodyAwake(String bodyId) {
        return SimulationAssertionSpec.of(new SimulationAssertion.PropertyEquals(
                body(bodyId), "awake", RuntimeValues.bool(true)));
    }

    /** Requires final linear magnitude and angular velocity to be within explicit tolerances. */
    public static SimulationAssertionSpec bodyStopped(
            String bodyId, double linearTolerance, double angularTolerance) {
        EntityId entityId = body(bodyId);
        return SimulationAssertionSpec.of(new SimulationAssertion.AllOf(List.of(
                new SimulationAssertion.VectorMagnitudeAtMost(entityId, "linearVelocity",
                        decimal(linearTolerance, "linearTolerance"),
                        SimulationAssertion.Extent.FINAL),
                new SimulationAssertion.ScalarApproximatelyEquals(entityId, "angularVelocity",
                        BigDecimal.ZERO, decimal(angularTolerance, "angularTolerance")))));
    }

    /** Requires final body position inside a closed physics-world area. */
    public static SimulationAssertionSpec bodyInsideArea(
            String bodyId, Box2dVector minimum, Box2dVector maximum) {
        return area(bodyId, minimum, maximum,
                SimulationAssertion.AreaRelation.INSIDE, SimulationAssertion.Extent.FINAL);
    }

    /** Requires final body position outside a closed physics-world area. */
    public static SimulationAssertionSpec bodyOutsideArea(
            String bodyId, Box2dVector minimum, Box2dVector maximum) {
        return area(bodyId, minimum, maximum,
                SimulationAssertion.AreaRelation.OUTSIDE, SimulationAssertion.Extent.FINAL);
    }

    /** Requires body position inside a closed area for every requested tick. */
    public static SimulationAssertionSpec bodyRemainedWithinBounds(
            String bodyId, Box2dVector minimum, Box2dVector maximum) {
        return area(bodyId, minimum, maximum,
                SimulationAssertion.AreaRelation.INSIDE, SimulationAssertion.Extent.EVERY_TICK);
    }

    /** Requires final distance between two body positions within an absolute tolerance. */
    public static SimulationAssertionSpec bodyDistanceApproximately(String leftBodyId,
            String rightBodyId, double expectedDistance, double absoluteTolerance) {
        return SimulationAssertionSpec.of(
                new SimulationAssertion.VectorDistanceApproximatelyEquals(
                        body(leftBodyId), "position", body(rightBodyId), "position",
                        decimal(expectedDistance, "expectedDistance"),
                        decimal(absoluteTolerance, "absoluteTolerance")));
    }

    /** Requires final body angle within a wrapped-radian absolute tolerance. */
    public static SimulationAssertionSpec bodyAngleApproximately(
            String bodyId, double expectedRadians, double absoluteTolerance) {
        return SimulationAssertionSpec.of(
                new SimulationAssertion.WrappedAngleApproximatelyEquals(
                        body(bodyId), "angleRadians", decimal(expectedRadians, "expectedRadians"),
                        RADIANS_PERIOD, decimal(absoluteTolerance, "absoluteTolerance")));
    }

    /** Requires at least one exact begin contact between the stable endpoints. */
    public static SimulationAssertionSpec contactOccurred(String worldId,
            ContactEndpoint first, ContactEndpoint second) {
        return contactEvent(worldId, first, second,
                SimulationAssertion.EventExpectation.AT_LEAST_ONE);
    }

    /** Requires no exact begin contact between the stable endpoints. */
    public static SimulationAssertionSpec contactDidNotOccur(String worldId,
            ContactEndpoint first, ContactEndpoint second) {
        return contactEvent(worldId, first, second,
                SimulationAssertion.EventExpectation.NONE);
    }

    /** Requires the exact stable contact in every active-contact tick snapshot. */
    public static SimulationAssertionSpec contactRemainedActive(String worldId,
            ContactEndpoint first, ContactEndpoint second) {
        validateId(worldId, "worldId");
        ContactPair pair = pair(first, second);
        RuntimeValue.ObjectValue selector = RuntimeValues.object(
                RuntimeValues.field("endpointA", endpoint(pair.first)),
                RuntimeValues.field("endpointB", endpoint(pair.second)),
                RuntimeValues.field("key", key(pair)));
        return contactSpec(worldId, new SimulationAssertion.ObjectListContains(
                contacts(worldId), "activeContacts", selector,
                SimulationAssertion.Extent.EVERY_TICK));
    }

    /** Requires linear speed not to exceed the inclusive threshold at any requested tick. */
    public static SimulationAssertionSpec bodyNeverExceededSpeed(
            String bodyId, double maximumSpeed) {
        return SimulationAssertionSpec.of(new SimulationAssertion.VectorMagnitudeAtMost(
                body(bodyId), "linearVelocity", decimal(maximumSpeed, "maximumSpeed"),
                SimulationAssertion.Extent.EVERY_TICK));
    }

    private static SimulationAssertionSpec vectorApproximately(String bodyId, String property,
            Box2dVector expected, double tolerance,
            SimulationAssertion.VectorToleranceMode toleranceMode) {
        Objects.requireNonNull(expected, "expected");
        return SimulationAssertionSpec.of(new SimulationAssertion.VectorApproximatelyEquals(
                body(bodyId), property, RuntimeValues.vector2(expected.x(), expected.y()),
                decimal(tolerance, "absoluteTolerance"), toleranceMode));
    }

    private static SimulationAssertionSpec area(String bodyId, Box2dVector minimum,
            Box2dVector maximum, SimulationAssertion.AreaRelation relation,
            SimulationAssertion.Extent extent) {
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
        SimulationAssertion.Area area = new SimulationAssertion.Area(
                BigDecimal.valueOf(minimum.x()), BigDecimal.valueOf(minimum.y()),
                BigDecimal.valueOf(maximum.x()), BigDecimal.valueOf(maximum.y()));
        return SimulationAssertionSpec.of(new SimulationAssertion.VectorInArea(
                body(bodyId), "position", area, relation, extent));
    }

    private static SimulationAssertionSpec contactEvent(String worldId, ContactEndpoint first,
            ContactEndpoint second, SimulationAssertion.EventExpectation expectation) {
        validateId(worldId, "worldId");
        ContactPair pair = pair(first, second);
        SimulationAssertion.EventSelector selector = new SimulationAssertion.EventSelector(
                EventType.of("box2d.contact.begin"), Optional.of(body(pair.first.bodyId)),
                Optional.of(body(pair.second.bodyId)), RuntimeValues.object(
                        RuntimeValues.field("key", key(pair)),
                        RuntimeValues.field("worldId", RuntimeValues.string(worldId))));
        return contactSpec(worldId, new SimulationAssertion.EventCount(selector, expectation, 0));
    }

    private static SimulationAssertionSpec contactSpec(
            String worldId, SimulationAssertion assertion) {
        return new SimulationAssertionSpec(assertion, List.of(
                new SimulationEvidenceRequirement(contacts(worldId), "complete")));
    }

    private static ContactPair pair(ContactEndpoint first, ContactEndpoint second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        int order = first.compareTo(second);
        if (order == 0) {
            throw new IllegalArgumentException("contact endpoints must identify distinct fixtures");
        }
        return order < 0 ? new ContactPair(first, second) : new ContactPair(second, first);
    }

    private static RuntimeValue.ObjectValue key(ContactPair pair) {
        return RuntimeValues.object(
                RuntimeValues.field("childIndexA", RuntimeValues.integer(pair.first.childIndex)),
                RuntimeValues.field("childIndexB", RuntimeValues.integer(pair.second.childIndex)),
                RuntimeValues.field("fixtureAId", RuntimeValues.string(pair.first.fixtureId)),
                RuntimeValues.field("fixtureBId", RuntimeValues.string(pair.second.fixtureId)));
    }

    private static RuntimeValue.ObjectValue endpoint(ContactEndpoint endpoint) {
        return RuntimeValues.object(
                RuntimeValues.field("bodyId", RuntimeValues.string(endpoint.bodyId)),
                RuntimeValues.field("childIndex", RuntimeValues.integer(endpoint.childIndex)),
                RuntimeValues.field("fixtureId", RuntimeValues.string(endpoint.fixtureId)));
    }

    private static EntityId body(String bodyId) {
        validateId(bodyId, "bodyId");
        return EntityId.of("box2d.body." + bodyId);
    }

    private static EntityId contacts(String worldId) {
        validateId(worldId, "worldId");
        return EntityId.of("box2d.contacts." + worldId);
    }

    private static BigDecimal decimal(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return BigDecimal.valueOf(value);
    }

    private static void validateId(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 220) {
            throw new IllegalArgumentException(name + " is outside range");
        }
    }

    private record ContactPair(ContactEndpoint first, ContactEndpoint second) {}
}
