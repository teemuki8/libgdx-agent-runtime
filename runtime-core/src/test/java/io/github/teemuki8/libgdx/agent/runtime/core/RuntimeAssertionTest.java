package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class RuntimeAssertionTest {
    @Test
    void evaluatesClosedAssertionTypesAgainstCompletedEpochEvidence() {
        long[] health = {100};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("game"))
                .clock(() -> 1)
                .build();
        runtime.entities().register(EntityId.of("enemy-1"), EntityType.of("enemy"), () -> "Enemy",
                inspector -> inspector.property("health", () -> health[0]));
        runtime.start();
        health[0] = 75;
        runtime.frame(1, () -> runtime.emit(EventSpec.type("damage.applied")
                .subject(EntityId.of("enemy-1"))));
        health[0] = 60;
        runtime.frame(1, () -> {
            try (DecisionScope decision = runtime.beginDecision(
                    DecisionType.of("target.select"), EntityId.of("enemy-1"))) {
                decision.reject(EntityId.of("enemy-2"), Reason.of("out-of-range"));
                decision.accept(EntityId.of("enemy-1"), Reason.of("nearest"));
                decision.choose(EntityId.of("enemy-1"), Reason.of("nearest"));
            }
        });
        AssertionScope scope = new AssertionScope(new ExecutionEpochId(0),
                new FrameRange(new FrameId(0), new FrameId(2)), 16);

        assertEquals(AssertionStatus.PASS, runtime.assertions().evaluate(
                new RuntimeAssertion.EntityExists(EntityId.of("enemy-1")), scope).status());
        assertEquals(AssertionStatus.PASS, runtime.assertions().evaluate(
                new RuntimeAssertion.PropertyEquals(EntityId.of("enemy-1"), "health",
                        RuntimeValues.integer(60)), scope).status());
        assertEquals(AssertionStatus.PASS, runtime.assertions().evaluate(
                new RuntimeAssertion.PropertyChangesFrom(EntityId.of("enemy-1"), "health",
                        RuntimeValues.integer(100)), scope).status());
        assertEquals(AssertionStatus.PASS, runtime.assertions().evaluate(
                new RuntimeAssertion.PropertyRemainsWithinRange(EntityId.of("enemy-1"), "health",
                        BigDecimal.valueOf(50), BigDecimal.valueOf(100)), scope).status());
        assertEquals(AssertionStatus.PASS, runtime.assertions().evaluate(
                new RuntimeAssertion.EventOccurs(EventType.of("damage.applied")), scope).status());
        assertEquals(AssertionStatus.PASS, runtime.assertions().evaluate(
                new RuntimeAssertion.EventOccursExactly(EventType.of("damage.applied"), 1),
                scope).status());
        assertEquals(AssertionStatus.PASS, runtime.assertions().evaluate(
                new RuntimeAssertion.DecisionSelected(DecisionType.of("target.select"),
                        EntityId.of("enemy-1")), scope).status());
        assertEquals(AssertionStatus.PASS, runtime.assertions().evaluate(
                new RuntimeAssertion.DecisionRejected(DecisionType.of("target.select"),
                        EntityId.of("enemy-2")), scope).status());
        assertEquals(AssertionStatus.PASS, runtime.assertions().evaluate(
                new RuntimeAssertion.EntityCountStaysBelow(Optional.of(EntityType.of("enemy")), 2),
                scope).status());
        assertEquals(AssertionStatus.PASS, runtime.assertions().evaluate(
                new RuntimeAssertion.SnapshotsEquivalent(new FrameId(1), new FrameId(2),
                        new SnapshotComparisonScope(List.of(EntityId.of("enemy-1")), List.of(),
                                List.of("health"), false, false)), scope).status());
    }

    @Test
    void negativeAndRemainsAssertionsRequireCompleteUntruncatedEvidence() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("game"))
                .clock(() -> 1)
                .build();
        runtime.start();
        AssertionScope complete = new AssertionScope(new ExecutionEpochId(0),
                new FrameRange(new FrameId(0), new FrameId(0)), 4);
        AssertionScope future = new AssertionScope(new ExecutionEpochId(0),
                new FrameRange(new FrameId(0), new FrameId(1)), 4);

        assertEquals(AssertionStatus.PASS, runtime.assertions().evaluate(
                new RuntimeAssertion.EntityDoesNotExist(EntityId.of("missing")), complete).status());
        assertEquals(AssertionStatus.PASS, runtime.assertions().evaluate(
                new RuntimeAssertion.EventDoesNotOccur(EventType.of("missing.event")),
                complete).status());
        assertEquals(AssertionStatus.INCONCLUSIVE, runtime.assertions().evaluate(
                new RuntimeAssertion.EntityDoesNotExist(EntityId.of("missing")), future).status());
        assertEquals(AssertionStatus.INCONCLUSIVE, runtime.assertions().evaluate(
                new RuntimeAssertion.EventDoesNotOccur(EventType.of("missing.event")),
                future).status());
    }

    @Test
    void evictionAndCaptureDiagnosticsProduceInconclusiveCompletenessResults() {
        RuntimeLimits defaults = RuntimeLimits.developmentDefaults();
        RuntimeLimits limits = new RuntimeLimits(1, defaults.retainedEvents(),
                defaults.entitiesPerSnapshot(), defaults.propertiesPerEntity(),
                defaults.decisionsPerFrame(), defaults.candidatesPerDecision(),
                defaults.attributesPerItem(), defaults.stringLength(),
                defaults.collectionLength(), defaults.nestingDepth(), defaults.queryResults());
        AgentRuntime evicted = AgentRuntime.builder()
                .sessionId(SessionId.of("evicted"))
                .configuration(new RuntimeConfiguration(true, limits))
                .clock(() -> 1)
                .build();
        evicted.start();
        evicted.frame(1, () -> {});
        AssertionScope evictedScope = new AssertionScope(new ExecutionEpochId(0),
                new FrameRange(new FrameId(0), new FrameId(1)), 4);
        assertEquals(AssertionStatus.INCONCLUSIVE, evicted.assertions().evaluate(
                new RuntimeAssertion.EventDoesNotOccur(EventType.of("missing.event")),
                evictedScope).status());

        AgentRuntime failed = AgentRuntime.builder()
                .sessionId(SessionId.of("failed"))
                .clock(() -> 1)
                .build();
        failed.entities().register(EntityId.of("enemy-1"), EntityType.of("enemy"), () -> "Enemy",
                inspector -> inspector.property("health", (java.util.function.LongSupplier) () -> {
                    throw new IllegalStateException("capture failed");
                }));
        failed.start();
        AssertionScope failedScope = new AssertionScope(new ExecutionEpochId(0),
                new FrameRange(new FrameId(0), new FrameId(0)), 4);
        AssertionResult result = failed.assertions().evaluate(
                new RuntimeAssertion.PropertyEquals(EntityId.of("enemy-1"), "health",
                        RuntimeValues.integer(75)),
                failedScope);
        assertEquals(AssertionStatus.INCONCLUSIVE, result.status());
        assertEquals(true, result.evidenceIncomplete());
    }

    @Test
    @Timeout(2)
    void handlesTheMaximumFrameIdWithoutLoopOverflow() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("maximum"))
                .clock(() -> 1)
                .build();
        runtime.start();
        AssertionResult result = runtime.assertions().evaluate(
                new RuntimeAssertion.EventDoesNotOccur(EventType.of("missing.event")),
                new AssertionScope(new ExecutionEpochId(0),
                        new FrameRange(new FrameId(Long.MAX_VALUE),
                                new FrameId(Long.MAX_VALUE)), 4));

        assertEquals(AssertionStatus.INCONCLUSIVE, result.status());
    }

    @Test
    void validatesHardBoundsBeforeEvaluation() {
        assertThrows(IllegalArgumentException.class, () -> new AssertionScope(
                new ExecutionEpochId(0), new FrameRange(new FrameId(0), new FrameId(1001)), 4));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeAssertion.EventOccursExactly(
                EventType.of("event"), 0));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotComparisonScope(
                List.of(), List.of(), List.of(), false, false));
    }
}
