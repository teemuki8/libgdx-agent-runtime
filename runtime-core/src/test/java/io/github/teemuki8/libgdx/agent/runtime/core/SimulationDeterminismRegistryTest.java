package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SimulationDeterminismRegistryTest {
    private static final long STEP = 16;

    @Test
    void repeatsRegisteredInputsInStableTickOrderAndReturnsActualTickEquality() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        ArrayList<String> inputOrder = new ArrayList<>();
        long[] position = {0};
        AgentRuntime runtime = runtime(queue, position, inputOrder, false);
        runtime.start();
        SimulationDeterminismSpec spec = spec(List.of(
                input(2, "move", 2), input(1, "move", 1), input(2, "move", 3)));

        SimulationDeterminismOperation submitted = runtime.determinism().checkSimulation(
                spec, "simulation-equal", Duration.ofSeconds(1));
        assertEquals(CommandState.QUEUED, submitted.command().status().orElseThrow().state());
        queue.removeFirst().run();
        SimulationDeterminismResult result = runtime.determinism().checkSimulation(
                spec, "simulation-equal", Duration.ofSeconds(1)).result().orElseThrow();

        assertEquals(DeterminismStatus.EQUAL, result.status());
        assertEquals(List.of("1", "2", "3", "1", "2", "3"), inputOrder);
        assertEquals(2, result.bounds().completedRepeats());
        assertTrue(result.divergence().isEmpty());
    }

    @Test
    void reportsFirstDifferentActualSimulationTickAndBothCorrelations() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        ArrayList<String> inputOrder = new ArrayList<>();
        long[] position = {0};
        AgentRuntime runtime = runtime(queue, position, inputOrder, true);
        runtime.start();
        SimulationDeterminismSpec spec = spec(List.of(input(2, "move", 2)));

        runtime.determinism().checkSimulation(
                spec, "simulation-diverged", Duration.ofSeconds(1));
        queue.removeFirst().run();
        SimulationDeterminismResult result = runtime.determinism().checkSimulation(
                spec, "simulation-diverged", Duration.ofSeconds(1)).result().orElseThrow();

        assertEquals(DeterminismStatus.DIVERGED, result.status());
        SimulationDeterminismDivergence divergence = result.divergence().orElseThrow();
        assertEquals(2, divergence.epochTick());
        assertTrue(divergence.leftSimulationTickId().value()
                < divergence.rightSimulationTickId().value());
        assertTrue(divergence.leftFrameId().value() < divergence.rightFrameId().value());
        assertEquals(DeterminismDifferenceKind.PROPERTY, divergence.difference().kind());
        assertEquals(Optional.of("box2d.body.player:position"),
                divergence.difference().fact());
        assertEquals(Optional.of(RuntimeValues.integer(2)), divergence.difference().left());
        assertEquals(Optional.of(RuntimeValues.integer(4)), divergence.difference().right());
    }

    @Test
    void rejectsFixedStepAndConfigurationConflictBeforeDispatch() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = runtime(queue, new long[] {0}, new ArrayList<>(), false);
        runtime.start();
        SimulationDeterminismSpec wrongStep = new SimulationDeterminismSpec(
                execution(STEP * 2), List.of(), configuration(), completeness(), List.of());

        AgentRuntimeException step = assertThrows(AgentRuntimeException.class,
                () -> runtime.determinism().checkSimulation(
                        wrongStep, "wrong-step", Duration.ofSeconds(1)));
        assertEquals(RuntimeErrorCode.INVALID_QUERY, step.code());
        SimulationConfigurationRequirement wrongIterations =
                new SimulationConfigurationRequirement(EntityId.of("box2d.world.main"),
                        "velocityIterations", RuntimeValues.integer(9));
        SimulationDeterminismSpec wrongConfiguration = new SimulationDeterminismSpec(
                execution(STEP), List.of(), List.of(wrongIterations), completeness(), List.of());
        AgentRuntimeException configurationFailure = assertThrows(AgentRuntimeException.class,
                () -> runtime.determinism().checkSimulation(
                        wrongConfiguration, "wrong-configuration", Duration.ofSeconds(1)));

        assertEquals(RuntimeErrorCode.INVALID_QUERY, configurationFailure.code());
        DeterminismSpec missingPropertyExecution = new DeterminismSpec(
                "player-move", 7, RuntimeValues.object(), 2, 3, STEP,
                new DeterminismProfile(new SnapshotComparisonScope(
                        List.of(EntityId.of("box2d.body.player")), List.of("positoin"),
                        List.of(), false, false), false));
        SimulationDeterminismSpec missingProperty = new SimulationDeterminismSpec(
                missingPropertyExecution, List.of(), configuration(), completeness(), List.of());
        AgentRuntimeException selectionFailure = assertThrows(AgentRuntimeException.class,
                () -> runtime.determinism().checkSimulation(
                        missingProperty, "missing-property", Duration.ofSeconds(1)));

        assertEquals(RuntimeErrorCode.INVALID_QUERY, selectionFailure.code());
        assertTrue(selectionFailure.getMessage().contains("property"));
        assertTrue(queue.isEmpty());
    }

    @Test
    void postResetConfigurationDriftAndIncompleteContactsAreInconclusiveBeforeEquality() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        long[] iterations = {8};
        boolean[] complete = {true};
        int[] ticks = {0};
        AgentRuntime runtime = customRuntime(queue, position, iterations, complete,
                delta -> {
                    ticks[0]++;
                    return delta;
                }, context -> iterations[0] = 9);
        runtime.start();
        SimulationDeterminismSpec spec = spec(List.of());

        runtime.determinism().checkSimulation(
                spec, "configuration-drift", Duration.ofSeconds(1));
        queue.removeFirst().run();
        SimulationDeterminismResult drift = runtime.determinism().checkSimulation(
                spec, "configuration-drift", Duration.ofSeconds(1)).result().orElseThrow();

        assertEquals(DeterminismStatus.INCONCLUSIVE, drift.status());
        assertTrue(drift.message().contains("configuration requirement"));
        assertEquals(0, ticks[0]);

        ArrayDeque<Runnable> contactQueue = new ArrayDeque<>();
        long[] contactPosition = {0};
        long[] contactIterations = {8};
        boolean[] contactComplete = {true};
        AgentRuntime contactRuntime = customRuntime(contactQueue, contactPosition,
                contactIterations, contactComplete, delta -> delta,
                context -> contactComplete[0] = false);
        contactRuntime.start();
        contactRuntime.determinism().checkSimulation(
                spec, "contacts-incomplete", Duration.ofSeconds(1));
        contactQueue.removeFirst().run();
        SimulationDeterminismResult incomplete = contactRuntime.determinism().checkSimulation(
                spec, "contacts-incomplete", Duration.ofSeconds(1)).result().orElseThrow();

        assertEquals(DeterminismStatus.INCONCLUSIVE, incomplete.status());
        assertTrue(incomplete.message().contains("incomplete"));
    }

    @Test
    void perFrameEventTruncationIsInconclusiveInsteadOfEqual() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        RuntimeLimits defaults = RuntimeLimits.developmentDefaults();
        RuntimeLimits limitedEvents = new RuntimeLimits(defaults.retainedFrames(), 1,
                defaults.entitiesPerSnapshot(), defaults.propertiesPerEntity(),
                defaults.decisionsPerFrame(), defaults.candidatesPerDecision(),
                defaults.attributesPerItem(), defaults.stringLength(),
                defaults.collectionLength(), defaults.nestingDepth(), defaults.queryResults());
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("simulation-determinism-truncation"))
                .configuration(new RuntimeConfiguration(true, limitedEvents))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        registerRuntime(runtime, new long[] {0}, new long[] {8}, new boolean[] {true},
                delta -> {
                    runtime.emit(EventSpec.type("physics.event"));
                    runtime.emit(EventSpec.type("physics.event"));
                    return delta;
                }, context -> {});
        runtime.start();
        DeterminismSpec execution = new DeterminismSpec("player-move", 7,
                RuntimeValues.object(), 2, 3, STEP,
                new DeterminismProfile(new SnapshotComparisonScope(
                        List.of(EntityId.of("box2d.body.player")), List.of("position"),
                        List.of(), true, false), false));
        SimulationDeterminismSpec spec = new SimulationDeterminismSpec(execution, List.of(),
                configuration(), completeness(), List.of(EventType.of("physics.event")));

        runtime.determinism().checkSimulation(
                spec, "truncated-events", Duration.ofSeconds(1));
        queue.removeFirst().run();
        SimulationDeterminismResult result = runtime.determinism().checkSimulation(
                spec, "truncated-events", Duration.ofSeconds(1)).result().orElseThrow();

        assertEquals(DeterminismStatus.INCONCLUSIVE, result.status());
        assertTrue(result.message().contains("truncation"));
    }

    @Test
    void resetAndTickFailuresAreSanitizedAndNeverEqual() {
        ArrayDeque<Runnable> resetQueue = new ArrayDeque<>();
        AgentRuntime resetRuntime = customRuntime(resetQueue, new long[] {0}, new long[] {8},
                new boolean[] {true}, delta -> delta,
                context -> {
                    throw new IllegalStateException("secret reset path /tmp/private");
                });
        resetRuntime.start();
        SimulationDeterminismSpec spec = spec(List.of());
        resetRuntime.determinism().checkSimulation(
                spec, "reset-failure", Duration.ofSeconds(1));
        resetQueue.removeFirst().run();
        SimulationDeterminismResult resetFailure = resetRuntime.determinism().checkSimulation(
                spec, "reset-failure", Duration.ofSeconds(1)).result().orElseThrow();

        assertEquals(DeterminismStatus.INCONCLUSIVE, resetFailure.status());
        assertEquals("simulationDeterminism.execute",
                resetFailure.applicationFailure().orElseThrow().category());
        assertFalse(resetFailure.message().contains("secret reset"));

        ArrayDeque<Runnable> tickQueue = new ArrayDeque<>();
        AgentRuntime tickRuntime = customRuntime(tickQueue, new long[] {0}, new long[] {8},
                new boolean[] {true}, delta -> {
                    throw new IllegalStateException("secret tick token");
                }, context -> {});
        tickRuntime.start();
        tickRuntime.determinism().checkSimulation(
                spec, "tick-failure", Duration.ofSeconds(1));
        tickQueue.removeFirst().run();
        SimulationDeterminismResult tickFailure = tickRuntime.determinism().checkSimulation(
                spec, "tick-failure", Duration.ofSeconds(1)).result().orElseThrow();

        assertEquals(DeterminismStatus.INCONCLUSIVE, tickFailure.status());
        assertFalse(tickFailure.message().contains("secret tick"));
    }

    @Test
    void unknownOrOutstandingInputIsRejectedBeforeDeterminismDispatch() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = runtime(queue, new long[] {0}, new ArrayList<>(), false);
        runtime.start();
        SimulationDeterminismSpec unknown = spec(List.of(input(1, "unknown", 1)));
        assertThrows(IllegalArgumentException.class, () -> runtime.determinism().checkSimulation(
                unknown, "unknown-input", Duration.ofSeconds(1)));
        runtime.controls().control(true, "pause-first", Duration.ofSeconds(1));
        queue.removeFirst().run();
        runtime.inputs().inject("move", "ordinary-input", RuntimeValues.object(
                RuntimeValues.field("amount", RuntimeValues.integer(1))),
                java.util.OptionalLong.empty(), Duration.ofSeconds(1));

        AgentRuntimeException outstanding = assertThrows(AgentRuntimeException.class,
                () -> runtime.determinism().checkSimulation(
                        spec(List.of()), "blocked-by-input", Duration.ofSeconds(1)));
        assertEquals(RuntimeErrorCode.INVALID_LIFECYCLE, outstanding.code());
        assertEquals(1, queue.size());
    }

    @Test
    void simulationAndLegacyOperationsShareRetentionAndEvictionIsInconclusive() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("simulation-determinism-retention"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .determinismLimits(new DeterminismLimits(
                        1, 2, 3, 10, 20, 4_096, Duration.ofSeconds(1).toNanos()))
                .build();
        registerRuntime(runtime, position, new long[] {8}, new boolean[] {true},
                delta -> delta, context -> position[0] = 0);
        runtime.start();
        SimulationDeterminismSpec first = spec(List.of());
        runtime.determinism().checkSimulation(first, "first-simulation", Duration.ofSeconds(1));
        queue.removeFirst().run();
        DeterminismSpec legacy = execution(STEP);
        runtime.determinism().check(legacy, "second-legacy", Duration.ofSeconds(1));
        queue.removeFirst().run();

        SimulationDeterminismOperation evicted = runtime.determinism().checkSimulation(
                first, "first-simulation", Duration.ofSeconds(1));
        assertEquals(DeterminismStatus.INCONCLUSIVE,
                evicted.result().orElseThrow().status());
        assertTrue(evicted.result().orElseThrow().message().contains("evicted"));
        assertTrue(queue.isEmpty());
    }

    private static AgentRuntime runtime(ArrayDeque<Runnable> queue, long[] position,
            List<String> inputOrder, boolean divergeByRepeat) {
        int[] resets = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("simulation-determinism"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP));
        runtime.entities().register(EntityId.of("box2d.world.main"), EntityType.of("box2d.world"),
                () -> "main", inspector -> inspector
                        .property("fixedStepNanos", () -> STEP)
                        .property("velocityIterations", () -> 8L));
        runtime.entities().register(EntityId.of("box2d.body.player"), EntityType.of("box2d.body"),
                () -> "player", inspector -> inspector.property("position", () -> position[0]));
        runtime.entities().register(EntityId.of("box2d.contacts.main"),
                EntityType.of("box2d.contacts"), () -> "main",
                inspector -> inspector.property("complete", () -> true));
        runtime.inputs().register(InputSpec.builder("move").requiredInteger("amount")
                .handler(parameters -> {
                    long amount = parameters.requiredInteger("amount");
                    inputOrder.add(Long.toString(amount));
                    position[0] += divergeByRepeat && resets[0] == 2 ? amount * 2 : amount;
                }).build());
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {})
                .acknowledgedTick(delta -> delta)
                .build());
        runtime.scenarios().register("player-move", context -> {
            resets[0]++;
            position[0] = 0;
        });
        return runtime;
    }

    private static AgentRuntime customRuntime(ArrayDeque<Runnable> queue, long[] position,
            long[] iterations, boolean[] complete, SimulationTickCallback tick,
            ScenarioResetHandler reset) {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("simulation-determinism-custom"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        registerRuntime(runtime, position, iterations, complete, tick, reset);
        return runtime;
    }

    private static void registerRuntime(AgentRuntime runtime, long[] position, long[] iterations,
            boolean[] complete, SimulationTickCallback tick, ScenarioResetHandler reset) {
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP));
        runtime.entities().register(EntityId.of("box2d.world.main"), EntityType.of("box2d.world"),
                () -> "main", inspector -> inspector
                        .property("fixedStepNanos", () -> STEP)
                        .property("velocityIterations", () -> iterations[0]));
        runtime.entities().register(EntityId.of("box2d.body.player"), EntityType.of("box2d.body"),
                () -> "player", inspector -> inspector.property("position", () -> position[0]));
        runtime.entities().register(EntityId.of("box2d.contacts.main"),
                EntityType.of("box2d.contacts"), () -> "main",
                inspector -> inspector.property("complete", () -> complete[0]));
        runtime.inputs().register(InputSpec.builder("move").requiredInteger("amount")
                .handler(parameters -> position[0] += parameters.requiredInteger("amount"))
                .build());
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).acknowledgedTick(tick).build());
        runtime.scenarios().register("player-move", reset);
    }

    private static SimulationDeterminismSpec spec(List<SimulationDeterminismInput> inputs) {
        return new SimulationDeterminismSpec(
                execution(STEP), inputs, configuration(), completeness(), List.of());
    }

    private static DeterminismSpec execution(long step) {
        return new DeterminismSpec("player-move", 7, RuntimeValues.object(), 2, 3, step,
                new DeterminismProfile(new SnapshotComparisonScope(
                        List.of(EntityId.of("box2d.body.player")), List.of("position"),
                        List.of(), false, false), false));
    }

    private static List<SimulationConfigurationRequirement> configuration() {
        return List.of(
                new SimulationConfigurationRequirement(EntityId.of("box2d.world.main"),
                        "fixedStepNanos", RuntimeValues.integer(STEP)),
                new SimulationConfigurationRequirement(EntityId.of("box2d.world.main"),
                        "velocityIterations", RuntimeValues.integer(8)));
    }

    private static List<SimulationEvidenceRequirement> completeness() {
        return List.of(new SimulationEvidenceRequirement(
                EntityId.of("box2d.contacts.main"), "complete"));
    }

    private static SimulationDeterminismInput input(long tick, String inputId, long amount) {
        return new SimulationDeterminismInput(tick, inputId, RuntimeValues.object(
                RuntimeValues.field("amount", RuntimeValues.integer(amount))));
    }
}
