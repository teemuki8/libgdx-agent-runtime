package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class AgentRuntimeTest {
    @Test
    void capturesVerticalSliceWithoutInventingEventCausality() {
        MutableEnemy enemy = new MutableEnemy("enemy-1", 100, 20, 5);
        AgentRuntime runtime = runtime(RuntimeLimits.developmentDefaults());
        runtime.entities().register(EntityId.of(enemy.id), EntityType.of("enemy"),
                () -> "Enemy 1", inspector -> inspector
                        .property("health", enemy::health)
                        .property("position", () -> RuntimeValues.vector2(enemy.x, enemy.y))
                        .property("state", () -> RuntimeValues.enumValue(enemy.state)));
        runtime.start();

        runtime.frame(16_000_000, () -> {
            runtime.emit(EventSpec.type("damage.applied")
                    .subject(EntityId.of("enemy-1"))
                    .source(EntityId.of("projectile-3"))
                    .attribute("amount", RuntimeValues.integer(25)));
            enemy.health -= 25;
        });

        FrameSnapshot frame = runtime.latestFrame().orElseThrow();
        assertEquals(new FrameId(1), frame.frameId());
        EntitySnapshot snapshot = frame.entity(EntityId.of("enemy-1")).orElseThrow();
        assertEquals(RuntimeValues.integer(75), snapshot.property("health").orElseThrow());
        assertEquals(RuntimeValues.vector2(20, 5), snapshot.property("position").orElseThrow());
        PropertyChange change = frame.changes().getFirst();
        assertEquals(ChangeKind.PROPERTY_CHANGED, change.kind());
        assertEquals(RuntimeValues.integer(100), change.before().orElseThrow());
        assertEquals(RuntimeValues.integer(75), change.after().orElseThrow());
        assertEquals(ChangeCause.unknown(), change.cause());
        RuntimeEvent event = frame.events().getFirst();
        assertEquals("damage.applied", event.type().value());
        assertEquals("projectile-3", event.source().orElseThrow().value());
        assertEquals(RuntimeValues.integer(25), event.attributes().getFirst().value());
    }

    @Test
    void capturesAddedRemovedAndDynamicPropertiesDeterministically() {
        AgentRuntime runtime = runtime(RuntimeLimits.developmentDefaults());
        MutableEnemy enemy = new MutableEnemy("enemy-1", 100, 0, 0);
        boolean[] include = {false};
        boolean[] includeState = {false};
        runtime.entities().registerSource("enemies", () -> include[0]
                ? Stream.of(InspectableEntity.of(EntityId.of(enemy.id), EntityType.of("enemy"),
                        () -> "Enemy", inspector -> {
                            inspector.property("health", enemy::health);
                            if (includeState[0]) {
                                inspector.property("state",
                                        () -> RuntimeValues.enumValue(enemy.state));
                            }
                        }))
                : Stream.empty());
        runtime.start();

        include[0] = true;
        runtime.frame(1, () -> {});
        assertEquals(ChangeKind.ENTITY_ADDED,
                runtime.latestFrame().orElseThrow().changes().getFirst().kind());

        includeState[0] = true;
        runtime.frame(1, () -> {});
        assertEquals(ChangeKind.PROPERTY_ADDED,
                runtime.latestFrame().orElseThrow().changes().getFirst().kind());

        includeState[0] = false;
        runtime.frame(1, () -> {});
        assertEquals(ChangeKind.PROPERTY_REMOVED,
                runtime.latestFrame().orElseThrow().changes().getFirst().kind());

        include[0] = false;
        runtime.frame(1, () -> {});
        assertEquals(ChangeKind.ENTITY_REMOVED,
                runtime.latestFrame().orElseThrow().changes().getFirst().kind());
    }

    @Test
    void retainsProviderFailuresAndDuplicateDynamicIdsAsDiagnostics() {
        AgentRuntime runtime = runtime(RuntimeLimits.developmentDefaults());
        runtime.entities().register(EntityId.of("bad"), EntityType.of("enemy"),
                () -> "Bad", inspector -> inspector.property("health",
                        (java.util.function.Supplier<RuntimeValue>) () -> {
                    throw new IllegalStateException("hidden stack");
                }));
        runtime.entities().registerSource("duplicates", () -> Stream.of(
                InspectableEntity.of(EntityId.of("bad"), EntityType.of("enemy"),
                        () -> "Duplicate", inspector -> inspector.property("x", () -> 1L))));
        runtime.start();

        List<CaptureDiagnostic> diagnostics =
                runtime.latestFrame().orElseThrow().stats().diagnostics();
        assertEquals(2, diagnostics.size());
        assertEquals(Optional.of("health"), diagnostics.stream()
                .filter(value -> value.property().isPresent()).findFirst().orElseThrow().property());
        assertFalse(diagnostics.getFirst().message().contains("\n"));
    }

    @Test
    void unregisteringReleasesProviderAndProducesRemoval() {
        AgentRuntime runtime = runtime(RuntimeLimits.developmentDefaults());
        EntityRegistration registration = runtime.entities().register(
                EntityId.of("enemy"), EntityType.of("enemy"), () -> "Enemy",
                inspector -> inspector.property("health", () -> 10L));
        runtime.start();
        registration.close();
        runtime.frame(1, () -> {});
        assertEquals(ChangeKind.ENTITY_REMOVED,
                runtime.latestFrame().orElseThrow().changes().getFirst().kind());
    }

    @Test
    void recordsDecisionFilteringAbortionAndExplicitCause() {
        MutableEnemy enemy = new MutableEnemy("enemy", 100, 0, 0);
        AgentRuntime runtime = runtime(RuntimeLimits.developmentDefaults());
        runtime.entities().register(EntityId.of(enemy.id), EntityType.of("enemy"),
                () -> "Enemy", inspector -> inspector.property("health", enemy::health));
        runtime.start();

        runtime.frame(1, () -> {
            try (DecisionScope decision = runtime.beginDecision(
                    DecisionType.of("target-selection"), EntityId.of("tower-1"))) {
                decision.reject(EntityId.of("enemy-4"), Reason.of("out-of-range"),
                        RuntimeValues.field("distance", RuntimeValues.decimal(14.2)));
                decision.accept(EntityId.of("enemy"), RuntimeValues.field(
                        "distance", RuntimeValues.decimal(8.1)));
                decision.choose(EntityId.of("enemy"), Reason.of("nearest-in-range"));
                runtime.causeNextChange(EntityId.of("enemy"), "health",
                        ChangeCause.decision(decision.id().orElseThrow()));
                enemy.health = 75;
            }
        });

        DecisionTrace trace = runtime.latestFrame().orElseThrow().decisions().getFirst();
        assertEquals(DecisionTrace.Completion.COMPLETED, trace.completion());
        assertEquals("enemy", trace.chosenCandidate().orElseThrow().value());
        assertEquals(ChangeCause.Kind.DECISION,
                runtime.latestFrame().orElseThrow().changes().getFirst().cause().kind());
        QueryPage<DecisionTrace> filtered = runtime.decisions(new DecisionQuery(
                FrameRange.of(0, 1), Optional.of(DecisionType.of("target-selection")),
                Optional.of(EntityId.of("tower-1")), Optional.of(EntityId.of("enemy")),
                Optional.of("out-of-range"), 10));
        assertEquals(1, filtered.items().size());

        runtime.beginFrame(1);
        runtime.beginDecision(DecisionType.of("path"), EntityId.of("enemy"));
        runtime.endFrame();
        assertEquals(DecisionTrace.Completion.ABORTED,
                runtime.latestFrame().orElseThrow().decisions().getFirst().completion());
    }

    @Test
    void recordsAndExactlyFiltersExplicitFactMetadata() {
        MutableEnemy enemy = new MutableEnemy("enemy", 100, 0, 0);
        AgentRuntime runtime = runtime(RuntimeLimits.developmentDefaults());
        runtime.entities().register(EntityId.of(enemy.id), EntityType.of("enemy"),
                () -> "Enemy", inspector -> inspector.property("health", enemy::health));
        runtime.start();
        FactMetadata metadata = FactMetadata.empty()
                .withSourceSubsystem("combat")
                .withSourceLocation("DamageSystem.java:84")
                .withCorrelationId("attack-172");

        runtime.frame(1, () -> {
            runtime.emit(EventSpec.type("damage.applied")
                    .source(EntityId.of("attacker"))
                    .sourceSubsystem("combat")
                    .sourceLocation("DamageSystem.java:84")
                    .correlationId("attack-172"));
            try (DecisionScope decision = runtime.beginDecision(
                    DecisionType.of("attack"), EntityId.of("attacker"), metadata)) {
                assertTrue(decision.id().isPresent());
                runtime.causeNextChange(EntityId.of("enemy"), "health",
                        ChangeCause.semantic("damage").withMetadata(metadata));
                enemy.health = 75;
            }
        });

        FrameSnapshot frame = runtime.latestFrame().orElseThrow();
        assertEquals("combat", frame.events().getFirst().metadata()
                .sourceSubsystem().orElseThrow());
        assertEquals("attacker", frame.events().getFirst().source().orElseThrow().value());
        assertEquals("attack-172", frame.decisions().getFirst().metadata()
                .correlationId().orElseThrow());
        assertEquals(1, runtime.changes(new ChangeQuery(FrameRange.of(0, 1), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of("combat"),
                Optional.of("attack-172"), 10)).items().size());
        assertEquals(1, runtime.events(new EventQuery(FrameRange.of(0, 1), Optional.empty(), false,
                Optional.empty(), Optional.empty(), Optional.of("combat"),
                Optional.of("attack-172"), 10)).items().size());
        assertEquals(1, runtime.decisions(new DecisionQuery(FrameRange.of(0, 1),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("combat"), Optional.of("attack-172"), 10)).items().size());
    }

    @Test
    void callbackFailureRetainsFrameAndAbortedDecisionThenRethrows() {
        AgentRuntime runtime = runtime(RuntimeLimits.developmentDefaults());
        runtime.start();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runtime.frame(1, () -> {
                    try (DecisionScope decision = runtime.beginDecision(
                            DecisionType.of("test"), EntityId.of("actor"))) {
                        assertTrue(decision.id().isPresent());
                        throw new IllegalStateException("boom");
                    }
                }));
        assertEquals("boom", failure.getMessage());
        assertEquals(DecisionTrace.Completion.ABORTED,
                runtime.latestFrame().orElseThrow().decisions().getFirst().completion());
    }

    @Test
    void rejectsInvalidLifecycleAndNestedDecisions() {
        AgentRuntime runtime = runtime(RuntimeLimits.developmentDefaults());
        assertThrows(AgentRuntimeException.class, runtime::endFrame);
        runtime.start();
        assertThrows(AgentRuntimeException.class, runtime::start);
        assertThrows(AgentRuntimeException.class,
                () -> runtime.emit(EventSpec.type("invalid")));
        runtime.beginFrame(1);
        assertThrows(AgentRuntimeException.class, () -> runtime.beginFrame(1));
        runtime.beginDecision(DecisionType.of("one"), EntityId.of("actor"));
        assertThrows(AgentRuntimeException.class,
                () -> runtime.beginDecision(DecisionType.of("two"), EntityId.of("actor")));
        assertThrows(AgentRuntimeException.class, runtime::close);
        runtime.endFrame();
        runtime.close();
        assertEquals(RuntimeStatus.CLOSED, runtime.status());
        assertTrue(runtime.latestFrame().isPresent());
        AgentRuntimeException closed =
                assertThrows(AgentRuntimeException.class, () -> runtime.beginFrame(1));
        assertEquals(RuntimeErrorCode.RUNTIME_CLOSED, closed.code());
    }

    @Test
    void completedFramesAllowConcurrentReadsAndEvictBoundedly() throws Exception {
        RuntimeLimits limits = new RuntimeLimits(3, 10, 10, 10, 10, 10, 10, 100, 10, 5, 100);
        AgentRuntime runtime = runtime(limits);
        runtime.start();
        IntStream.range(0, 10).forEach(ignored -> runtime.frame(1, () -> {}));
        assertEquals(3, runtime.frames(FrameRange.of(0, 10), 100).items().size());
        assertTrue(runtime.frames(FrameRange.of(0, 10), 100).requestedRangePartiallyEvicted());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<FrameSnapshot>> reads = IntStream.range(0, 100)
                    .mapToObj(ignored -> executor.submit(() -> runtime.latestFrame().orElseThrow()))
                    .toList();
            for (var read : reads) {
                assertEquals(new FrameId(10), read.get().frameId());
            }
        }
    }

    @Test
    void disabledRuntimeExecutesGameCodeWithoutCaptureOrProviderRetention() {
        AgentRuntime runtime = AgentRuntime.builder()
                .configuration(RuntimeConfiguration.disabled()).build();
        runtime.entities().register(EntityId.of("unused"), EntityType.of("enemy"),
                () -> "Unused", inspector -> inspector.property("value", () -> 1L));
        runtime.start();
        int[] value = {0};
        runtime.frame(1, () -> {
            value[0]++;
            assertTrue(runtime.emit(EventSpec.type("ignored")).isEmpty());
            try (DecisionScope decision = runtime.beginDecision(
                    DecisionType.of("ignored"), EntityId.of("ignored"))) {
                assertTrue(decision.id().isEmpty());
            }
        });
        assertEquals(1, value[0]);
        assertTrue(runtime.latestFrame().isEmpty());
        assertEquals(RuntimeStatus.DISABLED, runtime.status());
    }

    @Test
    void newExecutionEpochCapturesBaselineWithoutCrossEpochDiffs() {
        long[] health = {100};
        AgentRuntime runtime = runtime(new RuntimeLimits(2, 10, 10, 10, 10, 10, 10, 100,
                10, 5, 100));
        runtime.entities().register(EntityId.of("enemy"), EntityType.of("enemy"),
                () -> "Enemy", inspector -> inspector.property("health", () -> health[0]));
        runtime.start();
        health[0] = 75;
        runtime.frame(1, () -> {});
        assertFalse(runtime.latestFrame().orElseThrow().changes().isEmpty());

        health[0] = 100;
        FrameId baseline = runtime.startEpoch(BaselineKind.SCENARIO_RESET);

        FrameSnapshot frame = runtime.latestFrame().orElseThrow();
        assertEquals(new FrameId(2), baseline);
        assertEquals(new ExecutionEpochId(1), frame.executionEpochId());
        assertEquals(Optional.of(BaselineKind.SCENARIO_RESET), frame.baselineKind());
        assertTrue(frame.changes().isEmpty());
        assertEquals(new ExecutionEpochId(0),
                runtime.frame(new FrameId(1)).orElseThrow().executionEpochId());
        assertTrue(runtime.frames(new ExecutionEpochId(0), 10)
                .requestedEpochPartiallyEvicted());
        assertFalse(runtime.frames(new ExecutionEpochId(1), 10)
                .requestedEpochPartiallyEvicted());
        assertThrows(IllegalArgumentException.class,
                () -> runtime.startEpoch(BaselineKind.INITIAL));
    }

    @Test
    void truncatesEvidenceExplicitlyAndBoundsEventRetention() {
        RuntimeLimits limits = new RuntimeLimits(10, 2, 1, 1, 1, 1, 1, 4, 1, 2, 10);
        AgentRuntime runtime = runtime(limits);
        runtime.entities().register(EntityId.of("enemy"), EntityType.of("enemy"),
                () -> "Long name", inspector -> inspector
                        .property("a", () -> RuntimeValues.string("12345"))
                        .property("b", () -> 2L));
        runtime.start();
        runtime.frame(1, () -> IntStream.range(0, 3).forEach(index ->
                runtime.emit(EventSpec.type("event").attribute("value",
                        RuntimeValues.list(RuntimeValues.string("longer"))))));

        FrameSnapshot frame = runtime.latestFrame().orElseThrow();
        assertTrue(frame.entity(EntityId.of("enemy")).orElseThrow().truncated());
        assertEquals(2, frame.events().size());
        assertTrue(frame.stats().truncations().stream()
                .anyMatch(value -> value.dimension().equals("frame.events")));
    }

    @Test
    void manyFramesAndEventsRemainWithinConfiguredRetention() {
        RuntimeLimits limits =
                new RuntimeLimits(20, 50, 10, 10, 10, 10, 10, 100, 10, 5, 100);
        AgentRuntime runtime = runtime(limits);
        runtime.start();
        IntStream.range(0, 200).forEach(frame -> runtime.frame(1, () -> IntStream.range(0, 5)
                .forEach(event -> runtime.emit(EventSpec.type("small.event")
                        .attribute("value", RuntimeValues.integer(event))))));

        assertTrue(runtime.frames(FrameRange.of(0, 200), 100).items().size()
                <= limits.retainedFrames());
        assertTrue(runtime.events(new EventQuery(
                FrameRange.of(0, 200), Optional.empty(), false,
                Optional.empty(), Optional.empty(), 50)).items().size()
                <= limits.retainedEvents());
    }

    @Test
    void preservesEventOrderOptionalPartiesUnchangedPropertiesAndCandidateBounds() {
        RuntimeLimits limits =
                new RuntimeLimits(10, 10, 10, 10, 10, 1, 10, 100, 10, 5, 100);
        AgentRuntime runtime = runtime(limits);
        runtime.entities().register(EntityId.of("enemy"), EntityType.of("enemy"),
                () -> "Enemy", inspector -> inspector.property("health", () -> 100L));
        runtime.start();
        runtime.frame(1, () -> {
            runtime.emit(EventSpec.type("wave.started"));
            runtime.emit(EventSpec.type("wave.progress")
                    .subject(EntityId.of("enemy")));
            try (DecisionScope decision = runtime.beginDecision(
                    DecisionType.of("candidate-bound"), EntityId.of("enemy"))) {
                decision.accept(EntityId.of("first"));
                decision.reject(EntityId.of("second"), Reason.of("lower-priority"));
            }
        });

        FrameSnapshot frame = runtime.latestFrame().orElseThrow();
        assertTrue(frame.changes().isEmpty(), "unchanged properties must not create changes");
        assertEquals(List.of("wave.started", "wave.progress"),
                frame.events().stream().map(event -> event.type().value()).toList());
        assertTrue(frame.events().getFirst().subject().isEmpty());
        assertTrue(frame.events().getFirst().source().isEmpty());
        assertEquals(1, frame.decisions().getFirst().candidates().size());
        assertEquals(1, frame.decisions().getFirst().truncations().size());
        assertTrue(runtime.events(new EventQuery(
                FrameRange.of(0, 1), Optional.of("wave."), true,
                Optional.empty(), Optional.empty(), 10)).items().size() == 2);
    }

    private static AgentRuntime runtime(RuntimeLimits limits) {
        AtomicLong time = new AtomicLong();
        return AgentRuntime.builder()
                .sessionId(SessionId.of("test-session-" + System.nanoTime()))
                .configuration(new RuntimeConfiguration(true, limits))
                .clock(time::incrementAndGet)
                .wallClock(Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC))
                .build();
    }

    private static final class MutableEnemy {
        private final String id;
        private long health;
        private final double x;
        private final double y;
        private String state = "MOVING";

        MutableEnemy(String id, long health, double x, double y) {
            this.id = id;
            this.health = health;
            this.x = x;
            this.y = y;
        }

        long health() {
            return health;
        }
    }
}
