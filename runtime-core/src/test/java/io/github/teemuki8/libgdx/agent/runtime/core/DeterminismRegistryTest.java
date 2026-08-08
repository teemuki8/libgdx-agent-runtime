package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;

final class DeterminismRegistryTest {
    @Test
    void repeatsSeededScenarioAndReportsConfiguredObservableEquality() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] value = {0};
        AgentRuntime runtime = runtime(queue, value, delta -> value[0]++);
        runtime.scenarios().register(new ScenarioDescriptor("seeded", Optional.empty()), context ->
                value[0] = context.randomSeed().orElseThrow());
        runtime.start();

        DeterminismSpec spec = spec("seeded", 7, 2);
        DeterminismOperation submitted = runtime.determinism().check(
                spec, "determinism-equal", Duration.ofSeconds(1));
        assertEquals(CommandState.QUEUED,
                submitted.command().status().orElseThrow().state());
        queue.removeFirst().run();
        DeterminismResult result = runtime.determinism().check(
                spec, "determinism-equal", Duration.ofSeconds(1)).result().orElseThrow();

        assertEquals(DeterminismStatus.EQUAL, result.status());
        assertEquals(2, result.bounds().completedRepeats());
        assertEquals(2, result.bounds().ticksPerRepeat());
        assertTrue(result.message().contains("configured observable state"));
        assertEquals(List.of(
                DeterminismNormalization.EPOCH_RELATIVE_TICK,
                DeterminismNormalization.EXCLUDE_RUNTIME_IDENTIFIERS,
                DeterminismNormalization.EXCLUDE_WALL_CLOCK),
                result.profile().normalizationRules());
    }

    @Test
    void reportsFirstPropertyDivergenceWithBothActualFrames() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] value = {0};
        int[] reset = {0};
        AgentRuntime runtime = runtime(queue, value, delta -> value[0] += reset[0]);
        runtime.scenarios().register(new ScenarioDescriptor("divergent", Optional.empty()), context -> {
            value[0] = context.randomSeed().orElseThrow();
            reset[0]++;
        });
        runtime.start();

        DeterminismSpec spec = spec("divergent", 3, 2);

        runtime.determinism().check(spec, "determinism-diverged", Duration.ofSeconds(1));
        queue.removeFirst().run();
        DeterminismResult result = runtime.determinism().check(
                spec, "determinism-diverged", Duration.ofSeconds(1)).result().orElseThrow();

        assertEquals(DeterminismStatus.DIVERGED, result.status());
        assertEquals(1, result.epochRelativeTick().orElseThrow());
        assertTrue(result.leftFrameId().isPresent());
        assertTrue(result.rightFrameId().isPresent());
        DeterminismDifference difference = result.difference().orElseThrow();
        assertEquals(DeterminismDifferenceKind.PROPERTY, difference.kind());
        assertEquals(Optional.of("counter:value"), difference.fact());
        assertEquals(Optional.of(RuntimeValues.integer(4)), difference.left());
        assertEquals(Optional.of(RuntimeValues.integer(5)), difference.right());
    }

    @Test
    void failedResetProducesBoundedInconclusiveEvidence() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] value = {0};
        int[] resets = {0};
        AgentRuntime runtime = runtime(queue, value, delta -> value[0]++);
        runtime.scenarios().register("failed", context -> {
            if (++resets[0] == 2) {
                throw new IllegalStateException("reset rejected");
            }
            value[0] = context.randomSeed().orElseThrow();
        });
        runtime.start();
        DeterminismSpec spec = spec("failed", 1, 1);

        runtime.determinism().check(spec, "determinism-failed", Duration.ofSeconds(1));
        queue.removeFirst().run();
        DeterminismOperation operation = runtime.determinism().check(
                spec, "determinism-failed", Duration.ofSeconds(1));

        assertEquals(CommandState.SUCCEEDED,
                operation.command().status().orElseThrow().state());
        DeterminismResult result = operation.result().orElseThrow();
        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        ApplicationFailureEvidence failure = result.applicationFailure().orElseThrow();
        assertEquals("determinism.execute", failure.category());
        String message = result.message();
        assertEquals(failure.legacyEnvelope(), message);
        assertFalse(message.contains("reset rejected"));
        assertTrue(message.startsWith("determinism|failure-"));
        assertTrue(message.endsWith("|determinism.execute|java.lang.IllegalStateException"));
        assertEquals(1, result.bounds().completedRepeats());
    }

    @Test
    void failedSimulationPauseReportsStableCategoryAndOmitsRawMessages() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] value = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("determinism-pause-failure"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.entities().register(EntityId.of("counter"), EntityType.of("state"),
                () -> "Counter", inspector -> inspector.property("value", () -> value[0]));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {
                    throw new IllegalStateException("token=secret-123 /home/private/save.dat");
                })
                .resume(() -> {})
                .tick(delta -> value[0]++)
                .build());
        runtime.scenarios().register("seeded",
                context -> value[0] = context.randomSeed().orElseThrow());
        runtime.start();
        DeterminismSpec spec = spec("seeded", 1, 1);

        runtime.determinism().check(
                spec, "determinism-pause-failure", Duration.ofSeconds(1));
        queue.removeFirst().run();
        DeterminismOperation operation = runtime.determinism().check(
                spec, "determinism-pause-failure", Duration.ofSeconds(1));

        DeterminismResult result = operation.result().orElseThrow();
        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        ApplicationFailureEvidence failure = result.applicationFailure().orElseThrow();
        assertEquals("determinism.pause", failure.category());
        String message = result.message();
        assertEquals(failure.legacyEnvelope(), message);
        assertFalse(message.contains("token=secret-123"));
        assertFalse(message.contains("/home/private/save.dat"));
        assertTrue(message.startsWith("determinism-pause-failure|failure-"));
        assertTrue(message.endsWith("|determinism.pause|java.lang.IllegalStateException"));
    }

    @Test
    void hugeSanitizedDetailStaysWithinDeterminismMessageBound() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] value = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("determinism-sanitizer-bound"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .applicationFailureSanitizer((context, failure) ->
                        Optional.of("z".repeat(2_000)))
                .build();
        runtime.entities().register(EntityId.of("counter"), EntityType.of("state"),
                () -> "Counter", inspector -> inspector.property("value", () -> value[0]));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).tick(delta -> value[0]++).build());
        runtime.scenarios().register("seeded", context -> {
            throw new IllegalStateException("token=secret-123 /home/private/save.dat");
        });
        runtime.start();
        DeterminismSpec spec = spec("seeded", 1, 1);

        runtime.determinism().check(
                spec, "determinism-sanitizer-bound", Duration.ofSeconds(1));
        queue.removeFirst().run();
        DeterminismOperation operation = runtime.determinism().check(
                spec, "determinism-sanitizer-bound", Duration.ofSeconds(1));

        DeterminismResult result = operation.result().orElseThrow();
        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        ApplicationFailureEvidence failure = result.applicationFailure().orElseThrow();
        assertEquals(ApplicationFailureEvidence.MAX_SANITIZED_DETAIL_LENGTH,
                failure.sanitizedDetail().orElseThrow().length());
        String message = result.message();
        assertEquals(failure.legacyEnvelope(), message);
        assertTrue(message.length() <= ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY);
        assertFalse(message.contains("z".repeat(8)));
        assertFalse(message.contains("token=secret-123"));
        assertFalse(message.contains("/home/private/save.dat"));
    }

    @Test
    void rejectsExecutionBoundsBeforeApplicationDispatch() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] value = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("determinism-bounds"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .determinismLimits(new DeterminismLimits(
                        1, 2, 1, 1, 1, 1_024, Duration.ofSeconds(1).toNanos()))
                .build();
        runtime.entities().register(EntityId.of("counter"), EntityType.of("state"),
                () -> "Counter", inspector -> inspector.property("value", () -> value[0]));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).tick(delta -> value[0]++).build());
        runtime.scenarios().register("seeded",
                context -> value[0] = context.randomSeed().orElseThrow());
        runtime.start();

        AgentRuntimeException failure = assertThrows(AgentRuntimeException.class,
                () -> runtime.determinism().check(
                        new DeterminismSpec("seeded", 1, RuntimeValues.object(), 3, 1, 1,
                                spec("seeded", 1, 1).profile()),
                        "too-many-repeats", Duration.ofSeconds(1)));
        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED, failure.code());
        assertTrue(queue.isEmpty());
    }

    @Test
    void encodedEvidenceExhaustionMakesEqualityInconclusive() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] value = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("determinism-evidence-limit"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .determinismLimits(new DeterminismLimits(
                        1, 2, 1, 1, 1, 128, Duration.ofSeconds(1).toNanos()))
                .build();
        runtime.entities().register(EntityId.of("counter"), EntityType.of("state"),
                () -> "Counter", inspector -> inspector.property("value", () -> value[0]));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).tick(delta -> value[0]++).build());
        runtime.scenarios().register("seeded",
                context -> value[0] = context.randomSeed().orElseThrow());
        runtime.start();
        DeterminismSpec spec = spec("seeded", 1, 1);

        runtime.determinism().check(spec, "evidence-limit", Duration.ofSeconds(1));
        queue.removeFirst().run();
        DeterminismResult result = runtime.determinism().check(
                spec, "evidence-limit", Duration.ofSeconds(1)).result().orElseThrow();

        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        assertTrue(result.message().contains("encoded determinism evidence limit"));
        assertTrue(result.bounds().encodedEvidenceBytes() > 128);
    }

    @Test
    void evictedResultPollIsExplicitlyInconclusiveAndNeverReexecutes() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] value = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("determinism-retention"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .determinismLimits(new DeterminismLimits(
                        1, 2, 1, 1, 1, 1_024, Duration.ofSeconds(1).toNanos()))
                .build();
        runtime.entities().register(EntityId.of("counter"), EntityType.of("state"),
                () -> "Counter", inspector -> inspector.property("value", () -> value[0]));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).tick(delta -> value[0]++).build());
        runtime.scenarios().register("seeded",
                context -> value[0] = context.randomSeed().orElseThrow());
        runtime.start();
        DeterminismSpec first = spec("seeded", 1, 1);
        DeterminismSpec second = spec("seeded", 2, 1);
        DeterminismSpec third = spec("seeded", 3, 1);

        runtime.determinism().check(first, "first", Duration.ofSeconds(1));
        queue.removeFirst().run();
        runtime.determinism().check(second, "second", Duration.ofSeconds(1));
        queue.removeFirst().run();
        runtime.determinism().check(third, "third", Duration.ofSeconds(1));
        queue.removeFirst().run();
        DeterminismOperation evicted =
                runtime.determinism().check(first, "first", Duration.ofSeconds(1));

        assertEquals(DeterminismStatus.INCONCLUSIVE,
                evicted.result().orElseThrow().status());
        assertTrue(evicted.result().orElseThrow().message().contains("evicted"));
        assertTrue(queue.isEmpty());
    }

    private static AgentRuntime runtime(
            ArrayDeque<Runnable> queue, long[] value, LongConsumer tick) {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("determinism"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.entities().register(EntityId.of("counter"), EntityType.of("state"),
                () -> "Counter", inspector -> inspector.property("value", () -> value[0]));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {})
                .tick(tick)
                .build());
        return runtime;
    }

    private static DeterminismSpec spec(String scenarioId, long seed, int ticks) {
        return new DeterminismSpec(
                scenarioId, seed, RuntimeValues.object(), 2, ticks, 1,
                new DeterminismProfile(
                        new SnapshotComparisonScope(
                                List.of(EntityId.of("counter")), List.of("value"),
                                List.of(), false, false),
                        false));
    }
}
