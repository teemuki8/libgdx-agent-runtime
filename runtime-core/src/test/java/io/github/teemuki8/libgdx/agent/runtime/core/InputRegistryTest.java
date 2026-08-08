package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

final class InputRegistryTest {
    @Test
    void registeredInputsExecuteOnceInAcceptanceOrderAtTheTargetControlledTick() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        List<String> observed = new ArrayList<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("inputs"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .tick(deltaNanos -> {})
                .build());
        runtime.inputs().register(InputSpec.builder("keyboard")
                .description("Registered keyboard fact")
                .requiredString("key")
                .handler(parameters -> observed.add(parameters.requiredString("key")))
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        RuntimeValue.ObjectValue a = RuntimeValues.object(
                RuntimeValues.field("key", RuntimeValues.string("A")));
        RuntimeValue.ObjectValue b = RuntimeValues.object(
                RuntimeValues.field("key", RuntimeValues.string("B")));
        runtime.inputs().inject("keyboard", "input-a", a, OptionalLong.empty(),
                Duration.ofSeconds(1));
        runtime.inputs().inject("keyboard", "input-b", b, OptionalLong.of(1),
                Duration.ofSeconds(1));
        runtime.controls().advance("tick-1", 1, 16_666_667, Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        dispatch.removeFirst().run();
        dispatch.removeFirst().run();

        InputInjection first = runtime.inputs().inject(
                "keyboard", "input-a", a, OptionalLong.empty(), Duration.ofSeconds(1));
        InputInjection second = runtime.inputs().inject(
                "keyboard", "input-b", b, OptionalLong.of(1), Duration.ofSeconds(1));
        assertEquals(List.of("A", "B"), observed);
        assertEquals(InputInjectionState.EXECUTED, first.state());
        assertEquals(1, first.targetTick());
        assertEquals(OptionalLong.of(1), first.actualTick());
        assertEquals(new ExecutionEpochId(0), first.executionEpochId());
        assertEquals(new FrameId(0), first.submittedFrameId().orElseThrow());
        assertEquals(new FrameId(1), first.resultingFrameId().orElseThrow());
        assertEquals(a, first.recordedParameters().orElseThrow());
        assertEquals(InputInjectionState.EXECUTED, second.state());
    }

    @Test
    void rejectsInvalidTargetsBeforeExecutionAndRedactsRecordedParameters() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        int[] executions = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-bounds"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .inputLimits(new InputLimits(4, 4, 1, 4, 2, 32))
                .build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .tick(deltaNanos -> {})
                .build());
        runtime.inputs().register(InputSpec.builder("button")
                .requiredString("button")
                .redaction(InputRedactionPolicy.OMIT_PARAMETERS)
                .handler(parameters -> executions[0]++)
                .build());
        runtime.start();
        RuntimeValue.ObjectValue parameters = RuntimeValues.object(
                RuntimeValues.field("button", RuntimeValues.string("PRIMARY")));

        assertThrows(IllegalStateException.class, () -> runtime.inputs().inject(
                "button", "resumed", parameters, OptionalLong.empty(), Duration.ofSeconds(1)));
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        assertThrows(IllegalArgumentException.class, () -> runtime.inputs().inject(
                "button", "past", parameters, OptionalLong.of(0), Duration.ofSeconds(1)));
        assertThrows(AgentRuntimeException.class, () -> runtime.inputs().inject(
                "button", "distant", parameters, OptionalLong.of(3), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> runtime.inputs().inject(
                "button", "missing", RuntimeValues.object(), OptionalLong.of(1),
                Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> runtime.inputs().inject(
                "button", "unknown", RuntimeValues.object(
                        RuntimeValues.field("other", RuntimeValues.string("PRIMARY"))),
                OptionalLong.of(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> runtime.inputs().inject(
                "button", "wrong-type", RuntimeValues.object(
                        RuntimeValues.field("button", RuntimeValues.integer(1))),
                OptionalLong.of(1), Duration.ofSeconds(1)));

        runtime.inputs().inject(
                "button", "redacted", parameters, OptionalLong.of(1), Duration.ofSeconds(1));
        assertThrows(AgentRuntimeException.class, () -> runtime.inputs().inject(
                "button", "overflow", parameters, OptionalLong.of(1),
                Duration.ofSeconds(1)));
        runtime.controls().advance("tick", 1, 1, Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        dispatch.removeFirst().run();
        InputInjection redacted = runtime.inputs().inject(
                "button", "redacted", parameters, OptionalLong.of(1), Duration.ofSeconds(1));
        assertEquals(1, executions[0]);
        assertTrue(redacted.recordedParameters().isEmpty());
        assertEquals(true, redacted.parametersRedacted());
        assertThrows(IllegalArgumentException.class, () -> runtime.inputs().inject(
                "button", "changed", RuntimeValues.object(), OptionalLong.of(2),
                Duration.ofSeconds(1)));
    }

    @Test
    void terminalDispatchBeforeSchedulingFailsInputWithoutLeakingQueueCapacity() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-dispatch-rejection"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .commandDispatchLimits(new CommandDispatchLimits(1, 8, 8,
                        Duration.ofSeconds(1).toNanos(), 64))
                .inputLimits(new InputLimits(2, 2, 1, 4, 2, 64))
                .build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).tick(deltaNanos -> {}).build());
        runtime.inputs().register(InputSpec.builder("button")
                .requiredString("button").handler(parameters -> {}).build());
        runtime.start();
        runtime.controls().control(true, "pause-rejection", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        runtime.commands().orElseThrow().submit(
                "blocking-command", Duration.ofSeconds(1), () -> {});
        RuntimeValue.ObjectValue parameters = RuntimeValues.object(
                RuntimeValues.field("button", RuntimeValues.string("PRIMARY")));

        InputInjection first = runtime.inputs().inject(
                "button", "rejected-input-1", parameters, OptionalLong.empty(),
                Duration.ofSeconds(1));
        InputInjection second = runtime.inputs().inject(
                "button", "rejected-input-2", parameters, OptionalLong.empty(),
                Duration.ofSeconds(1));

        assertEquals(InputInjectionState.FAILED, first.state());
        assertEquals(CommandState.REJECTED,
                first.command().status().orElseThrow().state());
        assertEquals(InputInjectionState.FAILED, second.state());
    }

    @Test
    void callbackFailureStillReportsTheCompletedResultingFrame() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-failed-tick"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {})
                .tick(deltaNanos -> {
                    throw new IllegalStateException("tick failed");
                }).build());
        RuntimeValue.ObjectValue parameters = RuntimeValues.object(
                RuntimeValues.field("button", RuntimeValues.string("PRIMARY")));
        runtime.inputs().register(InputSpec.builder("button")
                .requiredString("button").handler(ignored -> {}).build());
        runtime.start();
        runtime.controls().control(true, "pause-failed-tick", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        runtime.inputs().inject("button", "failed-tick-input", parameters,
                OptionalLong.empty(), Duration.ofSeconds(1));
        runtime.controls().advance("failed-tick", 1, 1, Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        dispatch.removeFirst().run();

        InputInjection completed = runtime.inputs().inject(
                "button", "failed-tick-input", parameters,
                OptionalLong.empty(), Duration.ofSeconds(1));
        assertEquals(InputInjectionState.EXECUTED, completed.state());
        assertEquals(new FrameId(1), completed.resultingFrameId().orElseThrow());
        assertTrue(completed.diagnostic().isEmpty());
        assertTrue(runtime.frame(new FrameId(1)).isPresent());
    }

    @Test
    void failedInputHandlerEvidenceOmitsRawApplicationMessages() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-leak"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .tick(deltaNanos -> {})
                .build());
        runtime.inputs().register(InputSpec.builder("keyboard")
                .requiredString("key")
                .handler(parameters -> {
                    throw new IllegalStateException("token=secret-123 /home/private/save.dat");
                })
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        RuntimeValue.ObjectValue parameters = RuntimeValues.object(
                RuntimeValues.field("key", RuntimeValues.string("A")));
        runtime.inputs().inject("keyboard", "input-leak", parameters,
                OptionalLong.empty(), Duration.ofSeconds(1));
        runtime.controls().advance("tick-leak", 1, 1, Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        dispatch.removeFirst().run();

        InputInjection failed = runtime.inputs().inject(
                "keyboard", "input-leak", parameters,
                OptionalLong.empty(), Duration.ofSeconds(1));
        assertEquals(InputInjectionState.FAILED, failed.state());
        String diagnostic = failed.diagnostic().orElseThrow();
        assertFalse(diagnostic.contains("token=secret-123"));
        assertFalse(diagnostic.contains("/home/private/save.dat"));
        assertTrue(diagnostic.contains("input.execution"));
        assertTrue(diagnostic.contains("failure-1"));
    }
}
