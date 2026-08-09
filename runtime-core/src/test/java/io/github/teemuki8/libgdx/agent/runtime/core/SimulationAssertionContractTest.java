package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SimulationAssertionContractTest {
    private static final EntityId BALL = EntityId.of("box2d.body.ball");

    @Test
    void closedValuesCanonicalizeDecimalsAndDefensivelyCopyCollections() {
        SimulationAssertion first = new SimulationAssertion.EntityExists(BALL);
        SimulationAssertion second = new SimulationAssertion.PropertyEquals(
                BALL, "awake", RuntimeValues.bool(true));
        ArrayList<SimulationAssertion> terms = new ArrayList<>(List.of(first, second));
        SimulationAssertion.AllOf all = new SimulationAssertion.AllOf(terms);
        terms.clear();

        assertEquals(List.of(first, second), all.terms());
        assertEquals(BigDecimal.ONE, new SimulationAssertion.ScalarApproximatelyEquals(
                BALL, "mass", new BigDecimal("1.000"), new BigDecimal("0.0100"))
                .expected());

        ArrayList<SimulationEvidenceRequirement> requirements = new ArrayList<>(List.of(
                new SimulationEvidenceRequirement(EntityId.of("box2d.contacts.main"),
                        "complete")));
        SimulationAssertionSpec spec = new SimulationAssertionSpec(all, requirements);
        requirements.clear();
        assertEquals(1, spec.evidenceRequirements().size());

        ArrayList<SimulationAssertionEvidence> evidence = new ArrayList<>(List.of(
                evidence(1, RuntimeValues.bool(false))));
        SimulationAssertionResult result = new SimulationAssertionResult(
                AssertionStatus.FAIL, "allOf", scope(), Optional.empty(), Optional.empty(),
                evidence, false, "assertion failed");
        evidence.clear();
        assertEquals(1, result.evidence().size());
    }

    @Test
    void everyClosedAssertionVariantHasValidatedDataOnlyInputs() {
        RuntimeValue.Vector2Value vector = RuntimeValues.vector2(1, 2);
        SimulationAssertion.Area area = new SimulationAssertion.Area(
                new BigDecimal("-1"), new BigDecimal("-2"),
                new BigDecimal("3"), new BigDecimal("4"));
        SimulationAssertion.EventSelector selector = new SimulationAssertion.EventSelector(
                EventType.of("box2d.contact.begin"), Optional.of(BALL),
                Optional.of(EntityId.of("box2d.body.ground")),
                RuntimeValues.object(RuntimeValues.field("sensor", RuntimeValues.bool(false))));

        List<SimulationAssertion> variants = List.of(
                new SimulationAssertion.EntityExists(BALL),
                new SimulationAssertion.PropertyEquals(BALL, "awake", RuntimeValues.bool(true)),
                new SimulationAssertion.ScalarApproximatelyEquals(
                        BALL, "angleRadians", BigDecimal.ZERO, new BigDecimal("0.01")),
                new SimulationAssertion.VectorApproximatelyEquals(BALL, "position", vector,
                        new BigDecimal("0.01"),
                        SimulationAssertion.VectorToleranceMode.COMPONENT),
                new SimulationAssertion.VectorInArea(BALL, "position", area,
                        SimulationAssertion.AreaRelation.INSIDE,
                        SimulationAssertion.Extent.FINAL),
                new SimulationAssertion.VectorMagnitudeAtMost(BALL, "linearVelocity",
                        new BigDecimal("3"), SimulationAssertion.Extent.EVERY_TICK),
                new SimulationAssertion.VectorDistanceApproximatelyEquals(
                        BALL, "position", EntityId.of("box2d.body.ground"), "position",
                        new BigDecimal("2"), new BigDecimal("0.1")),
                new SimulationAssertion.WrappedAngleApproximatelyEquals(
                        BALL, "angleRadians", BigDecimal.ZERO,
                        new BigDecimal("6.283185307179586"), new BigDecimal("0.01")),
                new SimulationAssertion.EventCount(selector,
                        SimulationAssertion.EventExpectation.AT_LEAST_ONE, 0),
                new SimulationAssertion.ObjectListContains(
                        EntityId.of("box2d.contacts.main"), "activeContacts",
                        RuntimeValues.object(RuntimeValues.field("sensor", RuntimeValues.bool(false))),
                        SimulationAssertion.Extent.EVERY_TICK));

        assertEquals(10, variants.size());
        assertEquals(new BigDecimal("0.01"),
                ((SimulationAssertion.VectorApproximatelyEquals) variants.get(3))
                        .absoluteTolerance());
        assertEquals(BigDecimal.valueOf(-1), area.minimumX());
        assertEquals("box2d.contact.begin", selector.eventType().value());
    }

    @Test
    void rejectsInvalidNumericGeometryCompositeAndCountContracts() {
        assertThrows(IllegalArgumentException.class, () ->
                new SimulationAssertion.ScalarApproximatelyEquals(
                        BALL, "mass", BigDecimal.ZERO, new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class, () -> new SimulationAssertion.Area(
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () ->
                new SimulationAssertion.WrappedAngleApproximatelyEquals(
                        BALL, "angleRadians", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                new SimulationAssertion.WrappedAngleApproximatelyEquals(
                        BALL, "angleRadians", BigDecimal.ZERO, BigDecimal.TEN,
                        new BigDecimal("5.01")));
        assertThrows(IllegalArgumentException.class, () -> new SimulationAssertion.EventCount(
                eventSelector(), SimulationAssertion.EventExpectation.EXACT, 0));
        assertThrows(IllegalArgumentException.class, () -> new SimulationAssertion.EventCount(
                eventSelector(), SimulationAssertion.EventExpectation.NONE, 1));

        SimulationAssertion leaf = new SimulationAssertion.EntityExists(BALL);
        assertThrows(IllegalArgumentException.class, () ->
                new SimulationAssertion.AllOf(List.of(leaf)));
        assertThrows(IllegalArgumentException.class, () -> new SimulationAssertion.AllOf(
                Collections.nCopies(9, leaf)));
        SimulationAssertion nested = new SimulationAssertion.AllOf(List.of(leaf,
                new SimulationAssertion.PropertyEquals(BALL, "awake", RuntimeValues.bool(true))));
        assertThrows(IllegalArgumentException.class, () ->
                new SimulationAssertion.AllOf(List.of(leaf, nested)));
    }

    @Test
    void rejectsOpenEndedOrOversizedSelectorsAndRequirements() {
        assertThrows(IllegalArgumentException.class, () ->
                new SimulationAssertion.EventSelector(eventSelector().eventType(),
                        Optional.empty(), Optional.empty(), RuntimeValues.object(
                                RuntimeValues.field("values",
                                        new RuntimeValue.ListValue(List.of(RuntimeValues.integer(1)))))));

        List<RuntimeValue.Field> tooManyFields = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            tooManyFields.add(RuntimeValues.field("field" + index, RuntimeValues.integer(index)));
        }
        assertThrows(IllegalArgumentException.class, () ->
                new SimulationAssertion.EventSelector(eventSelector().eventType(),
                        Optional.empty(), Optional.empty(),
                        new RuntimeValue.ObjectValue(tooManyFields)));

        RuntimeValue.ObjectValue tooDeep = RuntimeValues.object(RuntimeValues.field("a",
                RuntimeValues.object(RuntimeValues.field("b",
                        RuntimeValues.object(RuntimeValues.field("c",
                                RuntimeValues.object(RuntimeValues.field("d",
                                        RuntimeValues.object(RuntimeValues.field(
                                                "e", RuntimeValues.bool(true)))))))))));
        assertThrows(IllegalArgumentException.class, () ->
                new SimulationAssertion.ObjectListContains(BALL, "values", tooDeep,
                        SimulationAssertion.Extent.FINAL));

        SimulationAssertion assertion = new SimulationAssertion.EntityExists(BALL);
        assertThrows(IllegalArgumentException.class, () -> new SimulationAssertionSpec(assertion,
                Collections.nCopies(9,
                        new SimulationEvidenceRequirement(BALL, "complete"))));
    }

    @Test
    void validatesScopeEvidenceAndResultBounds() {
        assertThrows(IllegalArgumentException.class, () -> new SimulationAssertionScope(
                new ExecutionEpochId(0), 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SimulationAssertionScope(
                new ExecutionEpochId(0), 1, 1_001, 1));
        assertThrows(IllegalArgumentException.class, () -> new SimulationAssertionScope(
                new ExecutionEpochId(0), 1, 1, 101));
        assertThrows(IllegalArgumentException.class, () -> new SimulationAssertionEvidence(
                Optional.of(new SimulationTickId(1)), new ExecutionEpochId(0), 0,
                Optional.of(new FrameId(1)),
                "property", Optional.of(BALL), Optional.of("position"), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new SimulationAssertionResult(
                AssertionStatus.FAIL, "entityExists", scope(), Optional.empty(), Optional.empty(),
                List.of(evidence(1, RuntimeValues.bool(false)),
                        evidence(1, RuntimeValues.bool(true))), false, "assertion failed"));
    }

    private static SimulationAssertionScope scope() {
        return new SimulationAssertionScope(new ExecutionEpochId(0), 1, 1, 1);
    }

    private static SimulationAssertionEvidence evidence(long epochTick, RuntimeValue observed) {
        return new SimulationAssertionEvidence(Optional.of(new SimulationTickId(epochTick)),
                new ExecutionEpochId(0), epochTick, Optional.of(new FrameId(epochTick)), "property",
                Optional.of(BALL), Optional.of("awake"), Optional.of(observed));
    }

    private static SimulationAssertion.EventSelector eventSelector() {
        return new SimulationAssertion.EventSelector(EventType.of("box2d.contact.begin"),
                Optional.empty(), Optional.empty(), RuntimeValues.object());
    }
}
