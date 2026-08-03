package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class CommandDispatchTest {
    @Test
    void executesOnceOnApplicationOwnedCaptureThread() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        AtomicLong clock = new AtomicLong(10);
        AtomicInteger executions = new AtomicInteger();
        AgentRuntime runtime = runtime(applicationQueue, clock,
                CommandDispatchLimits.developmentDefaults());
        CommandDispatch commands = runtime.commands().orElseThrow();

        assertEquals(CommandState.QUEUED, commands.submit(
                "attack-1", Duration.ofNanos(100), executions::incrementAndGet)
                .status().orElseThrow().state());
        assertEquals(CommandState.QUEUED, commands.submit(
                "attack-1", Duration.ofNanos(100), executions::incrementAndGet)
                .status().orElseThrow().state());
        assertEquals(1, applicationQueue.size());

        applicationQueue.removeFirst().run();

        CommandStatus status = commands.status("attack-1").status().orElseThrow();
        assertEquals(CommandState.SUCCEEDED, status.state());
        assertTrue(status.outcomeKnown());
        assertEquals(1, executions.get());
    }

    @Test
    void cancelsOnlyBeforeDispatchAndQueuedTaskBecomesNoOp() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        AtomicInteger executions = new AtomicInteger();
        CommandDispatch commands = runtime(applicationQueue, new AtomicLong(1),
                CommandDispatchLimits.developmentDefaults()).commands().orElseThrow();
        commands.submit("reset-1", Duration.ofSeconds(1), executions::incrementAndGet);

        CommandCancellation cancellation = commands.cancel("reset-1");

        assertTrue(cancellation.accepted());
        assertEquals(CommandState.CANCELLED,
                cancellation.command().status().orElseThrow().state());
        applicationQueue.removeFirst().run();
        assertEquals(0, executions.get());
        assertFalse(commands.cancel("reset-1").accepted());
    }

    @Test
    void reportsDeadlineAfterDispatchAsUnknownUntilTaskCompletes() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        AtomicLong clock = new AtomicLong(1);
        CommandDispatch commands = runtime(applicationQueue, clock,
                CommandDispatchLimits.developmentDefaults()).commands().orElseThrow();
        CommandStatus[] observed = new CommandStatus[1];
        commands.submit("advance-1", 10, () -> {
            clock.set(11);
            observed[0] = commands.status("advance-1").status().orElseThrow();
        });

        applicationQueue.removeFirst().run();

        assertEquals(CommandState.TIMED_OUT, observed[0].state());
        assertFalse(observed[0].outcomeKnown());
        assertEquals(CommandState.SUCCEEDED,
                commands.status("advance-1").status().orElseThrow().state());
    }

    @Test
    void boundsQueueResultsExpiredIdsAndDiagnostics() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        AtomicLong clock = new AtomicLong(1);
        CommandDispatch commands = runtime(applicationQueue, clock,
                new CommandDispatchLimits(1, 1, 1, 12)).commands().orElseThrow();
        commands.submit("one", 100, () -> {
            throw new IllegalStateException("sensitive diagnostic detail");
        });
        assertEquals(CommandState.REJECTED,
                commands.submit("two", 100, () -> {}).status().orElseThrow().state());
        assertEquals(CommandLookup.Kind.FOUND, commands.status("two").kind());

        applicationQueue.removeFirst().run();

        CommandStatus failed = commands.status("one").status().orElseThrow();
        assertEquals(CommandState.FAILED, failed.state());
        assertTrue(failed.diagnostic().orElseThrow().length() <= 12);
        assertEquals(CommandLookup.Kind.EXPIRED, commands.status("two").kind());
        commands.submit("three", 100, () -> {});
        applicationQueue.removeFirst().run();
        assertEquals(CommandLookup.Kind.EXPIRED, commands.status("one").kind());
        assertEquals(CommandLookup.Kind.UNKNOWN, commands.status("two").kind());
    }

    @Test
    void disablesDispatchWithoutExplicitEnabledConfiguration() {
        assertTrue(AgentRuntime.builder().build().commands().isEmpty());
        assertTrue(AgentRuntime.builder()
                .configuration(RuntimeConfiguration.disabled())
                .commandDispatcher(Runnable::run)
                .build().commands().isEmpty());
    }

    private static AgentRuntime runtime(ArrayDeque<Runnable> applicationQueue,
            AtomicLong clock, CommandDispatchLimits limits) {
        return AgentRuntime.builder()
                .sessionId(SessionId.of("game"))
                .captureThread(Thread.currentThread())
                .clock(clock::get)
                .commandDispatcher(applicationQueue::addLast)
                .commandDispatchLimits(limits)
                .build();
    }
}
