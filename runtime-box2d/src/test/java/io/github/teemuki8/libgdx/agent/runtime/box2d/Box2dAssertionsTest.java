package io.github.teemuki8.libgdx.agent.runtime.box2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertion;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertionSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationEvidenceRequirement;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class Box2dAssertionsTest {
    @Test
    void bodyFactoriesCompileToDocumentedGenericPropertiesAndExtents() {
        assertEquals(new SimulationAssertion.EntityExists(EntityId.of("box2d.body.ball")),
                Box2dAssertions.bodyExists("ball").assertion());
        assertEquals(new SimulationAssertion.VectorApproximatelyEquals(
                EntityId.of("box2d.body.ball"), "position", RuntimeValues.vector2(5, 0.5),
                new BigDecimal("0.01"), SimulationAssertion.VectorToleranceMode.EUCLIDEAN),
                Box2dAssertions.bodyPositionApproximately("ball", new Box2dVector(5, 0.5),
                        0.01, SimulationAssertion.VectorToleranceMode.EUCLIDEAN).assertion());
        assertEquals("linearVelocity",
                ((SimulationAssertion.VectorApproximatelyEquals)
                        Box2dAssertions.bodyVelocityApproximately("ball", new Box2dVector(0, 0),
                                0.01, SimulationAssertion.VectorToleranceMode.COMPONENT)
                                .assertion()).property());
        assertEquals(new SimulationAssertion.PropertyEquals(EntityId.of("box2d.body.ball"),
                "awake", RuntimeValues.bool(false)),
                Box2dAssertions.bodySleeping("ball").assertion());
        assertEquals(new SimulationAssertion.PropertyEquals(EntityId.of("box2d.body.ball"),
                "awake", RuntimeValues.bool(true)), Box2dAssertions.bodyAwake("ball").assertion());

        SimulationAssertion.AllOf stopped = (SimulationAssertion.AllOf)
                Box2dAssertions.bodyStopped("ball", 0.02, 0.03).assertion();
        assertEquals(2, stopped.terms().size());
        assertEquals("linearVelocity",
                ((SimulationAssertion.VectorMagnitudeAtMost) stopped.terms().getFirst()).property());
        assertEquals("angularVelocity",
                ((SimulationAssertion.ScalarApproximatelyEquals) stopped.terms().getLast()).property());

        SimulationAssertion.VectorInArea remained = (SimulationAssertion.VectorInArea)
                Box2dAssertions.bodyRemainedWithinBounds("ball", new Box2dVector(-1, -2),
                        new Box2dVector(3, 4)).assertion();
        assertEquals(SimulationAssertion.Extent.EVERY_TICK, remained.extent());
        assertEquals(SimulationAssertion.AreaRelation.INSIDE, remained.relation());
        assertEquals(SimulationAssertion.Extent.EVERY_TICK,
                ((SimulationAssertion.VectorMagnitudeAtMost)
                        Box2dAssertions.bodyNeverExceededSpeed("ball", 10).assertion()).extent());
    }

    @Test
    void finalAreaDistanceAndAngleFactoriesUsePhysicsProperties() {
        assertEquals(SimulationAssertion.AreaRelation.INSIDE,
                ((SimulationAssertion.VectorInArea) Box2dAssertions.bodyInsideArea(
                        "ball", new Box2dVector(0, 0), new Box2dVector(10, 10))
                        .assertion()).relation());
        assertEquals(SimulationAssertion.AreaRelation.OUTSIDE,
                ((SimulationAssertion.VectorInArea) Box2dAssertions.bodyOutsideArea(
                        "ball", new Box2dVector(0, 0), new Box2dVector(10, 10))
                        .assertion()).relation());
        SimulationAssertion.VectorDistanceApproximatelyEquals distance =
                (SimulationAssertion.VectorDistanceApproximatelyEquals)
                        Box2dAssertions.bodyDistanceApproximately(
                                "ball", "ground", 5, 0.1).assertion();
        assertEquals(EntityId.of("box2d.body.ball"), distance.leftEntityId());
        assertEquals(EntityId.of("box2d.body.ground"), distance.rightEntityId());
        assertEquals("position", distance.leftProperty());
        SimulationAssertion.WrappedAngleApproximatelyEquals angle =
                (SimulationAssertion.WrappedAngleApproximatelyEquals)
                        Box2dAssertions.bodyAngleApproximately("ball", Math.PI, 0.01).assertion();
        assertEquals("angleRadians", angle.property());
        assertEquals(new BigDecimal("6.283185307179586"), angle.period());
    }

    @Test
    void contactFactoriesCanonicalizeStableEndpointsAndRequireCompleteContactEvidence() {
        Box2dAssertions.ContactEndpoint high =
                new Box2dAssertions.ContactEndpoint("ground-body", "z-ground", 2);
        Box2dAssertions.ContactEndpoint low =
                new Box2dAssertions.ContactEndpoint("ball-body", "a-ball", 1);

        SimulationAssertionSpec occurred = Box2dAssertions.contactOccurred("main", high, low);
        SimulationAssertion.EventCount event = (SimulationAssertion.EventCount) occurred.assertion();
        assertEquals(EntityId.of("box2d.body.ball-body"),
                event.selector().subject().orElseThrow());
        assertEquals(EntityId.of("box2d.body.ground-body"),
                event.selector().source().orElseThrow());
        assertEquals(RuntimeValues.object(
                RuntimeValues.field("key", RuntimeValues.object(
                        RuntimeValues.field("childIndexA", RuntimeValues.integer(1)),
                        RuntimeValues.field("childIndexB", RuntimeValues.integer(2)),
                        RuntimeValues.field("fixtureAId", RuntimeValues.string("a-ball")),
                        RuntimeValues.field("fixtureBId", RuntimeValues.string("z-ground")))),
                RuntimeValues.field("worldId", RuntimeValues.string("main"))),
                event.selector().attributes());
        assertEquals(List.of(new SimulationEvidenceRequirement(
                EntityId.of("box2d.contacts.main"), "complete")),
                occurred.evidenceRequirements());
        assertEquals(SimulationAssertion.EventExpectation.NONE,
                ((SimulationAssertion.EventCount) Box2dAssertions.contactDidNotOccur(
                        "main", high, low).assertion()).expectation());

        SimulationAssertion.ObjectListContains active =
                (SimulationAssertion.ObjectListContains) Box2dAssertions.contactRemainedActive(
                        "main", high, low).assertion();
        assertEquals(EntityId.of("box2d.contacts.main"), active.entityId());
        assertEquals("activeContacts", active.property());
        assertEquals(SimulationAssertion.Extent.EVERY_TICK, active.extent());
        assertEquals(RuntimeValues.object(
                RuntimeValues.field("endpointA", RuntimeValues.object(
                        RuntimeValues.field("bodyId", RuntimeValues.string("ball-body")),
                        RuntimeValues.field("childIndex", RuntimeValues.integer(1)),
                        RuntimeValues.field("fixtureId", RuntimeValues.string("a-ball")))),
                RuntimeValues.field("endpointB", RuntimeValues.object(
                        RuntimeValues.field("bodyId", RuntimeValues.string("ground-body")),
                        RuntimeValues.field("childIndex", RuntimeValues.integer(2)),
                        RuntimeValues.field("fixtureId", RuntimeValues.string("z-ground")))),
                RuntimeValues.field("key", event.selector().attributes().fields().stream()
                        .filter(field -> field.name().equals("key")).findFirst().orElseThrow().value())),
                active.selector());
    }

    @Test
    void factoriesRejectInvalidIdsChildrenAndNumbersWithoutNativeObjects() {
        Box2dAssertions.ContactEndpoint endpoint =
                new Box2dAssertions.ContactEndpoint("ball", "ball-fixture", 0);
        assertThrows(IllegalArgumentException.class, () -> Box2dAssertions.bodyExists(" "));
        assertThrows(IllegalArgumentException.class, () ->
                new Box2dAssertions.ContactEndpoint("ball", "fixture", -1));
        assertThrows(IllegalArgumentException.class, () ->
                Box2dAssertions.contactOccurred("main", endpoint, endpoint));
        assertThrows(IllegalArgumentException.class, () ->
                Box2dAssertions.bodyNeverExceededSpeed("ball", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Box2dAssertions.bodyInsideArea(
                "ball", new Box2dVector(2, 0), new Box2dVector(1, 1)));
    }
}
