package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

    @Timeout(10)
    @Test
    void parallelDynamicSourcesStayOnCaptureThread() {
        RuntimeLimits limits = new RuntimeLimits(240, 2_000, 64, 128, 256, 256, 64,
                4_096, 256, 16, 1_000);
        AgentRuntime runtime = runtime(limits);
        List<Thread> mapperThreads = Collections.synchronizedList(new ArrayList<>());
        runtime.entities().registerSource("parallel", () -> IntStream.range(0, 64).parallel()
                .mapToObj(index -> {
                    mapperThreads.add(Thread.currentThread());
                    return InspectableEntity.of(EntityId.of("parallel-" + index),
                            EntityType.of("enemy"), () -> "Enemy " + index,
                            inspector -> inspector.property("index", () -> (long) index));
                }));
        runtime.start();
        mapperThreads.clear();

        Thread captureThread = Thread.currentThread();
        runtime.frame(1, () -> {});

        assertEquals(64, mapperThreads.size());
        assertTrue(mapperThreads.stream().allMatch(thread -> thread == captureThread),
                "every parallel stream callback must run on the capture thread");
    }

    @Timeout(10)
    @Test
    void infiniteDynamicSourcesStopAtLimitSentinel() {
        RuntimeLimits limits = new RuntimeLimits(240, 2_000, 3, 128, 256, 256, 64,
                4_096, 256, 16, 1_000);
        AgentRuntime runtime = runtime(limits);
        AtomicInteger invocations = new AtomicInteger();
        runtime.entities().registerSource("infinite", () -> Stream.generate(() -> {
            int index = invocations.incrementAndGet();
            return InspectableEntity.of(EntityId.of("gen-" + index), EntityType.of("enemy"),
                    () -> "Entity " + index,
                    inspector -> inspector.property("index", () -> (long) index));
        }));
        runtime.start();
        invocations.set(0);

        runtime.frame(1, () -> {});

        assertTrue(invocations.get() <= 4,
                "stream must stop after the configured limit plus one sentinel");
        FrameSnapshot frame = runtime.latestFrame().orElseThrow();
        assertEquals(List.of("gen-1", "gen-2", "gen-3"),
                frame.entities().stream().map(entity -> entity.id().value()).toList());
        Truncation truncation = frame.stats().truncations().stream()
                .filter(value -> value.dimension().equals("snapshot.entities"))
                .findFirst().orElseThrow();
        assertEquals(4, truncation.observed());
        assertEquals(3, truncation.retained());
        assertEquals(3, truncation.limit());
    }

    @Timeout(10)
    @Test
    void dynamicSourceSentinelNeverDisplacesBoundedPrefix() {
        RuntimeLimits limits = new RuntimeLimits(240, 2_000, 3, 128, 256, 256, 64,
                4_096, 256, 16, 1_000);
        AgentRuntime runtime = runtime(limits);
        AtomicInteger laterSourcePulls = new AtomicInteger();
        runtime.entities().registerSource("first", () -> Stream.of(
                entity("z1", 1), entity("z2", 2), entity("z3", 3), entity("a", 4)));
        runtime.entities().registerSource("second", () -> Stream.generate(() -> {
            laterSourcePulls.incrementAndGet();
            return entity("gen-" + laterSourcePulls.get(), 9);
        }));
        runtime.start();

        runtime.frame(1, () -> {});

        FrameSnapshot frame = runtime.latestFrame().orElseThrow();
        assertEquals(List.of("z1", "z2", "z3"),
                frame.entities().stream().map(entry -> entry.id().value()).toList(),
                "the sentinel observation must not displace the bounded prefix");
        assertEquals(0, laterSourcePulls.get(), "the sentinel must stop pulling globally");
        Truncation truncation = frame.stats().truncations().stream()
                .filter(value -> value.dimension().equals("snapshot.entities"))
                .findFirst().orElseThrow();
        assertEquals(4, truncation.observed());
        assertEquals(3, truncation.retained());
        assertEquals(3, truncation.limit());
        assertTrue(frame.stats().diagnostics().isEmpty());
    }

    @Timeout(10)
    @Test
    void dynamicSourceSentinelCannotCreateDuplicateOrPropertyDiagnostics() {
        RuntimeLimits limits = new RuntimeLimits(240, 2_000, 3, 128, 256, 256, 64,
                4_096, 256, 16, 1_000);

        AgentRuntime duplicateRuntime = runtime(limits);
        duplicateRuntime.entities().registerSource("duplicates", () -> Stream.of(
                entity("z1", 1), entity("z2", 2), entity("z3", 3), entity("z1", 1)));
        duplicateRuntime.start();
        duplicateRuntime.frame(1, () -> {});
        FrameSnapshot duplicateFrame = duplicateRuntime.latestFrame().orElseThrow();
        assertEquals(List.of("z1", "z2", "z3"),
                duplicateFrame.entities().stream().map(entry -> entry.id().value()).toList());
        assertTrue(duplicateFrame.stats().diagnostics().isEmpty(),
                "a duplicate sentinel must not emit a duplicate-entity diagnostic");

        AgentRuntime invalidRuntime = runtime(limits);
        invalidRuntime.entities().registerSource("invalid", () -> Stream.of(
                entity("y1", 1), entity("y2", 2), entity("y3", 3),
                InspectableEntity.of(EntityId.of("boom"), EntityType.of("enemy"),
                        () -> {
                            throw new IllegalStateException(
                                    "sentinel display must not be read");
                        },
                        inspector -> inspector.property("index", () -> 1L))));
        invalidRuntime.start();
        invalidRuntime.frame(1, () -> {});
        FrameSnapshot invalidFrame = invalidRuntime.latestFrame().orElseThrow();
        assertEquals(List.of("y1", "y2", "y3"),
                invalidFrame.entities().stream().map(entry -> entry.id().value()).toList(),
                "an invalid sentinel must not be captured or displace the bounded prefix");
        assertTrue(invalidFrame.stats().diagnostics().isEmpty(),
                "a sentinel observation must not trigger property evaluation");
        Truncation truncation = invalidFrame.stats().truncations().stream()
                .filter(value -> value.dimension().equals("snapshot.entities"))
                .findFirst().orElseThrow();
        assertEquals(4, truncation.observed());
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
        assertFalse(diagnostics.getFirst().failure().legacyEnvelope().contains("\n"));
    }

    @Test
    void providerFailureDiagnosticsOmitRawMessagesAndExposeStableCategoryAndCorrelation() {
        AgentRuntime runtime = runtime(RuntimeLimits.developmentDefaults());
        runtime.entities().register(EntityId.of("bad"), EntityType.of("enemy"),
                () -> "Bad", inspector -> inspector.property("health",
                        (java.util.function.Supplier<RuntimeValue>) () -> {
                    throw new IllegalStateException("token=secret-123 /home/private/save.dat");
                }));
        runtime.start();

        List<CaptureDiagnostic> diagnostics =
                runtime.latestFrame().orElseThrow().stats().diagnostics();
        assertEquals(1, diagnostics.size());
        ApplicationFailureEvidence failure = diagnostics.getFirst().failure();
        assertEquals("provider.property", failure.category());
        assertEquals("java.lang.IllegalStateException", failure.exceptionClass());
        String envelope = failure.legacyEnvelope();
        assertEquals(runtime.sessionId().value() + "|failure-1|provider.property"
                + "|java.lang.IllegalStateException", envelope);
        assertFalse(envelope.contains("token=secret-123"));
        assertFalse(envelope.contains("/home/private/save.dat"));
        assertTrue(failure.sanitizedDetail().isEmpty());
    }

    @Test
    void sanitizerDetailAppearsOnlyInStructuredEvidence() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("sanitized"))
                .configuration(RuntimeConfiguration.developmentDefaults())
                .clock(new AtomicLong()::incrementAndGet)
                .wallClock(Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC))
                .applicationFailureSanitizer((context, failure) ->
                        Optional.of("safe-detail"))
                .build();
        runtime.entities().register(EntityId.of("bad"), EntityType.of("enemy"),
                () -> "Bad", inspector -> inspector.property("health",
                        (java.util.function.Supplier<RuntimeValue>) () -> {
                    throw new IllegalStateException("token=secret-123 /home/private/save.dat");
                }));
        runtime.start();

        ApplicationFailureEvidence failure = runtime.latestFrame().orElseThrow().stats()
                .diagnostics().getFirst().failure();
        assertEquals(Optional.of("safe-detail"), failure.sanitizedDetail());
        String envelope = failure.legacyEnvelope();
        assertEquals(runtime.sessionId().value() + "|failure-1|provider.property"
                + "|java.lang.IllegalStateException", envelope);
        assertFalse(envelope.contains("safe-detail"));
        assertFalse(envelope.contains("token=secret-123"));
        assertFalse(envelope.contains("/home/private/save.dat"));
    }

    @Test
    void sanitizerDetailOverLimitIsTruncatedAtOneThousandTwentyFour() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("sanitized-truncated"))
                .configuration(RuntimeConfiguration.developmentDefaults())
                .clock(new AtomicLong()::incrementAndGet)
                .wallClock(Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC))
                .applicationFailureSanitizer((context, failure) ->
                        Optional.of("x".repeat(2_000)))
                .build();
        runtime.entities().register(EntityId.of("bad"), EntityType.of("enemy"),
                () -> "Bad", inspector -> inspector.property("health",
                        (java.util.function.Supplier<RuntimeValue>) () -> {
                    throw new IllegalStateException("token=secret-123");
                }));
        runtime.start();

        ApplicationFailureEvidence failure = runtime.latestFrame().orElseThrow().stats()
                .diagnostics().getFirst().failure();
        String detail = failure.sanitizedDetail().orElseThrow();
        assertEquals(ApplicationFailureEvidence.MAX_SANITIZED_DETAIL_LENGTH, detail.length());
        assertTrue(detail.chars().allMatch(value -> value == 'x'));
        assertFalse(failure.legacyEnvelope().contains("x".repeat(8)));
        assertFalse(failure.legacyEnvelope().contains("token=secret-123"));
    }

    @Test
    void captureDiagnosticLegacyEnvelopeFitsConfiguredStringLength() {
        RuntimeLimits limits = new RuntimeLimits(240, 2_000, 5_000, 128, 256, 256, 64,
                642, 256, 16, 1_000);
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("capture-bound"))
                .configuration(new RuntimeConfiguration(true, limits))
                .clock(new AtomicLong()::incrementAndGet)
                .wallClock(Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC))
                .build();
        runtime.entities().register(EntityId.of("bad"), EntityType.of("enemy"),
                () -> "Bad", inspector -> inspector.property("health",
                        (java.util.function.Supplier<RuntimeValue>) () -> {
                    throw new IllegalStateException("token=secret-123 /home/private/save.dat");
                }));
        runtime.start();

        String envelope = runtime.latestFrame().orElseThrow().stats().diagnostics()
                .getFirst().failure().legacyEnvelope();
        assertTrue(envelope.length() <= limits.stringLength());
        assertTrue(envelope.contains(runtime.sessionId().value() + "|failure-1"));
        assertFalse(envelope.contains("token=secret-123"));
        assertFalse(envelope.contains("/home/private/save.dat"));
    }

    @Test
    void throwingSanitizerFailsClosedWithoutExposingAnyFailureMessage() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("sanitizer-failure"))
                .configuration(RuntimeConfiguration.developmentDefaults())
                .clock(new AtomicLong()::incrementAndGet)
                .wallClock(Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC))
                .applicationFailureSanitizer((context, failure) -> {
                    throw new IllegalStateException("sanitizer token=secret-456");
                })
                .build();
        runtime.entities().register(EntityId.of("bad"), EntityType.of("enemy"),
                () -> "Bad", inspector -> inspector.property("health",
                        (java.util.function.Supplier<RuntimeValue>) () -> {
                    throw new IllegalStateException("token=secret-123 /home/private/save.dat");
                }));
        runtime.start();

        ApplicationFailureEvidence failure = runtime.latestFrame().orElseThrow().stats()
                .diagnostics().getFirst().failure();
        assertTrue(failure.sanitizedDetail().isEmpty());
        String envelope = failure.legacyEnvelope();
        assertFalse(envelope.contains("sanitizer token=secret-456"));
        assertFalse(envelope.contains("token=secret-123"));
        assertFalse(envelope.contains("/home/private/save.dat"));
        assertTrue(envelope.contains(runtime.sessionId().value() + "|failure-1"));
    }

    @Test
    void correlationIdsFollowDeterministicCaptureOrder() {
        AgentRuntime runtime = runtime(RuntimeLimits.developmentDefaults());
        runtime.entities().register(EntityId.of("bad"), EntityType.of("enemy"),
                () -> "Bad", inspector -> inspector
                        .property("a", (java.util.function.Supplier<RuntimeValue>) () -> {
                            throw new IllegalStateException("first failure");
                        })
                        .property("b", (java.util.function.Supplier<RuntimeValue>) () -> {
                            throw new IllegalStateException("second failure");
                        }));
        runtime.start();

        List<CaptureDiagnostic> diagnostics =
                runtime.latestFrame().orElseThrow().stats().diagnostics();
        assertEquals(2, diagnostics.size());
        assertTrue(diagnostics.getFirst().failure().correlationId()
                .equals(runtime.sessionId().value() + "|failure-1"));
        assertTrue(diagnostics.get(1).failure().correlationId()
                .equals(runtime.sessionId().value() + "|failure-2"));
    }

    @Test
    void maxLengthSessionStillInvokesConfiguredSanitizer() {
        String sessionId = "s".repeat(256);
        java.util.concurrent.atomic.AtomicBoolean invoked =
                new java.util.concurrent.atomic.AtomicBoolean();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of(sessionId))
                .configuration(RuntimeConfiguration.developmentDefaults())
                .clock(new AtomicLong()::incrementAndGet)
                .wallClock(Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC))
                .applicationFailureSanitizer((context, failure) -> {
                    invoked.set(true);
                    assertTrue(context.correlationId()
                            .startsWith(sessionId + "|failure-1"));
                    return Optional.of("safe-detail");
                })
                .build();
        runtime.entities().register(EntityId.of("bad"), EntityType.of("enemy"),
                () -> "Bad", inspector -> inspector.property("health",
                        (java.util.function.Supplier<RuntimeValue>) () -> {
                    throw new IllegalStateException("token=secret-123");
                }));
        runtime.start();

        ApplicationFailureEvidence failure = runtime.latestFrame().orElseThrow().stats()
                .diagnostics().getFirst().failure();
        assertTrue(invoked.get(), "sanitizer must run for a valid max-length session");
        assertEquals(Optional.of("safe-detail"), failure.sanitizedDetail());
        assertEquals(sessionId + "|failure-1", failure.correlationId());
    }

    @Test
    void distinctSessionsProduceDistinctCorrelationIds() {
        AgentRuntime first = runtime(RuntimeLimits.developmentDefaults());
        AgentRuntime second = runtime(RuntimeLimits.developmentDefaults());
        first.entities().register(EntityId.of("bad"), EntityType.of("enemy"),
                () -> "Bad", inspector -> inspector.property("health",
                        (java.util.function.Supplier<RuntimeValue>) () -> {
                    throw new IllegalStateException("secret-1");
                }));
        second.entities().register(EntityId.of("bad"), EntityType.of("enemy"),
                () -> "Bad", inspector -> inspector.property("health",
                        (java.util.function.Supplier<RuntimeValue>) () -> {
                    throw new IllegalStateException("secret-2");
                }));
        first.start();
        second.start();

        ApplicationFailureEvidence firstFailure = first.latestFrame().orElseThrow().stats()
                .diagnostics().getFirst().failure();
        ApplicationFailureEvidence secondFailure = second.latestFrame().orElseThrow().stats()
                .diagnostics().getFirst().failure();
        assertEquals(first.sessionId().value() + "|failure-1", firstFailure.correlationId());
        assertEquals(second.sessionId().value() + "|failure-1", secondFailure.correlationId());
        assertNotEquals(firstFailure.correlationId(), secondFailure.correlationId());
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
        RuntimeLimits limits = new RuntimeLimits(3, 10, 10, 10, 10, 10, 10, 642, 10, 5, 100);
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
    @Timeout(10)
    void lifecycleStatusIsVisibleAcrossThreads() throws Exception {
        AgentRuntime runtime = runtime(RuntimeLimits.developmentDefaults());
        awaitStatusTransition(runtime, runtime::start,
                RuntimeStatus.CREATED, RuntimeStatus.RUNNING);
        awaitStatusTransition(runtime, runtime::close,
                RuntimeStatus.RUNNING, RuntimeStatus.CLOSED);

        AgentRuntime disabled = AgentRuntime.builder()
                .configuration(RuntimeConfiguration.disabled()).build();
        awaitStatusTransition(disabled, disabled::start,
                RuntimeStatus.CREATED, RuntimeStatus.DISABLED);
        awaitStatusTransition(disabled, disabled::close,
                RuntimeStatus.DISABLED, RuntimeStatus.CLOSED);
    }

    private static void awaitStatusTransition(AgentRuntime runtime, Runnable transition,
            RuntimeStatus prior, RuntimeStatus expected) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch polling = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<RuntimeStatus> observedStatus = new AtomicReference<>();
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                go.await();
                long observationDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (runtime.status() == prior
                        && !Thread.currentThread().isInterrupted()
                        && System.nanoTime() < observationDeadlineNanos) {
                    polling.countDown();
                    Thread.onSpinWait();
                }
                observedStatus.set(runtime.status());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable failure) {
                readerFailure.set(failure);
            } finally {
                observed.countDown();
            }
        });
        try {
            go.countDown();
            assertTrue(polling.await(5, TimeUnit.SECONDS),
                    "reader must poll before the transition runs");
            transition.run();
            assertTrue(observed.await(5, TimeUnit.SECONDS),
                    "reader must observe " + expected + " (stale lifecycle status race)");
            assertEquals(expected, observedStatus.get(),
                    "reader observed stale lifecycle status " + observedStatus.get());
            assertEquals(expected, runtime.status());
        } catch (Throwable primary) {
            try {
                cleanupVisibilityReader(reader, readerFailure);
            } catch (Throwable cleanup) {
                primary.addSuppressed(cleanup);
            }
            throw primary;
        }
        cleanupVisibilityReader(reader, readerFailure);
    }

    /**
     * Non-interruptible, bounded reader cleanup: interrupts the reader, then polls
     * {@link Thread#isAlive()} against a short absolute deadline (no unbounded or interruptible
     * join, and the current thread's interrupt status is never read or cleared). Reader failures
     * and non-termination surface as {@link AssertionError}s.
     */
    private static void cleanupVisibilityReader(Thread reader,
            AtomicReference<Throwable> readerFailure) {
        reader.interrupt();
        long cleanupDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (reader.isAlive() && System.nanoTime() < cleanupDeadlineNanos) {
            Thread.onSpinWait();
        }
        Throwable failure = readerFailure.get();
        if (reader.isAlive()) {
            AssertionError alive = new AssertionError(
                    "visibility reader did not terminate within the cleanup deadline");
            if (failure != null) {
                alive.addSuppressed(failure);
            }
            throw alive;
        }
        if (failure != null) {
            throw new AssertionError("visibility reader failed", failure);
        }
    }

    @Test
    void newExecutionEpochCapturesBaselineWithoutCrossEpochDiffs() {
        long[] health = {100};
        AgentRuntime runtime = runtime(new RuntimeLimits(2, 10, 10, 10, 10, 10, 10, 642,
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
        RuntimeLimits limits = new RuntimeLimits(10, 2, 1, 1, 1, 1, 1, 642, 1, 2, 10);
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
    void openFrameFactsAreBoundedAtInsertion() {
        RuntimeLimits limits = new RuntimeLimits(10, 3, 10, 10, 3, 10, 10, 642, 10, 5, 100);
        AgentRuntime runtime = runtime(limits, new FrameStagingLimits(4));
        runtime.start();
        AtomicLong attributeAccesses = new AtomicLong();
        RuntimeValue.ObjectValue payload = new RuntimeValue.ObjectValue(
                new CountingFieldList(List.of(RuntimeValues.field("inner", RuntimeValues.integer(7))),
                        attributeAccesses));
        EventSpec event = EventSpec.type("bound.event").attribute("payload", payload);
        runtime.frame(1, () -> {
            for (int index = 0; index < 3; index++) {
                assertTrue(runtime.emit(event).isPresent());
            }
            long accessesAfterRetainedEvents = attributeAccesses.get();
            for (int index = 0; index < 10; index++) {
                assertTrue(runtime.emit(event).isPresent());
            }
            assertEquals(3, runtime.stagedEventCount());
            assertEquals(accessesAfterRetainedEvents, attributeAccesses.get(),
                    "dropped events must not read or copy attribute values");

            for (int index = 0; index < 3; index++) {
                try (DecisionScope decision = runtime.beginDecision(
                        DecisionType.of("bound"), EntityId.of("actor"))) {
                    assertTrue(decision.id().isPresent());
                    decision.reject(EntityId.of("candidate-" + index), Reason.of("reason"),
                            RuntimeValues.field("payload", payload));
                }
            }
            long accessesAfterRetainedDecisions = attributeAccesses.get();
            for (int index = 0; index < 10; index++) {
                try (DecisionScope decision = runtime.beginDecision(
                        DecisionType.of("bound"), EntityId.of("actor"))) {
                    assertTrue(decision.id().isEmpty());
                    decision.reject(EntityId.of("candidate-" + index), Reason.of("reason"),
                            RuntimeValues.field("payload", payload));
                }
            }
            assertEquals(3, runtime.stagedDecisionCount());
            assertEquals(accessesAfterRetainedDecisions, attributeAccesses.get(),
                    "overflow decisions must not copy attribute values");

            for (int index = 0; index < 14; index++) {
                runtime.causeNextChange(EntityId.of("enemy"), "p" + index,
                        ChangeCause.semantic("cause-" + index));
            }
            assertEquals(4, runtime.stagedCauseCount());
        });

        FrameSnapshot frame = runtime.latestFrame().orElseThrow();
        assertEquals(3, frame.events().size());
        assertEquals(3, frame.decisions().size());
        assertTruncation(truncation(frame, "frame.events"), 13, 3, 3);
        assertTruncation(truncation(frame, "frame.decisions"), 13, 3, 3);
        assertTruncation(truncation(frame, "frame.causes"), 14, 4, 4);
    }

    @Test
    void droppedEventIdsStayMonotonicAndStagingCountersResetPerFrame() {
        RuntimeLimits limits = new RuntimeLimits(10, 2, 10, 10, 2, 10, 10, 642, 10, 5, 100);
        AgentRuntime runtime = runtime(limits, new FrameStagingLimits(2));
        runtime.start();
        List<Long> eventIds = new ArrayList<>();
        runtime.frame(1, () -> {
            for (int index = 0; index < 12; index++) {
                eventIds.add(runtime.emit(EventSpec.type("burst")
                        .attribute("value", RuntimeValues.integer(index))).orElseThrow().value());
            }
        });
        FrameSnapshot burst = runtime.latestFrame().orElseThrow();
        assertTruncation(truncation(burst, "frame.events"), 12, 2, 2);

        runtime.frame(2, () -> {
            eventIds.add(runtime.emit(EventSpec.type("single")).orElseThrow().value());
            try (DecisionScope decision = runtime.beginDecision(
                    DecisionType.of("single"), EntityId.of("actor"))) {
                assertTrue(decision.id().isPresent());
                decision.reject(EntityId.of("candidate"), Reason.of("reason"));
            }
            runtime.causeNextChange(EntityId.of("enemy"), "p", ChangeCause.semantic("one"));
        });
        FrameSnapshot single = runtime.latestFrame().orElseThrow();
        assertTrue(single.stats().truncations().stream()
                        .noneMatch(value -> value.dimension().startsWith("frame.")),
                "per-frame staging counters must reset after the previous frame");
        assertEquals(1, single.events().size());
        assertEquals(1, single.decisions().size());

        List<Long> sorted = eventIds.stream().sorted().toList();
        assertEquals(sorted, eventIds, "event IDs must stay monotonic even when dropped");
        assertEquals(eventIds.size(), eventIds.stream().distinct().count());
    }

    @Test
    void overflowDecisionScopeIsDisabledWhileRetainedOpenDecisionPreventsNesting() {
        RuntimeLimits limits = new RuntimeLimits(10, 10, 10, 10, 2, 10, 10, 642, 10, 5, 100);
        AgentRuntime runtime = runtime(limits, new FrameStagingLimits(4));
        runtime.start();
        runtime.frame(1, () -> {
            DecisionScope first = runtime.beginDecision(
                    DecisionType.of("first"), EntityId.of("actor"));
            assertTrue(first.id().isPresent());
            assertThrows(AgentRuntimeException.class,
                    () -> runtime.beginDecision(DecisionType.of("nested"), EntityId.of("actor")));
            first.close();

            DecisionScope second = runtime.beginDecision(
                    DecisionType.of("second"), EntityId.of("actor"));
            assertTrue(second.id().isPresent());
            assertThrows(AgentRuntimeException.class,
                    () -> runtime.beginDecision(DecisionType.of("nested"), EntityId.of("actor")));
            second.close();

            DecisionScope overflow = runtime.beginDecision(
                    DecisionType.of("overflow"), EntityId.of("actor"));
            assertTrue(overflow.id().isEmpty());
            overflow.accept(EntityId.of("candidate"), Reason.of("eligible"));
            overflow.choose(EntityId.of("candidate"), Reason.of("chosen"));
            overflow.close();
            assertEquals(2, runtime.stagedDecisionCount());
        });

        FrameSnapshot frame = runtime.latestFrame().orElseThrow();
        assertEquals(2, frame.decisions().size());
        assertTruncation(truncation(frame, "frame.decisions"), 3, 2, 2);
        assertTrue(frame.decisions().stream().allMatch(trace -> trace.candidates().isEmpty()),
                "the overflow decision scope must retain no candidates");
    }

    @Test
    void droppedCausesSkipRetentionAndKeepValidatingKeys() {
        RuntimeLimits limits = new RuntimeLimits(10, 10, 10, 10, 10, 10, 10, 642, 10, 5, 100);
        AgentRuntime runtime = runtime(limits, new FrameStagingLimits(2));
        long[] values = new long[6];
        runtime.entities().register(EntityId.of("enemy"), EntityType.of("enemy"),
                () -> "Enemy",
                inspector -> IntStream.range(0, 6).forEach(index ->
                        inspector.property("p" + index, () -> values[index])));
        runtime.start();
        runtime.frame(1, () -> {
            runtime.causeNextChange(EntityId.of("enemy"), "p0", ChangeCause.semantic("kept-0"));
            runtime.causeNextChange(EntityId.of("enemy"), "p1", ChangeCause.semantic("kept-1"));
            assertThrows(NullPointerException.class,
                    () -> runtime.causeNextChange(null, "p2", ChangeCause.semantic("null-entity")));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.causeNextChange(EntityId.of("enemy"), " ",
                            ChangeCause.semantic("blank-property")));
            for (int index = 2; index < 6; index++) {
                runtime.causeNextChange(EntityId.of("enemy"), "p" + index,
                        ChangeCause.semantic("dropped-" + index));
            }
            assertEquals(2, runtime.stagedCauseCount());
            for (int index = 0; index < 6; index++) {
                values[index] = 1;
            }
        });

        FrameSnapshot frame = runtime.latestFrame().orElseThrow();
        assertTruncation(truncation(frame, "frame.causes"), 6, 2, 2);
        Map<String, PropertyChange> changes = frame.changes().stream()
                .filter(change -> change.property().isPresent())
                .collect(Collectors.toMap(change -> change.property().orElseThrow(),
                        change -> change));
        assertEquals("kept-0", changes.get("p0").cause().semanticCode().orElseThrow());
        assertEquals("kept-1", changes.get("p1").cause().semanticCode().orElseThrow());
        assertEquals(ChangeCause.Kind.UNKNOWN, changes.get("p2").cause().kind());
        assertEquals(ChangeCause.Kind.UNKNOWN, changes.get("p5").cause().kind());
    }

    @Test
    void frameStagingLimitsRejectNonPositiveCausesPerFrame() {
        assertThrows(IllegalArgumentException.class, () -> new FrameStagingLimits(0));
        assertThrows(IllegalArgumentException.class, () -> new FrameStagingLimits(-1));
        assertTrue(FrameStagingLimits.developmentDefaults().causesPerFrame() > 0);
    }

    @Test
    void frameStagingLimitsPropagateFromBuilderAndConfiguration() {
        FrameStagingLimits staging = new FrameStagingLimits(7);
        AgentRuntime fromBuilder = AgentRuntime.builder().frameStagingLimits(staging).build();
        assertEquals(staging, fromBuilder.frameStagingLimits());
        assertEquals(staging, fromBuilder.configuration().frameStagingLimits());

        AgentRuntime fromConfig = AgentRuntime.builder()
                .configuration(new RuntimeConfiguration(true,
                        RuntimeLimits.developmentDefaults(), new FrameStagingLimits(9)))
                .build();
        assertEquals(9, fromConfig.frameStagingLimits().causesPerFrame());

        assertEquals(FrameStagingLimits.developmentDefaults(),
                new RuntimeConfiguration(true, RuntimeLimits.developmentDefaults())
                        .frameStagingLimits());
        assertEquals(FrameStagingLimits.developmentDefaults(),
                RuntimeConfiguration.developmentDefaults().frameStagingLimits());
        assertEquals(FrameStagingLimits.developmentDefaults(),
                RuntimeConfiguration.disabled().frameStagingLimits());
    }

    @Test
    void manyFramesAndEventsRemainWithinConfiguredRetention() {
        RuntimeLimits limits =
                new RuntimeLimits(20, 50, 10, 10, 10, 10, 10, 642, 10, 5, 100);
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
                new RuntimeLimits(10, 10, 10, 10, 10, 1, 10, 642, 10, 5, 100);
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

    @Test
    void closeReleasesCallbacksAndPendingWorkWhileRetainingCatalogsAndEvidence() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicInteger resets = new AtomicInteger();
        AtomicInteger actionRuns = new AtomicInteger();
        AtomicInteger inputRuns = new AtomicInteger();
        AtomicInteger tickRuns = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("close-release"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.scenarios().register("reset", "Restores state", context -> resets.incrementAndGet());
        runtime.actions().register(ActionSpec.builder("attack")
                .description("Attacks one target")
                .handler(ignored -> actionRuns.incrementAndGet()).build());
        runtime.inputs().register(InputSpec.builder("key")
                .description("Pressed key")
                .requiredString("key")
                .handler(ignored -> inputRuns.incrementAndGet()).build());
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).tick(delta -> tickRuns.incrementAndGet())
                .condition("ready", "Ready state", () -> false).build());
        runtime.start();
        runtime.frame(1, () -> {});

        runtime.controls().control(true, "pause-1", Duration.ofSeconds(1));
        queue.removeFirst().run();
        RuntimeValue.ObjectValue parameters = RuntimeValues.object(
                RuntimeValues.field("key", RuntimeValues.string("X")));
        runtime.inputs().inject("key", "input-1", parameters, OptionalLong.empty(),
                Duration.ofSeconds(1));
        queue.removeFirst().run();
        assertEquals(1, runtime.inputs().retainedPendingInjections());
        runtime.scenarios().reset("reset", "reset-1", Duration.ofSeconds(1));
        runtime.actions().invoke("attack", "action-1", RuntimeValues.object(),
                Optional.empty(), Duration.ofSeconds(1));
        assertEquals(1, runtime.controls().retainedControlOperations());

        runtime.close();

        assertEquals(RuntimeStatus.CLOSED, runtime.status());
        // Immutable catalogs remain queryable after close.
        assertEquals(List.of("reset"),
                runtime.scenarios().list().stream().map(ScenarioDescriptor::id).toList());
        assertTrue(runtime.scenarios().determinismAvailable());
        assertEquals(List.of("attack"),
                runtime.actions().list().stream().map(ActionDescriptor::id).toList());
        assertEquals(List.of("key"),
                runtime.inputs().list().stream().map(InputDescriptor::id).toList());
        assertEquals(List.of("ready"),
                runtime.controls().conditions().stream().map(ControlConditionDescriptor::id).toList());
        assertFalse(runtime.controls().descriptor().available());
        // Completed immutable history remains queryable.
        assertTrue(runtime.latestFrame().isPresent());
        // Every application callback and pending-work store is empty.
        assertEquals(0, runtime.scenarios().retainedResetCallbacks());
        assertEquals(0, runtime.scenarios().retainedPendingResets());
        assertEquals(0, runtime.actions().retainedActionHandlers());
        assertEquals(0, runtime.actions().retainedPendingInvocations());
        assertEquals(0, runtime.inputs().retainedInputHandlers());
        assertEquals(0, runtime.inputs().retainedPendingInjections());
        assertEquals(0, runtime.controls().retainedControlCallbacks());
        assertEquals(0, runtime.controls().retainedControlOperations());
        // Queued application work cannot execute after close.
        queue.forEach(Runnable::run);
        assertEquals(0, resets.get());
        assertEquals(0, actionRuns.get());
        assertEquals(0, inputRuns.get());
        assertEquals(0, tickRuns.get());
        // Terminal command evidence remains queryable.
        assertEquals(CommandState.REJECTED, runtime.commands().orElseThrow()
                .status("reset-1").status().orElseThrow().state());
        // New submissions reject the closed runtime.
        assertClosedSubmission(() -> runtime.scenarios().reset(
                "reset", "reset-2", Duration.ofSeconds(1)));
        assertClosedSubmission(() -> runtime.actions().invoke("attack", "action-2",
                RuntimeValues.object(), Optional.empty(), Duration.ofSeconds(1)));
        assertClosedSubmission(() -> runtime.inputs().inject("key", "input-2", parameters,
                OptionalLong.empty(), Duration.ofSeconds(1)));
        assertClosedSubmission(() -> runtime.controls().control(true, "pause-2",
                Duration.ofSeconds(1)));
        assertClosedSubmission(() -> runtime.controls().advance(
                "advance-2", 1, 1, Duration.ofSeconds(1)));
    }

    @Test
    void closeAttemptsEveryHookSuppressesLaterFailuresAndStaysIdempotent() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        List<Integer> disposed = new ArrayList<>();
        AtomicInteger created = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("close-suppression"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .checkpointLimits(new CheckpointLimits(2, 8, 642))
                .build();
        runtime.scenarios().register("reset", "Reset", context -> {});
        runtime.actions().register(ActionSpec.builder("attack").handler(ignored -> {}).build());
        runtime.inputs().register(InputSpec.builder("key").handler(ignored -> {}).build());
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).tick(delta -> {}).build());
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override
            public CheckpointHandle create() {
                return new CloseHandle(created.incrementAndGet());
            }

            @Override
            public void restore(CheckpointHandle handle) {}

            @Override
            public void dispose(CheckpointHandle handle) {
                int value = ((CloseHandle) handle).value();
                disposed.add(value);
                if (value == 1) {
                    throw new IllegalStateException("dispose failed");
                }
            }
        });
        runtime.start();
        runtime.checkpoints().create("one", null, "create-one", Duration.ofSeconds(1));
        queue.removeFirst().run();
        runtime.checkpoints().create("two", null, "create-two", Duration.ofSeconds(1));
        queue.removeFirst().run();

        IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::close);
        assertEquals("dispose failed", failure.getMessage());
        assertEquals(List.of(1, 2), disposed,
                "the second checkpoint must still be disposed after the first failure");
        assertEquals(RuntimeStatus.CLOSED, runtime.status());
        assertEquals(0, runtime.scenarios().retainedResetCallbacks());
        assertEquals(0, runtime.actions().retainedActionHandlers());
        assertEquals(0, runtime.inputs().retainedInputHandlers());
        assertEquals(0, runtime.controls().retainedControlCallbacks());
        assertEquals(0, runtime.checkpoints().retainedCheckpoints());
        assertEquals(0, runtime.checkpoints().retainedOperations());
        // A second close is a no-op.
        runtime.close();
        assertEquals(RuntimeStatus.CLOSED, runtime.status());
    }

    @Test
    void closeAttachesLaterHookFailuresAsSuppressedAndStillPublishesClosed() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicLong clock = new AtomicLong(1);
        List<Integer> disposed = new ArrayList<>();
        AtomicInteger created = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("close-suppressed-chain"))
                .clock(clock::get)
                .commandDispatcher(queue::addLast)
                .checkpointLimits(new CheckpointLimits(2, 8, 642))
                .build();
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override
            public CheckpointHandle create() {
                return new CloseHandle(created.incrementAndGet());
            }

            @Override
            public void restore(CheckpointHandle handle) {}

            @Override
            public void dispose(CheckpointHandle handle) {
                int value = ((CloseHandle) handle).value();
                disposed.add(value);
                if (value == 1) {
                    throw new IllegalStateException("dispose failed");
                }
                throw new IllegalStateException("second dispose failed");
            }
        });
        runtime.start();
        runtime.checkpoints().create("one", null, "create-one", Duration.ofSeconds(1));
        queue.removeFirst().run();
        runtime.checkpoints().create("two", null, "create-two", Duration.ofSeconds(1));
        queue.removeFirst().run();
        clock.set(-1);

        IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::close);
        assertEquals("monotonic clock returned a negative value", failure.getMessage());
        Throwable[] suppressed = failure.getSuppressed();
        assertEquals(1, suppressed.length, "the later command hook failure must be suppressed");
        assertEquals("dispose failed", suppressed[0].getMessage(),
                "the checkpoint disposal failure must follow the command hook failure");
        assertEquals(RuntimeStatus.CLOSED, runtime.status());
        assertEquals(List.of(1, 2), disposed,
                "both retained handles must be disposed before the failure propagates");
        assertEquals(0, runtime.checkpoints().retainedCheckpoints());
        assertEquals(0, runtime.checkpoints().retainedOperations());
        assertEquals(0, runtime.inputs().retainedInputHandlers());
        assertEquals(0, runtime.controls().retainedControlCallbacks());
        // A second close is a no-op.
        runtime.close();
        assertEquals(RuntimeStatus.CLOSED, runtime.status());
    }

    @Test
    void closeIgnoresIdenticalRepeatedFailuresWithoutSelfSuppression() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        List<Integer> disposed = new ArrayList<>();
        AtomicInteger created = new AtomicInteger();
        IllegalStateException shared = new IllegalStateException("shared dispose failure");
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("close-shared-failure"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .checkpointLimits(new CheckpointLimits(2, 8, 642))
                .build();
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override
            public CheckpointHandle create() {
                return new CloseHandle(created.incrementAndGet());
            }

            @Override
            public void restore(CheckpointHandle handle) {}

            @Override
            public void dispose(CheckpointHandle handle) {
                disposed.add(((CloseHandle) handle).value());
                throw shared;
            }
        });
        runtime.start();
        runtime.checkpoints().create("one", null, "create-one", Duration.ofSeconds(1));
        queue.removeFirst().run();
        runtime.checkpoints().create("two", null, "create-two", Duration.ofSeconds(1));
        queue.removeFirst().run();

        IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::close);
        assertSame(shared, failure,
                "the first identical instance must be rethrown unchanged");
        assertEquals(0, failure.getSuppressed().length,
                "a throwable must never suppress itself");
        assertEquals(List.of(1, 2), disposed,
                "cleanup must continue after the identical repeated failure");
        assertEquals(RuntimeStatus.CLOSED, runtime.status());
        assertEquals(0, runtime.checkpoints().retainedCheckpoints());
        assertEquals(0, runtime.checkpoints().retainedOperations());
        assertEquals(0, runtime.inputs().retainedInputHandlers());
        assertEquals(0, runtime.controls().retainedControlCallbacks());
        // A second close is a no-op.
        runtime.close();
        assertEquals(RuntimeStatus.CLOSED, runtime.status());
    }

    @Test
    void repeatedCreateRegisterStartCloseRetainsNoCallbacks() {
        for (int iteration = 0; iteration < 3; iteration++) {
            ArrayDeque<Runnable> queue = new ArrayDeque<>();
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("loop-" + iteration))
                    .clock(() -> 1)
                    .commandDispatcher(queue::addLast)
                    .build();
            runtime.scenarios().register("reset", "Reset", context -> {});
            runtime.actions().register(ActionSpec.builder("attack").handler(ignored -> {}).build());
            runtime.inputs().register(InputSpec.builder("key").handler(ignored -> {}).build());
            runtime.controls().register(SimulationControllerSpec.builder()
                    .pause(() -> {}).resume(() -> {}).tick(delta -> {}).build());
            runtime.start();
            runtime.close();
            assertEquals(0, runtime.scenarios().retainedResetCallbacks());
            assertEquals(0, runtime.actions().retainedActionHandlers());
            assertEquals(0, runtime.inputs().retainedInputHandlers());
            assertEquals(0, runtime.controls().retainedControlCallbacks());
            assertEquals(0, runtime.commands().orElseThrow().retainedLiveCommands());
            assertEquals(0, runtime.commands().orElseThrow().retainedPendingDispatches());
        }
    }

    @Test
    void closeSerializesWithInFlightInjectAndRetainsNoEvidence() throws Exception {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch dispatchEntered = new CountDownLatch(1);
        CountDownLatch releaseDispatch = new CountDownLatch(1);
        CountDownLatch releaseSignal = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-close-race"))
                .clock(() -> 1)
                .commandDispatcher(task -> {
                    queue.addLast(task);
                    if (armed.get()) {
                        dispatchEntered.countDown();
                        try {
                            if (!releaseDispatch.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("dispatch latch timed out");
                            }
                        } catch (InterruptedException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                })
                .build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).tick(delta -> {}).build());
        runtime.inputs().register(InputSpec.builder("keyboard")
                .requiredString("key")
                .handler(ignored -> executions.incrementAndGet()).build());
        runtime.start();
        runtime.controls().control(true, "pause-1", Duration.ofSeconds(1));
        queue.removeFirst().run();

        Thread releaser = new Thread(() -> {
            try {
                if (!releaseSignal.await(5, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException failure) {
                throw new IllegalStateException(failure);
            }
            releaseDispatch.countDown();
        }, "inject-releaser");
        releaser.start();
        armed.set(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            RuntimeValue.ObjectValue parameters = RuntimeValues.object(
                    RuntimeValues.field("key", RuntimeValues.string("X")));
            var inject = executor.submit(() -> runtime.inputs().inject(
                    "keyboard", "race-input", parameters, OptionalLong.empty(),
                    Duration.ofSeconds(1)));
            assertTrue(dispatchEntered.await(5, TimeUnit.SECONDS),
                    "inject must be in flight holding the submission lock before close");
            releaseSignal.countDown();
            runtime.close();
            inject.get(5, TimeUnit.SECONDS);
        }
        releaser.join(5_000);

        assertEquals(RuntimeStatus.CLOSED, runtime.status());
        assertEquals(0, runtime.inputs().retainedInputHandlers());
        assertEquals(0, runtime.inputs().retainedPendingInjections());
        assertEquals(0, executions.get());
        assertEquals(CommandState.REJECTED, runtime.commands().orElseThrow()
                .status("race-input").status().orElseThrow().state());
        queue.forEach(Runnable::run);
        assertEquals(0, executions.get());
    }

    @Test
    @Timeout(15)
    void closeBarrierRejectsSubmissionTargetingRegistryAndNeverRepopulates() throws Exception {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch dispatchEntered = new CountDownLatch(1);
        CountDownLatch releaseDispatch = new CountDownLatch(1);
        CountDownLatch disposeEntered = new CountDownLatch(1);
        CountDownLatch releaseDispose = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        // One absolute body deadline shared by every await, poll sleep, task get, and
        // dispatcher/checkpoint/releaser wait; each wait consumes only the time remaining
        // on the deadline, so runtime.close() can never block beyond the body budget.
        long bodyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-close-barrier"))
                .clock(() -> 1)
                .commandDispatcher(task -> {
                    queue.addLast(task);
                    if (armed.get()) {
                        dispatchEntered.countDown();
                        try {
                            long remaining = remainingNanos(bodyDeadline);
                            if (remaining <= 0
                                    || !releaseDispatch.await(remaining, TimeUnit.NANOSECONDS)) {
                                throw new IllegalStateException("dispatch latch timed out");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        }
                    }
                })
                .checkpointLimits(new CheckpointLimits(1, 8, 642))
                .build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).tick(delta -> {}).build());
        runtime.inputs().register(InputSpec.builder("keyboard")
                .requiredString("key")
                .handler(ignored -> executions.incrementAndGet()).build());
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override
            public CheckpointHandle create() {
                return new CloseHandle(1);
            }

            @Override
            public void restore(CheckpointHandle handle) {}

            @Override
            public void dispose(CheckpointHandle handle) {
                disposeEntered.countDown();
                try {
                    long remaining = remainingNanos(bodyDeadline);
                    if (remaining <= 0
                            || !releaseDispose.await(remaining, TimeUnit.NANOSECONDS)) {
                        throw new IllegalStateException("dispose latch timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }
        });
        runtime.start();
        runtime.controls().control(true, "pause-1", Duration.ofSeconds(1));
        queue.removeFirst().run();
        runtime.checkpoints().create("one", null, "create-one", Duration.ofSeconds(1));
        queue.removeFirst().run();
        armed.set(true);
        RuntimeValue.ObjectValue parameters = RuntimeValues.object(
                RuntimeValues.field("key", RuntimeValues.string("X")));

        FutureTask<InputInjection> admittedTask = new FutureTask<>(() -> runtime.inputs().inject(
                "keyboard", "race-a", parameters, OptionalLong.empty(),
                Duration.ofSeconds(1)));
        FutureTask<InputInjection> queuedTask = new FutureTask<>(() -> runtime.inputs().inject(
                "keyboard", "race-b", parameters, OptionalLong.empty(),
                Duration.ofSeconds(1)));
        Thread releaser = null;
        Thread admittedThread = null;
        Thread queuedThread = null;
        Throwable primaryFailure = null;
        try {
            releaser = new Thread(() -> {
                try {
                    long remaining = remainingNanos(bodyDeadline);
                    if (remaining <= 0
                            || !disposeEntered.await(remaining, TimeUnit.NANOSECONDS)) {
                        return;
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    return;
                }
                releaseDispatch.countDown();
                releaseDispose.countDown();
            }, "input-close-releaser");
            admittedThread = Thread.ofVirtual().name("race-a").unstarted(admittedTask);
            queuedThread = Thread.ofVirtual().name("race-b").unstarted(queuedTask);
            releaser.start();
            admittedThread.start();
            assertTrue(awaitLatch(dispatchEntered, bodyDeadline),
                    "admitted inject must hold the submission lock before close");
            queuedThread.start();
            boolean blockedOnSubmissionLock = false;
            while (remainingNanos(bodyDeadline) > 0) {
                if (queuedThread.getState() == Thread.State.BLOCKED
                        && Arrays.stream(queuedThread.getStackTrace()).anyMatch(frame ->
                                frame.getClassName().equals(InputRegistry.class.getName())
                                        && frame.getMethodName().equals("inject"))) {
                    blockedOnSubmissionLock = true;
                    break;
                }
                long sleepNanos = Math.min(remainingNanos(bodyDeadline),
                        TimeUnit.MILLISECONDS.toNanos(10));
                if (sleepNanos > 0) {
                    Thread.sleep(Math.max(1, TimeUnit.NANOSECONDS.toMillis(sleepNanos)));
                }
            }
            assertTrue(blockedOnSubmissionLock,
                    "race-b must be provably BLOCKED on InputRegistry.submissionLock before close");

            runtime.close();
            getBefore(bodyDeadline, admittedTask);
            try {
                getBefore(bodyDeadline, queuedTask);
                throw new AssertionError("queued submission must reject the closing runtime");
            } catch (java.util.concurrent.ExecutionException failure) {
                assertInstanceOf(AgentRuntimeException.class, failure.getCause());
                AgentRuntimeException rejected = (AgentRuntimeException) failure.getCause();
                assertEquals(RuntimeErrorCode.RUNTIME_CLOSED, rejected.code(),
                        "race-b passed the outer check, so rejection must come from the inner recheck");
            }

            assertEquals(RuntimeStatus.CLOSED, runtime.status());
            assertEquals(0, runtime.inputs().retainedInputHandlers());
            assertEquals(0, runtime.inputs().retainedPendingInjections());
            assertEquals(0, executions.get());
            assertEquals(CommandState.REJECTED, runtime.commands().orElseThrow()
                    .status("race-a").status().orElseThrow().state());
            assertEquals(0, runtime.commands().orElseThrow().retainedLiveCommands());
            queue.forEach(Runnable::run);
            assertEquals(0, executions.get());
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            Throwable cleanupFailure = terminateCloseRaceThreads(
                    releaseDispatch, releaseDispose, releaser, admittedThread, queuedThread);
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw new AssertionError("close race cleanup failed", cleanupFailure);
                }
            }
        }
    }

    private static boolean awaitLatch(CountDownLatch latch, long deadline)
            throws InterruptedException {
        long remaining = remainingNanos(deadline);
        return remaining > 0 && latch.await(remaining, TimeUnit.NANOSECONDS);
    }

    private static <T> T getBefore(long deadline, FutureTask<T> task) throws Exception {
        long remaining = remainingNanos(deadline);
        if (remaining <= 0) {
            throw new java.util.concurrent.TimeoutException(
                    "close barrier body deadline exceeded");
        }
        return task.get(remaining, TimeUnit.NANOSECONDS);
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0L, deadline - System.nanoTime());
    }

    /**
     * Releases every latch, interrupts every created thread, then polls all liveness states
     * against one shared absolute deadline. Never joins per thread, so an already-interrupted
     * test thread cannot abort cleanup; failures are accumulated into a single Throwable.
     */
    private static Throwable terminateCloseRaceThreads(CountDownLatch releaseDispatch,
            CountDownLatch releaseDispose, Thread releaser, Thread admittedThread,
            Thread queuedThread) {
        Throwable cleanupFailure = null;
        long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            releaseDispatch.countDown();
        } catch (Throwable failure) {
            cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
        }
        try {
            releaseDispose.countDown();
        } catch (Throwable failure) {
            cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
        }
        Thread[] threads = {releaser, admittedThread, queuedThread};
        for (Thread thread : threads) {
            if (thread == null) {
                continue;
            }
            try {
                thread.interrupt();
            } catch (Throwable failure) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
            }
        }
        while (remainingNanos(cleanupDeadline) > 0) {
            boolean anyAlive = false;
            for (Thread thread : threads) {
                if (thread != null && thread.isAlive()) {
                    anyAlive = true;
                    break;
                }
            }
            if (!anyAlive) {
                return cleanupFailure;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        for (Thread thread : threads) {
            if (thread != null && thread.isAlive()) {
                cleanupFailure = appendCleanupFailure(cleanupFailure,
                        new AssertionError("close race thread did not terminate: "
                                + thread.getName()));
            }
        }
        return cleanupFailure;
    }

    private static Throwable appendCleanupFailure(Throwable failure, Throwable next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private static void assertClosedSubmission(org.junit.jupiter.api.function.Executable submission) {
        AgentRuntimeException failure = assertThrows(AgentRuntimeException.class, submission);
        assertEquals(RuntimeErrorCode.RUNTIME_CLOSED, failure.code());
    }

    private record CloseHandle(int value) implements CheckpointHandle {}

    private static Truncation truncation(FrameSnapshot frame, String dimension) {
        return frame.stats().truncations().stream()
                .filter(value -> value.dimension().equals(dimension))
                .findFirst().orElseThrow();
    }

    private static void assertTruncation(
            Truncation value, long observed, long retained, long limit) {
        assertEquals(observed, value.observed());
        assertEquals(retained, value.retained());
        assertEquals(limit, value.limit());
    }

    /** Counts every read of the wrapped fields so tests can observe attribute copies. */
    private static final class CountingFieldList extends AbstractList<RuntimeValue.Field> {
        private final List<RuntimeValue.Field> delegate;
        private final AtomicLong accesses;

        CountingFieldList(List<RuntimeValue.Field> delegate, AtomicLong accesses) {
            this.delegate = delegate;
            this.accesses = accesses;
        }

        @Override
        public RuntimeValue.Field get(int index) {
            accesses.incrementAndGet();
            return delegate.get(index);
        }

        @Override
        public int size() {
            accesses.incrementAndGet();
            return delegate.size();
        }
    }

    private static InspectableEntity entity(String id, long index) {
        return InspectableEntity.of(EntityId.of(id), EntityType.of("enemy"),
                () -> "Entity " + id,
                inspector -> inspector.property("index", () -> index));
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

    private static AgentRuntime runtime(RuntimeLimits limits, FrameStagingLimits staging) {
        AtomicLong time = new AtomicLong();
        return AgentRuntime.builder()
                .sessionId(SessionId.of("test-session-" + System.nanoTime()))
                .configuration(new RuntimeConfiguration(true, limits, staging))
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
