package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SimulationControlTest {
    @Test
    void closeReleasesCallbacksAndPendingOperationsButRetainsConditionMetadata() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        int[] ticks = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("control-close"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .tick(deltaNanos -> ticks[0]++)
                .condition("ready", "Ready state", () -> false)
                .build());
        runtime.start();
        runtime.controls().control(true, "pause-1", Duration.ofSeconds(1));
        assertEquals(1, runtime.controls().retainedControlOperations());

        runtime.close();

        assertEquals(List.of("ready"),
                runtime.controls().conditions().stream()
                        .map(ControlConditionDescriptor::id).toList());
        assertEquals(0, runtime.controls().retainedControlCallbacks());
        assertEquals(0, runtime.controls().retainedControlOperations());
        queue.forEach(Runnable::run);
        assertEquals(0, ticks[0]);
        AgentRuntimeException closed = assertThrows(AgentRuntimeException.class,
                () -> runtime.controls().control(true, "pause-2",
                        Duration.ofSeconds(1)));
        assertEquals(RuntimeErrorCode.RUNTIME_CLOSED, closed.code());
        AgentRuntimeException advanced = assertThrows(AgentRuntimeException.class,
                () -> runtime.controls().advance(
                        "advance-2", 1, 1, Duration.ofSeconds(1)));
        assertEquals(RuntimeErrorCode.RUNTIME_CLOSED, advanced.code());
    }

    @Test
    void pausesAdvancesExactTicksAndStopsBoundedConditionWaitWithoutSleeping() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        boolean[] applicationPaused = {false};
        int[] ticks = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("game"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> applicationPaused[0] = true)
                .resume(() -> applicationPaused[0] = false)
                .tick(deltaNanos -> ticks[0]++)
                .condition("three-ticks", "Three ticks completed", () -> ticks[0] >= 3)
                .build());
        runtime.start();

        ControlOperation pause = runtime.controls().control(
                true, "pause-1", Duration.ofSeconds(1));
        assertEquals(CommandState.QUEUED, pause.command().status().orElseThrow().state());
        queue.removeFirst().run();
        assertEquals(true, applicationPaused[0]);
        assertEquals(true, runtime.controls().paused());
        assertEquals(new FrameId(0), runtime.latestFrame().orElseThrow().frameId());

        ControlOperation advance = runtime.controls().advance(
                "advance-1", 2, 16_666_667, Duration.ofSeconds(1));
        queue.removeFirst().run();
        advance = runtime.controls().advance(
                "advance-1", 2, 16_666_667, Duration.ofSeconds(1));
        assertEquals(CommandState.SUCCEEDED, advance.command().status().orElseThrow().state());
        assertEquals(2, advance.completedTicks());
        assertEquals(Optional.of(new FrameId(1)), advance.firstFrameId());
        assertEquals(Optional.of(new FrameId(2)), advance.finalFrameId());
        assertEquals(16_666_667, runtime.latestFrame().orElseThrow().deltaNanos());

        ControlOperation wait = runtime.controls().waitForCondition(
                "wait-1", "three-ticks", 5, 16_666_667, Duration.ofSeconds(1));
        queue.removeFirst().run();
        wait = runtime.controls().waitForCondition(
                "wait-1", "three-ticks", 5, 16_666_667, Duration.ofSeconds(1));
        assertEquals(ControlStopReason.CONDITION_SATISFIED, wait.stopReason());
        assertEquals(1, wait.completedTicks());
        assertEquals(3, ticks[0]);
        assertEquals(new FrameId(3), runtime.latestFrame().orElseThrow().frameId());

        runtime.controls().control(false, "resume-1", Duration.ofSeconds(1));
        queue.removeFirst().run();
        assertEquals(false, applicationPaused[0]);
        runtime.controls().advance("invalid", 1, 1, Duration.ofSeconds(1));
        queue.removeFirst().run();
        ControlOperation invalid = runtime.controls().advance(
                "invalid", 1, 1, Duration.ofSeconds(1));
        assertEquals(ControlStopReason.INVALID_STATE, invalid.stopReason());
        assertEquals(0, invalid.completedTicks());
    }

    @Test
    void reportsPartialAdvancementAndCallbackFailureWithBoundedEvidence() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        int[] ticks = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("failure"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .tick(deltaNanos -> {
                    ticks[0]++;
                    if (ticks[0] == 2) {
                        throw new IllegalStateException("tick failed");
                    }
                })
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        queue.removeFirst().run();

        runtime.controls().advance("advance", 3, 1, Duration.ofSeconds(1));
        queue.removeFirst().run();
        ControlOperation result = runtime.controls().advance(
                "advance", 3, 1, Duration.ofSeconds(1));

        assertEquals(CommandState.FAILED, result.command().status().orElseThrow().state());
        assertEquals(ControlStopReason.CALLBACK_FAILED, result.stopReason());
        assertEquals(1, result.completedTicks());
        assertEquals(Optional.of(new FrameId(1)), result.firstFrameId());
        assertEquals(Optional.of(new FrameId(1)), result.finalFrameId());
        assertEquals(2, ticks[0]);
    }

    @Test
    void assertionWaitUsesCompletedFramesAndTimeoutRetainsPartialProgress() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] time = {0};
        int[] value = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("assertion-wait"))
                .clock(() -> time[0])
                .commandDispatcher(queue::addLast)
                .build();
        runtime.entities().register(EntityId.of("counter"), EntityType.of("counter"),
                () -> "Counter", inspector -> inspector.property(
                        "value", () -> RuntimeValues.integer(value[0])));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .tick(deltaNanos -> {
                    value[0]++;
                    if (value[0] > 2) {
                        time[0] = 5;
                    }
                })
                .build());
        runtime.start();
        runtime.controls().control(true, "pause-assertion", Duration.ofNanos(100));
        queue.removeFirst().run();

        RuntimeAssertion assertion = new RuntimeAssertion.PropertyEquals(
                EntityId.of("counter"), "value", RuntimeValues.integer(2));
        runtime.controls().waitForAssertion(
                "wait-assertion", assertion, 3, 1, 4, Duration.ofNanos(100));
        queue.removeFirst().run();
        ControlOperation satisfied = runtime.controls().waitForAssertion(
                "wait-assertion", assertion, 3, 1, 4, Duration.ofNanos(100));
        assertEquals(ControlStopReason.ASSERTION_SATISFIED, satisfied.stopReason());
        assertEquals(2, satisfied.completedTicks());
        assertEquals(AssertionStatus.PASS,
                satisfied.assertionResult().orElseThrow().status());

        runtime.controls().advance("partial-timeout", 3, 1, Duration.ofNanos(5));
        time[0] = 4;
        queue.removeFirst().run();
        ControlOperation timedOut = runtime.controls().advance(
                "partial-timeout", 3, 1, Duration.ofNanos(5));
        assertEquals(ControlStopReason.TIMED_OUT, timedOut.stopReason());
        assertEquals(1, timedOut.completedTicks());
        assertEquals(Optional.of(new FrameId(3)), timedOut.finalFrameId());
    }

    @Test
    void unavailableAndBoundsFailuresAreExplicitBeforeDispatch() {
        AgentRuntime unavailable = AgentRuntime.builder()
                .sessionId(SessionId.of("unavailable"))
                .build();
        unavailable.start();
        assertEquals(false, unavailable.controls().available());
        assertThrows(IllegalStateException.class, () -> unavailable.controls().control(
                true, "pause", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ControlLimits(
                0, 10, 10, 1_000_000_000));
    }
}
