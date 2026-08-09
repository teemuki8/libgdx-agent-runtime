package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SimulationAssertionEvaluatorTest {
    private static final EntityId BALL = EntityId.of("ball");
    private static final EntityId GROUND = EntityId.of("ground");

    @Test
    void finalNumericVectorAreaDistanceAndWrappedAngleBoundariesAreInclusive() {
        State state = new State();
        state.scalar = new BigDecimal("1.01");
        state.position = vector("1.01", "1");
        state.velocity = RuntimeValues.vector2(3, 4);
        state.angle = new BigDecimal("6.278185307179586");
        AgentRuntime runtime = runtime("simulation-assert-final", state);
        runtime.start();
        tick(runtime, () -> {});
        SimulationAssertionScope scope = scope(1, 1);

        assertStatus(runtime, scope, new SimulationAssertion.ScalarApproximatelyEquals(
                BALL, "scalar", BigDecimal.ONE, new BigDecimal("0.01")), AssertionStatus.PASS);
        assertStatus(runtime, scope, new SimulationAssertion.VectorApproximatelyEquals(
                BALL, "position", RuntimeValues.vector2(1, 1), new BigDecimal("0.01"),
                SimulationAssertion.VectorToleranceMode.COMPONENT), AssertionStatus.PASS);
        assertStatus(runtime, scope, new SimulationAssertion.VectorApproximatelyEquals(
                BALL, "position", RuntimeValues.vector2(1, 1), new BigDecimal("0.01"),
                SimulationAssertion.VectorToleranceMode.EUCLIDEAN), AssertionStatus.PASS);
        state.position = vector("1.01", "1.01");
        tick(runtime, () -> {});
        assertStatus(runtime, scope(2, 2), new SimulationAssertion.VectorApproximatelyEquals(
                BALL, "position", RuntimeValues.vector2(1, 1), new BigDecimal("0.01"),
                SimulationAssertion.VectorToleranceMode.EUCLIDEAN), AssertionStatus.FAIL);

        SimulationAssertion.Area area = new SimulationAssertion.Area(
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("1.01"), new BigDecimal("1.01"));
        assertStatus(runtime, scope(2, 2), new SimulationAssertion.VectorInArea(
                BALL, "position", area, SimulationAssertion.AreaRelation.INSIDE,
                SimulationAssertion.Extent.FINAL), AssertionStatus.PASS);
        assertStatus(runtime, scope(2, 2), new SimulationAssertion.VectorInArea(
                BALL, "position", area, SimulationAssertion.AreaRelation.OUTSIDE,
                SimulationAssertion.Extent.FINAL), AssertionStatus.FAIL);
        assertStatus(runtime, scope(2, 2), new SimulationAssertion.VectorMagnitudeAtMost(
                BALL, "linearVelocity", new BigDecimal("5"),
                SimulationAssertion.Extent.FINAL), AssertionStatus.PASS);
        assertStatus(runtime, scope(2, 2), new SimulationAssertion.VectorDistanceApproximatelyEquals(
                BALL, "position", GROUND, "position", new BigDecimal("4.99"),
                new BigDecimal("0.02")), AssertionStatus.PASS);
        assertStatus(runtime, scope(2, 2), new SimulationAssertion.WrappedAngleApproximatelyEquals(
                BALL, "angleRadians", BigDecimal.ZERO,
                new BigDecimal("6.283185307179586"), new BigDecimal("0.005")),
                AssertionStatus.PASS);
    }

    @Test
    void temporalPredicatesRetainTheFirstCompleteViolation() {
        State state = new State();
        AgentRuntime runtime = runtime("simulation-assert-temporal", state);
        runtime.start();
        state.velocity = RuntimeValues.vector2(1, 0);
        tick(runtime, () -> {});
        state.velocity = RuntimeValues.vector2(3, 0);
        tick(runtime, () -> {});

        SimulationAssertionResult result = evaluate(runtime, scope(1, 2),
                new SimulationAssertion.VectorMagnitudeAtMost(BALL, "linearVelocity",
                        new BigDecimal("2"), SimulationAssertion.Extent.EVERY_TICK));

        assertEquals(AssertionStatus.FAIL, result.status());
        assertEquals(2, result.evidence().getFirst().epochTick());
        assertEquals(Optional.of(new FrameId(2)), result.evidence().getFirst().frameId());
        assertFalse(result.evidenceIncomplete());
    }

    @Test
    void exactEventSelectorsAndObjectSelectorsUseCompletedTickEvidence() {
        State state = new State();
        state.activeContacts = active("ball-fixture", "ground-fixture");
        AgentRuntime runtime = runtime("simulation-assert-selectors", state);
        runtime.start();
        tick(runtime, () -> runtime.emit(EventSpec.type("box2d.contact.begin")
                .subject(BALL).source(GROUND)
                .attribute("key", key("ball-fixture", "ground-fixture"))
                .attribute("sensor", RuntimeValues.bool(false))));
        tick(runtime, () -> {});
        SimulationAssertion.EventSelector selector = new SimulationAssertion.EventSelector(
                EventType.of("box2d.contact.begin"), Optional.of(BALL), Optional.of(GROUND),
                RuntimeValues.object(
                        RuntimeValues.field("key", key("ball-fixture", "ground-fixture")),
                        RuntimeValues.field("sensor", RuntimeValues.bool(false))));

        assertStatus(runtime, scope(1, 2), new SimulationAssertion.EventCount(selector,
                SimulationAssertion.EventExpectation.AT_LEAST_ONE, 0), AssertionStatus.PASS);
        assertStatus(runtime, scope(1, 2), new SimulationAssertion.EventCount(selector,
                SimulationAssertion.EventExpectation.NONE, 0), AssertionStatus.FAIL);
        assertStatus(runtime, scope(1, 2), new SimulationAssertion.EventCount(selector,
                SimulationAssertion.EventExpectation.EXACT, 1), AssertionStatus.PASS);
        assertStatus(runtime, scope(1, 2), new SimulationAssertion.ObjectListContains(
                BALL, "activeContacts", RuntimeValues.object(
                        RuntimeValues.field("key", key("ball-fixture", "ground-fixture"))),
                SimulationAssertion.Extent.EVERY_TICK), AssertionStatus.PASS);

        SimulationAssertion.EventSelector wrongFixture = new SimulationAssertion.EventSelector(
                selector.eventType(), selector.subject(), selector.source(),
                RuntimeValues.object(RuntimeValues.field(
                        "key", key("other-fixture", "ground-fixture"))));
        assertStatus(runtime, scope(1, 2), new SimulationAssertion.EventCount(wrongFixture,
                SimulationAssertion.EventExpectation.AT_LEAST_ONE, 0), AssertionStatus.FAIL);
    }

    @Test
    void missingFailedAndAdapterIncompleteEvidenceNeverProducesNegativeOrTemporalPass() {
        State state = new State();
        AgentRuntime runtime = runtime("simulation-assert-incomplete", state);
        runtime.start();
        tick(runtime, () -> {});

        SimulationAssertion eventAbsent = new SimulationAssertion.EventCount(
                new SimulationAssertion.EventSelector(EventType.of("box2d.contact.begin"),
                        Optional.empty(), Optional.empty(), RuntimeValues.object()),
                SimulationAssertion.EventExpectation.NONE, 0);
        SimulationAssertionResult missing = evaluate(runtime, scope(1, 2), eventAbsent);
        assertEquals(AssertionStatus.INCONCLUSIVE, missing.status());
        assertEquals("missingTick", missing.evidence().getFirst().kind());
        assertEquals(2, missing.evidence().getFirst().epochTick());
        assertEquals(Optional.empty(), missing.evidence().getFirst().simulationTickId());
        assertEquals(Optional.empty(), missing.evidence().getFirst().frameId());

        state.complete = false;
        tick(runtime, () -> {});
        SimulationAssertionSpec guarded = new SimulationAssertionSpec(eventAbsent, List.of(
                new SimulationEvidenceRequirement(BALL, "complete")));
        SimulationAssertionResult guardedResult = runtime.assertions().evaluateSimulation(
                guarded, scope(1, 2));
        assertEquals(AssertionStatus.INCONCLUSIVE, guardedResult.status());
        assertTrue(guardedResult.evidenceIncomplete());

        assertStatus(runtime, scope(1, 2), new SimulationAssertion.ObjectListContains(
                BALL, "activeContacts", RuntimeValues.object(RuntimeValues.field(
                        "key", key("missing", "ground-fixture"))),
                SimulationAssertion.Extent.EVERY_TICK), AssertionStatus.FAIL);
    }

    @Test
    void callbackFailureAndTimelineEvictionAreInconclusiveButACompleteViolationStillFails() {
        State failedState = new State();
        AgentRuntime failed = runtime("simulation-assert-failed", failedState);
        failed.start();
        try {
            failed.simulation().tick(1, supplied -> {
                failed.emit(EventSpec.type("failed.tick.event"));
                throw new IllegalStateException("expected");
            });
        } catch (IllegalStateException expected) {
            // The attempted tick remains immutable timeline evidence.
        }
        assertStatus(failed, scope(1, 1), absentEvent(), AssertionStatus.INCONCLUSIVE);
        assertStatus(failed, scope(1, 1), new SimulationAssertion.EventCount(
                new SimulationAssertion.EventSelector(EventType.of("failed.tick.event"),
                        Optional.empty(), Optional.empty(), RuntimeValues.object()),
                SimulationAssertion.EventExpectation.AT_LEAST_ONE, 0),
                AssertionStatus.INCONCLUSIVE);

        State evictedState = new State();
        AgentRuntime evicted = runtime("simulation-assert-evicted", evictedState,
                new SimulationTimelineLimits(1, 1, 10, 100, 128));
        evicted.start();
        evictedState.velocity = RuntimeValues.vector2(3, 0);
        tick(evicted, () -> {});
        evictedState.velocity = RuntimeValues.vector2(1, 0);
        tick(evicted, () -> {});
        assertStatus(evicted, scope(1, 2), absentEvent(), AssertionStatus.INCONCLUSIVE);
        assertStatus(evicted, scope(1, 2), new SimulationAssertion.VectorMagnitudeAtMost(
                BALL, "linearVelocity", new BigDecimal("2"),
                SimulationAssertion.Extent.EVERY_TICK), AssertionStatus.INCONCLUSIVE);
    }

    @Test
    void allOfUsesBoundedTermOrderAndIncompleteSemantics() {
        State state = new State();
        AgentRuntime runtime = runtime("simulation-assert-all", state);
        runtime.start();
        tick(runtime, () -> {});
        SimulationAssertion pass = new SimulationAssertion.PropertyEquals(
                BALL, "awake", RuntimeValues.bool(true));
        SimulationAssertion fail = new SimulationAssertion.VectorMagnitudeAtMost(
                BALL, "linearVelocity", BigDecimal.ZERO, SimulationAssertion.Extent.FINAL);

        SimulationAssertionResult result = evaluate(runtime, scope(1, 1),
                new SimulationAssertion.AllOf(List.of(pass, fail)));

        assertEquals(AssertionStatus.FAIL, result.status());
        assertEquals("allOf", result.assertionType());
        assertEquals("linearVelocity", result.evidence().getFirst().property().orElseThrow());
    }

    @Test
    void captureFailureAndFrameTruncationRetainTypedIncompleteEvidence() {
        State state = new State();
        long[] calls = {0};
        AgentRuntime captureFailed = AgentRuntime.builder()
                .sessionId(SessionId.of("simulation-assert-capture-failed"))
                .clock(() -> calls[0]++ == 0 ? 1 : -1).build();
        captureFailed.start();
        try {
            captureFailed.simulation().tick(1, supplied -> supplied);
        } catch (IllegalStateException expected) {
            // Capture failure is the evidence under test.
        }
        SimulationAssertionResult missingFrame = evaluate(
                captureFailed, scope(1, 1), absentEvent());
        assertEquals(AssertionStatus.INCONCLUSIVE, missingFrame.status());
        assertEquals("missingFrame", missingFrame.evidence().getFirst().kind());
        assertTrue(missingFrame.evidence().getFirst().simulationTickId().isPresent());
        assertEquals(Optional.empty(), missingFrame.evidence().getFirst().frameId());

        RuntimeLimits defaults = RuntimeLimits.developmentDefaults();
        RuntimeLimits oneEntity = new RuntimeLimits(defaults.retainedFrames(),
                defaults.retainedEvents(), 1, defaults.propertiesPerEntity(),
                defaults.decisionsPerFrame(), defaults.candidatesPerDecision(),
                defaults.attributesPerItem(), defaults.stringLength(),
                defaults.collectionLength(), defaults.nestingDepth(), defaults.queryResults());
        AgentRuntime truncated = AgentRuntime.builder()
                .sessionId(SessionId.of("simulation-assert-truncated"))
                .configuration(new RuntimeConfiguration(true, oneEntity))
                .clock(new AtomicLong()::incrementAndGet).build();
        truncated.entities().register(BALL, EntityType.of("body"), () -> "ball",
                inspector -> inspector.property("awake", () -> state.awake));
        truncated.entities().register(GROUND, EntityType.of("body"), () -> "ground",
                inspector -> inspector.property("awake", () -> true));
        truncated.start();
        tick(truncated, () -> {});
        SimulationAssertionResult incompleteFrame = evaluate(truncated, scope(1, 1),
                new SimulationAssertion.PropertyEquals(
                        BALL, "awake", RuntimeValues.bool(true)));
        assertEquals(AssertionStatus.INCONCLUSIVE, incompleteFrame.status());
        assertEquals("incompleteFrame", incompleteFrame.evidence().getFirst().kind());
    }

    @Test
    void eventEvidenceIsRetainedOnlyToTheRequestedBound() {
        State state = new State();
        AgentRuntime runtime = runtime("simulation-assert-event-bound", state);
        runtime.start();
        tick(runtime, () -> {
            for (int index = 0; index < 20; index++) {
                runtime.emit(EventSpec.type("pulse"));
            }
        });
        SimulationAssertion assertion = new SimulationAssertion.EventCount(
                new SimulationAssertion.EventSelector(EventType.of("pulse"), Optional.empty(),
                        Optional.empty(), RuntimeValues.object()),
                SimulationAssertion.EventExpectation.EXACT, 1);

        SimulationAssertionResult result = runtime.assertions().evaluateSimulation(
                SimulationAssertionSpec.of(assertion),
                new SimulationAssertionScope(new ExecutionEpochId(0), 1, 1, 2));

        assertEquals(AssertionStatus.FAIL, result.status());
        assertEquals(2, result.evidence().size());
        assertEquals(RuntimeValues.integer(2), result.observed().orElseThrow());
    }

    @Test
    void oversizedRetainedValuesProduceBoundedFailureEvidence() {
        State state = new State();
        RuntimeValue.ObjectValue item = RuntimeValues.object(
                RuntimeValues.field("a", RuntimeValues.bool(true)),
                RuntimeValues.field("b", RuntimeValues.bool(true)),
                RuntimeValues.field("c", RuntimeValues.bool(true)),
                RuntimeValues.field("d", RuntimeValues.bool(true)));
        state.activeContacts = new RuntimeValue.ListValue(
                java.util.Collections.nCopies(256, item));
        AgentRuntime runtime = runtime("simulation-assert-result-value-bound", state);
        runtime.start();
        tick(runtime, () -> {});

        SimulationAssertionResult result = evaluate(runtime, scope(1, 1),
                new SimulationAssertion.ObjectListContains(BALL, "activeContacts",
                        RuntimeValues.object(RuntimeValues.field(
                                "missing", RuntimeValues.bool(true))),
                        SimulationAssertion.Extent.FINAL));

        assertEquals(AssertionStatus.FAIL, result.status());
        assertEquals(Optional.empty(), result.observed());
        assertEquals(Optional.empty(), result.evidence().getFirst().observed());
        assertFalse(result.evidenceIncomplete());

        SimulationAssertion matching = new SimulationAssertion.ObjectListContains(
                BALL, "activeContacts", RuntimeValues.object(RuntimeValues.field(
                        "a", RuntimeValues.bool(true))), SimulationAssertion.Extent.FINAL);
        SimulationAssertionResult passed = evaluate(runtime, scope(1, 1), matching);
        assertEquals(AssertionStatus.PASS, passed.status());
        assertEquals(Optional.empty(), passed.observed());

        state.complete = false;
        tick(runtime, () -> {});
        SimulationAssertionResult inconclusive = runtime.assertions().evaluateSimulation(
                new SimulationAssertionSpec(matching, List.of(
                        new SimulationEvidenceRequirement(BALL, "complete"))), scope(2, 2));
        assertEquals(AssertionStatus.INCONCLUSIVE, inconclusive.status());
        assertEquals(Optional.empty(), inconclusive.observed());
        assertTrue(inconclusive.evidenceIncomplete());
    }

    private static SimulationAssertion absentEvent() {
        return new SimulationAssertion.EventCount(new SimulationAssertion.EventSelector(
                EventType.of("missing.event"), Optional.empty(), Optional.empty(),
                RuntimeValues.object()), SimulationAssertion.EventExpectation.NONE, 0);
    }

    private static void assertStatus(AgentRuntime runtime, SimulationAssertionScope scope,
            SimulationAssertion assertion, AssertionStatus expected) {
        assertEquals(expected, evaluate(runtime, scope, assertion).status());
    }

    private static SimulationAssertionResult evaluate(AgentRuntime runtime,
            SimulationAssertionScope scope, SimulationAssertion assertion) {
        return runtime.assertions().evaluateSimulation(SimulationAssertionSpec.of(assertion), scope);
    }

    private static SimulationAssertionScope scope(long from, long to) {
        return new SimulationAssertionScope(new ExecutionEpochId(0), from, to, 8);
    }

    private static AgentRuntime runtime(String id, State state) {
        return runtime(id, state, SimulationTimelineLimits.developmentDefaults());
    }

    private static AgentRuntime runtime(String id, State state, SimulationTimelineLimits limits) {
        AtomicLong clock = new AtomicLong();
        AgentRuntime runtime = AgentRuntime.builder().sessionId(SessionId.of(id))
                .clock(clock::incrementAndGet).simulationTimelineLimits(limits).build();
        runtime.entities().register(BALL, EntityType.of("body"), () -> "ball", inspector -> inspector
                .property("activeContacts", () -> state.activeContacts)
                .property("angleRadians", () -> new RuntimeValue.DecimalValue(state.angle))
                .property("awake", () -> state.awake)
                .property("complete", () -> state.complete)
                .property("linearVelocity", () -> state.velocity)
                .property("position", () -> state.position)
                .property("scalar", () -> new RuntimeValue.DecimalValue(state.scalar)));
        runtime.entities().register(GROUND, EntityType.of("body"), () -> "ground",
                inspector -> inspector.property("position", () -> vector("4.01", "5.01")));
        return runtime;
    }

    private static void tick(AgentRuntime runtime, Runnable mutation) {
        runtime.simulation().tick(1, supplied -> {
            mutation.run();
            return supplied;
        }).orElseThrow();
    }

    private static RuntimeValue.ObjectValue key(String fixtureA, String fixtureB) {
        return RuntimeValues.object(
                RuntimeValues.field("fixtureAId", RuntimeValues.string(fixtureA)),
                RuntimeValues.field("fixtureBId", RuntimeValues.string(fixtureB)));
    }

    private static RuntimeValue.ListValue active(String fixtureA, String fixtureB) {
        return RuntimeValues.list(RuntimeValues.object(
                RuntimeValues.field("enabled", RuntimeValues.bool(true)),
                RuntimeValues.field("key", key(fixtureA, fixtureB))));
    }

    private static RuntimeValue.Vector2Value vector(String x, String y) {
        return new RuntimeValue.Vector2Value(
                new RuntimeValue.DecimalValue(new BigDecimal(x)),
                new RuntimeValue.DecimalValue(new BigDecimal(y)));
    }

    private static final class State {
        private BigDecimal scalar = BigDecimal.ONE;
        private RuntimeValue.Vector2Value position = RuntimeValues.vector2(1, 1);
        private RuntimeValue.Vector2Value velocity = RuntimeValues.vector2(1, 0);
        private BigDecimal angle = BigDecimal.ZERO;
        private boolean awake = true;
        private boolean complete = true;
        private RuntimeValue.ListValue activeContacts = active(
                "ball-fixture", "ground-fixture");
    }
}
