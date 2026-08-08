package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ScenarioRegistryTest {
    @Test
    void explicitlyRegistersListsAndResetsToANewBaseline() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicInteger resets = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("game"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.scenarios().register("basic-combat", "Known combat state", resets::incrementAndGet);
        runtime.start();

        ScenarioReset queued = runtime.scenarios().reset(
                "basic-combat", "reset-1", Duration.ofSeconds(1));
        assertEquals(CommandState.QUEUED, queued.command().status().orElseThrow().state());
        assertTrue(queued.baselineFrameId().isEmpty());
        queue.removeFirst().run();

        ScenarioReset completed = runtime.scenarios().reset(
                "basic-combat", "reset-1", Duration.ofSeconds(1));
        assertEquals(CommandState.SUCCEEDED, completed.command().status().orElseThrow().state());
        assertEquals(1, resets.get());
        assertEquals(Optional.of(new ExecutionEpochId(1)), completed.executionEpochId());
        assertEquals(Optional.of(new FrameId(1)), completed.baselineFrameId());
        assertEquals(BaselineKind.SCENARIO_RESET,
                runtime.latestFrame().orElseThrow().baselineKind().orElseThrow());
        assertEquals("Known combat state",
                runtime.scenarios().list().getFirst().description().orElseThrow());
    }

    @Test
    void closeReleasesHandlersAndPendingRequestsButRetainsTheCatalog() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicInteger resets = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("scenario-close"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.scenarios().register("basic-combat", "Known combat state", resets::incrementAndGet);
        runtime.start();
        runtime.scenarios().reset("basic-combat", "reset-1", Duration.ofSeconds(1));
        assertEquals(1, runtime.scenarios().retainedPendingResets());

        runtime.close();

        assertEquals(List.of("basic-combat"), runtime.scenarios().list().stream()
                .map(ScenarioDescriptor::id).toList());
        assertEquals(0, runtime.scenarios().retainedResetCallbacks());
        assertEquals(0, runtime.scenarios().retainedPendingResets());
        queue.forEach(Runnable::run);
        assertEquals(0, resets.get());
        AgentRuntimeException closed = assertThrows(AgentRuntimeException.class,
                () -> runtime.scenarios().reset("basic-combat", "reset-2",
                        Duration.ofSeconds(1)));
        assertEquals(RuntimeErrorCode.RUNTIME_CLOSED, closed.code());
    }

    @Test
    void rejectsDuplicateUnknownAndCrossScenarioRequestReuse() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .clock(() -> 1).commandDispatcher(queue::addLast).build();
        runtime.scenarios().register("one", () -> {});
        runtime.scenarios().register("two", () -> {});
        assertThrows(IllegalArgumentException.class,
                () -> runtime.scenarios().register("one", () -> {}));
        runtime.start();
        assertThrows(IllegalArgumentException.class, () -> runtime.scenarios().reset(
                "missing", "request", Duration.ofSeconds(1)));
        runtime.scenarios().reset("one", "request", Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> runtime.scenarios().reset(
                "two", "request", Duration.ofSeconds(1)));
    }

    @Test
    void rejectsResetBeforeCallbackWhenAFrameIsOpen() {
        AtomicInteger resets = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .clock(() -> 1).commandDispatcher(Runnable::run).build();
        runtime.scenarios().register("one", resets::incrementAndGet);
        runtime.start();
        runtime.beginFrame(1);

        ScenarioReset reset = runtime.scenarios().reset(
                "one", "request", Duration.ofSeconds(1));

        assertEquals(CommandState.FAILED, reset.command().status().orElseThrow().state());
        assertEquals(0, resets.get());
        assertTrue(reset.baselineFrameId().isEmpty());
        runtime.endFrame();
    }

    @Test
    void retainedScenarioEvidenceNeverRedispatchesAnExpiredCommand() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicInteger resets = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder().clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .commandDispatchLimits(new CommandDispatchLimits(2, 1, 1, 1_000, 642))
                .scenarioLimits(new ScenarioLimits(2, 2)).build();
        runtime.scenarios().register("one", resets::incrementAndGet);
        runtime.start();
        runtime.scenarios().reset("one", "first", Duration.ofNanos(100));
        queue.removeFirst().run();
        runtime.commands().orElseThrow().submit("second", 100, () -> {});
        queue.removeFirst().run();

        ScenarioReset retry = runtime.scenarios().reset(
                "one", "first", Duration.ofNanos(100));

        assertEquals(CommandLookup.Kind.EXPIRED, retry.command().kind());
        assertEquals(1, resets.get());
        assertTrue(queue.isEmpty());
    }

    @Test
    void invalidTimeoutDoesNotPoisonARequestId() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder().clock(() -> 1)
                .commandDispatcher(queue::addLast).build();
        runtime.scenarios().register("one", () -> {});
        runtime.start();

        assertThrows(IllegalArgumentException.class, () -> runtime.scenarios().reset(
                "one", "retryable", Duration.ZERO));
        ScenarioReset retry = runtime.scenarios().reset(
                "one", "retryable", Duration.ofNanos(100));

        assertEquals(CommandState.QUEUED, retry.command().status().orElseThrow().state());
        assertEquals(1, queue.size());
    }
}
