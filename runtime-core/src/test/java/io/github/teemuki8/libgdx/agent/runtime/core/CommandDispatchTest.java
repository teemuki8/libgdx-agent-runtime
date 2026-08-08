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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class CommandDispatchTest {
    @Test
    void executesOnceOnApplicationOwnedCaptureThread() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        AtomicLong clock = new AtomicLong(10);
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<Thread> executionThread = new AtomicReference<>();
        AgentRuntime runtime = runtime(applicationQueue, clock,
                CommandDispatchLimits.developmentDefaults());
        CommandDispatch commands = runtime.commands().orElseThrow();

        assertEquals(CommandState.QUEUED, commands.submit(
                "attack-1", Duration.ofNanos(100), () -> {
                    executionThread.set(Thread.currentThread());
                    executions.incrementAndGet();
                })
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
        assertEquals(Thread.currentThread(), executionThread.get());
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
                new CommandDispatchLimits(1, 1, 1, 1_000, 642)).commands().orElseThrow();
        commands.submit("one", 100, () -> {
            throw new IllegalStateException("sensitive diagnostic detail");
        });
        assertEquals(CommandState.REJECTED,
                commands.submit("two", 100, () -> {}).status().orElseThrow().state());
        assertEquals(CommandLookup.Kind.FOUND, commands.status("two").kind());

        applicationQueue.removeFirst().run();

        CommandStatus failed = commands.status("one").status().orElseThrow();
        assertEquals(CommandState.FAILED, failed.state());
        assertTrue(failed.diagnostic().orElseThrow().length()
                <= ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY);
        assertTrue(failed.applicationFailure().isPresent());
        assertFalse(failed.diagnostic().orElseThrow().contains("sensitive diagnostic detail"));
        assertEquals(CommandLookup.Kind.EXPIRED, commands.status("two").kind());
        commands.submit("three", 100, () -> {});
        applicationQueue.removeFirst().run();
        assertEquals(CommandLookup.Kind.EXPIRED, commands.status("one").kind());
        assertEquals(CommandLookup.Kind.UNKNOWN, commands.status("two").kind());
    }

    @Test
    void commandFailureDiagnosticsOmitRawApplicationMessages() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        CommandDispatch commands = runtime(applicationQueue, new AtomicLong(1),
                CommandDispatchLimits.developmentDefaults()).commands().orElseThrow();
        commands.submit("secret-failure", 100, () -> {
            throw new IllegalStateException("token=secret-123 /home/private/save.dat");
        });

        applicationQueue.removeFirst().run();

        CommandStatus failed = commands.status("secret-failure").status().orElseThrow();
        assertEquals(CommandState.FAILED, failed.state());
        ApplicationFailureEvidence failure = failed.applicationFailure().orElseThrow();
        assertEquals("command.failed", failure.category());
        String diagnostic = failed.diagnostic().orElseThrow();
        assertEquals("game|failure-2|command.failed|java.lang.IllegalStateException", diagnostic);
        assertFalse(diagnostic.contains("token=secret-123"));
        assertFalse(diagnostic.contains("/home/private/save.dat"));
        assertTrue(failure.sanitizedDetail().isEmpty());
    }

    @Test
    void sanitizerDetailAppearsInCommandFailureDiagnostics() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("command-sanitized"))
                .captureThread(Thread.currentThread())
                .clock(() -> 1)
                .commandDispatcher(applicationQueue::addLast)
                .applicationFailureSanitizer((context, failure) ->
                        Optional.of("safe-detail"))
                .build();
        CommandDispatch commands = runtime.commands().orElseThrow();
        commands.submit("safe-failure", 100, () -> {
            throw new IllegalStateException("token=secret-123");
        });

        applicationQueue.removeFirst().run();

        CommandStatus failed = commands.status("safe-failure").status().orElseThrow();
        assertEquals(Optional.of("safe-detail"),
                failed.applicationFailure().orElseThrow().sanitizedDetail());
        String diagnostic = failed.diagnostic().orElseThrow();
        assertTrue(diagnostic.contains("command-sanitized|failure-2|command.failed"));
        assertFalse(diagnostic.contains("safe-detail"));
        assertFalse(diagnostic.contains("token=secret-123"));
    }

    @Test
    void dispatchRejectionCorrelationsFollowDeterministicAcceptanceOrder() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("dispatch-correlation-order"))
                .captureThread(Thread.currentThread())
                .clock(() -> 1)
                .commandDispatcher(task -> {
                    throw new IllegalStateException("dispatcher token=secret-123");
                })
                .build();
        CommandDispatch commands = runtime.commands().orElseThrow();
        commands.submit("one", 100, () -> {});
        commands.submit("two", 100, () -> {});
        commands.submit("three", 100, () -> {});

        String first = commands.status("one").status().orElseThrow().diagnostic().orElseThrow();
        String second = commands.status("two").status().orElseThrow().diagnostic().orElseThrow();
        String third = commands.status("three").status().orElseThrow().diagnostic().orElseThrow();
        assertEquals("dispatch-correlation-order|failure-1|command.dispatch.rejected"
                + "|java.lang.IllegalStateException", first);
        assertEquals("dispatch-correlation-order|failure-2|command.dispatch.rejected"
                + "|java.lang.IllegalStateException", second);
        assertEquals("dispatch-correlation-order|failure-3|command.dispatch.rejected"
                + "|java.lang.IllegalStateException", third);
        assertFalse(first.contains("dispatcher token=secret-123"));
    }

    @Test
    void disablesDispatchWithoutExplicitEnabledConfiguration() {
        assertTrue(AgentRuntime.builder().build().commands().isEmpty());
        assertTrue(AgentRuntime.builder()
                .configuration(RuntimeConfiguration.disabled())
                .commandDispatcher(Runnable::run)
                .build().commands().isEmpty());
    }

    @Test
    void cancelledApplicationQueueEntryRetainsItsBoundedSlotUntilDrained() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        CommandDispatch commands = runtime(applicationQueue, new AtomicLong(1),
                new CommandDispatchLimits(1, 2, 2, 1_000, 642)).commands().orElseThrow();
        commands.submit("one", 100, () -> {});
        assertTrue(commands.cancel("one").accepted());

        assertEquals(CommandState.REJECTED,
                commands.submit("two", 100, () -> {}).status().orElseThrow().state());
        assertEquals(1, applicationQueue.size());
        applicationQueue.removeFirst().run();
        assertEquals(CommandState.QUEUED,
                commands.submit("three", 100, () -> {}).status().orElseThrow().state());
    }

    @Test
    @Timeout(10)
    void concurrentSubmissionsReachApplicationDispatcherInAcceptanceOrder() throws Exception {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        CountDownLatch firstDispatchEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstDispatch = new CountDownLatch(1);
        AtomicInteger dispatchCalls = new AtomicInteger();
        List<String> executions = new ArrayList<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("game"))
                .captureThread(Thread.currentThread())
                .clock(() -> 1)
                .commandDispatcher(task -> {
                    if (dispatchCalls.incrementAndGet() == 1) {
                        firstDispatchEntered.countDown();
                        try {
                            if (!releaseFirstDispatch.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test dispatch timed out");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("test dispatch interrupted", failure);
                        }
                    }
                    applicationQueue.addLast(task);
                })
                .build();
        CommandDispatch commands = runtime.commands().orElseThrow();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() ->
                    commands.submit("one", 100, () -> executions.add("one")));
            assertTrue(firstDispatchEntered.await(5, TimeUnit.SECONDS));
            commands.submit("two", 100, () -> executions.add("two"));
            releaseFirstDispatch.countDown();
            first.get();
        }

        applicationQueue.removeFirst().run();
        applicationQueue.removeFirst().run();
        assertEquals(List.of("one", "two"), executions);
    }

    @Test
    void rejectsDeadlinesBeyondConfiguredMaximum() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        CommandDispatch commands = runtime(applicationQueue, new AtomicLong(10),
                new CommandDispatchLimits(1, 2, 2, 100, 642)).commands().orElseThrow();

        assertThrows(IllegalArgumentException.class,
                () -> commands.submit("relative", Duration.ofDays(1), () -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> commands.submit("absolute", Long.MAX_VALUE, () -> {}));
        assertTrue(applicationQueue.isEmpty());
    }

    @Test
    void publicCommandResultsRejectInconsistentLifecycleEvidence() {
        CommandStatus succeeded = new CommandStatus(
                "done", CommandState.SUCCEEDED, 1, 10,
                Optional.of(2L), Optional.of(3L), true, Optional.empty(), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> new CommandCancellation(
                true, new CommandLookup(CommandLookup.Kind.FOUND, Optional.of(succeeded))));
        assertThrows(IllegalArgumentException.class, () -> new CommandStatus(
                "queued", CommandState.QUEUED, 1, 10,
                Optional.of(2L), Optional.empty(), false, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new CommandStatus(
                "unknown-timeout", CommandState.TIMED_OUT, 1, 10,
                Optional.empty(), Optional.empty(), false, Optional.empty(), Optional.empty()));
    }

    @Test
    void wrongThreadDispatcherFailsWithoutExecutingApplicationCommand() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("game"))
                .captureThread(Thread.currentThread())
                .clock(() -> 1)
                .commandDispatcher(Runnable::run)
                .build();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CommandLookup result = executor.submit(() -> runtime.commands().orElseThrow().submit(
                    "wrong-thread", 100, executions::incrementAndGet)).get();
            CommandStatus status = result.status().orElseThrow();
            assertEquals(CommandState.FAILED, status.state());
            assertTrue(status.diagnostic().orElseThrow().contains("capture thread"));
        }
        assertEquals(0, executions.get());
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
