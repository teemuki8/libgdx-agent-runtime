package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ActionRegistryTest {
    @Test
    void validatesDispatchesOnceAndReportsFrameEvidence() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<EntityId> target = new AtomicReference<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("game"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.actions().register(ActionSpec.builder("player.attack")
                .description("Attack one target")
                .requiredEntityId("targetEntity")
                .handler(parameters -> {
                    executions.incrementAndGet();
                    target.set(parameters.requiredEntityId("targetEntity"));
                    runtime.frame(1, () -> runtime.emit(EventSpec.type("attack.executed")
                            .correlationId("attack-172")));
                }).build());
        runtime.start();
        RuntimeValue.ObjectValue parameters = RuntimeValues.object(RuntimeValues.field(
                "targetEntity", RuntimeValues.string("enemy-1")));

        ActionInvocation queued = runtime.actions().invoke("player.attack", "request-1",
                parameters, Optional.of("attack-172"), Duration.ofSeconds(1));
        assertEquals(CommandState.QUEUED, queued.command().status().orElseThrow().state());
        assertEquals(Optional.of(new FrameId(0)), queued.submittedFrameId());
        queue.removeFirst().run();
        ActionInvocation completed = runtime.actions().invoke("player.attack", "request-1",
                parameters, Optional.of("attack-172"), Duration.ofSeconds(1));

        assertEquals(CommandState.SUCCEEDED, completed.command().status().orElseThrow().state());
        assertEquals(Optional.of(new FrameId(1)), completed.completedFrameId());
        assertEquals(1, executions.get());
        assertEquals(EntityId.of("enemy-1"), target.get());
        assertEquals("player.attack", runtime.actions().list().getFirst().id());
    }

    @Test
    void rejectsAllClosedSchemaViolationsBeforeDispatch() {
        AtomicInteger executions = new AtomicInteger();
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder().clock(() -> 1)
                .commandDispatcher(queue::addLast).build();
        runtime.actions().register(ActionSpec.builder("player.attack")
                .requiredEntityId("targetEntity")
                .handler(ignored -> executions.incrementAndGet()).build());
        runtime.start();

        List<RuntimeValue.ObjectValue> invalid = List.of(
                RuntimeValues.object(),
                RuntimeValues.object(RuntimeValues.field("unknown", RuntimeValues.string("x"))),
                RuntimeValues.object(RuntimeValues.field(
                        "targetEntity", RuntimeValues.integer(1))));
        for (int index = 0; index < invalid.size(); index++) {
            RuntimeValue.ObjectValue values = invalid.get(index);
            int request = index;
            assertThrows(IllegalArgumentException.class, () -> runtime.actions().invoke(
                    "player.attack", "invalid-" + request, values, Optional.empty(),
                    Duration.ofSeconds(1)));
        }
        assertThrows(IllegalArgumentException.class, () -> runtime.actions().invoke(
                "missing", "unknown-action", RuntimeValues.object(), Optional.empty(),
                Duration.ofSeconds(1)));
        assertTrue(queue.isEmpty());
        assertEquals(0, executions.get());
    }

    @Test
    void invalidTimeoutDoesNotPoisonAnInvocationRequestId() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder().clock(() -> 1)
                .commandDispatcher(queue::addLast).build();
        runtime.actions().register(ActionSpec.builder("ping")
                .handler(ignored -> {}).build());
        runtime.start();
        RuntimeValue.ObjectValue parameters = RuntimeValues.object();

        assertThrows(IllegalArgumentException.class, () -> runtime.actions().invoke(
                "ping", "retryable", parameters, Optional.empty(), Duration.ZERO));
        ActionInvocation retry = runtime.actions().invoke(
                "ping", "retryable", parameters, Optional.empty(), Duration.ofNanos(100));

        assertEquals(CommandState.QUEUED, retry.command().status().orElseThrow().state());
        assertEquals(1, queue.size());
    }
}
