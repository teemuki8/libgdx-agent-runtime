package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.ChangeKind;
import io.github.teemuki8.libgdx.agent.runtime.core.ChangeQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.CommandState;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EventQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameRange;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class PublicJavaWorkflowTest {
    @Test
    void fixtureProducesCompletePublicJavaEvidence() {
        DeterministicSimulation simulation = new DeterministicSimulation();
        AgentRuntime runtime = simulation.startRuntime();
        IntStream.rangeClosed(1, 45).forEach(frame -> simulation.advance(runtime, frame));
        FrameRange range = FrameRange.of(0, 45);

        assertEquals(46, runtime.latestFrame().orElseThrow().frameId().value() + 1);
        assertFalse(runtime.entity(EntityId.of("enemy-2")).isPresent());
        var health = runtime.changes(new ChangeQuery(
                range, Optional.of(EntityId.of("enemy-2")), Optional.empty(),
                Optional.of("health"), 100)).items();
        assertEquals(1, health.size());
        assertEquals(RuntimeValues.integer(100), health.getFirst().before().orElseThrow());
        assertEquals(RuntimeValues.integer(75), health.getFirst().after().orElseThrow());
        assertEquals(ChangeKind.ENTITY_REMOVED,
                runtime.changes(new ChangeQuery(range, Optional.of(EntityId.of("enemy-2")),
                        Optional.empty(), Optional.empty(), 100)).items().getLast().kind());
        assertTrue(runtime.events(new EventQuery(
                range, Optional.of("projectile.hit"), false,
                Optional.of(EntityId.of("enemy-2")), Optional.of(EntityId.of("projectile-3")), 100))
                .items().size() == 1);
        assertEquals(1, runtime.decisions(new DecisionQuery(
                range, Optional.empty(), Optional.of(EntityId.of("tower-1")),
                Optional.of(EntityId.of("enemy-2")), Optional.of("out-of-range"), 100))
                .items().size());
        runtime.close();
    }

    @Test
    void controlActionRetryAndAttributionShareTheConfiguredApplicationThread() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        DeterministicSimulation simulation = new DeterministicSimulation();
        AgentRuntime runtime = simulation.startRuntime(applicationQueue::addLast);
        Thread applicationThread = Thread.currentThread();

        runtime.controls().control(true, "java-pause", Duration.ofSeconds(1));
        applicationQueue.removeFirst().run();
        assertSame(applicationThread, simulation.lastMutationThread());

        var parameters = RuntimeValues.object(
                RuntimeValues.field("state", RuntimeValues.string("ALERT")));
        var first = runtime.actions().invoke(
                "set-tower-state", "java-action", parameters,
                Optional.of("fixture-action-1"), Duration.ofSeconds(1));
        var retry = runtime.actions().invoke(
                "set-tower-state", "java-action", parameters,
                Optional.of("fixture-action-1"), Duration.ofSeconds(1));
        assertEquals(CommandState.QUEUED, first.command().status().orElseThrow().state());
        assertEquals(CommandState.QUEUED, retry.command().status().orElseThrow().state());
        assertEquals(1, applicationQueue.size());
        applicationQueue.removeFirst().run();
        assertSame(applicationThread, simulation.lastMutationThread());
        assertEquals(1, simulation.actionExecutions());

        runtime.controls().advance("java-tick", 1, 16_000_000, Duration.ofSeconds(1));
        applicationQueue.removeFirst().run();
        var changes = runtime.changes(new ChangeQuery(
                FrameRange.of(0, 1), Optional.of(EntityId.of("tower-1")), Optional.empty(),
                Optional.of("state"), 8)).items();
        assertEquals(1, changes.size());
        assertEquals("fixture-action",
                changes.getFirst().cause().metadata().sourceSubsystem().orElseThrow());
        assertEquals("fixture-action-1",
                changes.getFirst().cause().metadata().correlationId().orElseThrow());
        assertEquals(List.of(16_000_000L), simulation.controlledDeltaNanos());
        assertSame(applicationThread, simulation.lastMutationThread());
        assertEquals(List.of("set-tower-state"),
                runtime.actions().list().stream().map(descriptor -> descriptor.id()).toList());
        runtime.close();
    }
}
