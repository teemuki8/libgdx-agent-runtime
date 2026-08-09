package io.github.teemuki8.libgdx.agent.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepDropPolicy;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepSimulationConfiguration;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class FixedStepProtocolTest {
    @Test
    void protocolTwoTwoInspectsUpdatesAndAdvancesWithoutCallerSelectedDelta() {
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = configuredRuntime("fixed-step-protocol");
        runtime.start();
        runtime.fixedStepSimulation().update(5);
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        registry.publish(runtime);
        RuntimeProtocolService service = new RuntimeProtocolService(registry);

        RuntimeResponse.Result.FixedStep state = assertInstanceOf(
                RuntimeResponse.Result.FixedStep.class,
                success(service.execute(new RuntimeRequest(ProtocolVersion.V2_2, "state",
                        "fixed-step-protocol", new RuntimeCommand.FixedStep()))).result());
        assertEquals(5, state.state().accumulatorRemainderNanos());

        RuntimeResponse.Result.FixedStepUpdates updates = assertInstanceOf(
                RuntimeResponse.Result.FixedStepUpdates.class,
                success(service.execute(new RuntimeRequest(ProtocolVersion.V2_2, "updates",
                        "fixed-step-protocol",
                        new RuntimeCommand.FixedStepUpdates(1, 1, 10)))).result());
        assertEquals(1, updates.page().reports().size());

        RuntimeResponse.Result.Control advance = assertInstanceOf(
                RuntimeResponse.Result.Control.class,
                success(service.execute(new RuntimeRequest(ProtocolVersion.V2_2, "advance",
                        "fixed-step-protocol", new RuntimeCommand.SimulationAdvance(
                                "fixed-advance", 2, Duration.ofSeconds(1).toNanos())))).result());
        assertEquals(2, advance.operation().orElseThrow().completedTicks());
        assertEquals(5, runtime.fixedStepSimulation().state().accumulatorRemainderNanos());
    }

    @Test
    void priorVersionRejectsCommandsAndTwoTwoInputsRemainClosed() {
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = configuredRuntime("fixed-step-version");
        runtime.start();
        registry.publish(runtime);
        RuntimeProtocolService service = new RuntimeProtocolService(registry);

        RuntimeResponse.Failure old = assertInstanceOf(RuntimeResponse.Failure.class,
                service.execute(new RuntimeRequest(ProtocolVersion.V2_1, "old",
                        "fixed-step-version", new RuntimeCommand.FixedStep())));
        assertEquals(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED, old.error().code());

        String unknown = """
                {"version":{"major":2,"minor":2},"requestId":"unknown",
                 "sessionId":"fixed-step-version","command":{"type":"simulationAdvance",
                 "controlRequestId":"advance","ticks":1,"timeoutNanos":1000,
                 "deltaNanos":10}}
                """;
        assertThrows(ProtocolJson.ProtocolJsonException.class,
                () -> ProtocolJson.decodeRequest(unknown.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void capabilitiesCarryForwardTimelineAndAdvertiseFixedStepToolsInTwoTwo() {
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = configuredRuntime("fixed-step-capability");
        runtime.start();
        registry.publish(runtime);
        RuntimeProtocolService service = new RuntimeProtocolService(registry);

        RuntimeResponse.Result.Capabilities capabilities = assertInstanceOf(
                RuntimeResponse.Result.Capabilities.class,
                success(service.execute(new RuntimeRequest(ProtocolVersion.V2_2, "capabilities",
                        "fixed-step-capability", new RuntimeCommand.Capabilities()))).result());

        assertTrue(capabilities.supportedTools().contains("runtime_simulation"));
        assertTrue(capabilities.supportedTools().contains("runtime_fixed_step"));
        assertTrue(capabilities.supportedTools().contains("runtime_fixed_step_updates"));
        assertTrue(capabilities.supportedTools().contains("runtime_simulation_advance"));
        assertTrue(capabilities.capabilityReport().orElseThrow().capabilities().stream()
                .anyMatch(capability -> capability.id().equals("fixed-step-simulation")));
    }

    private static AgentRuntime configuredRuntime(String sessionId) {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of(sessionId))
                .clock(new IncrementingClock())
                .commandDispatcher(Runnable::run)
                .build();
        runtime.fixedStepSimulation().register(new FixedStepSimulationConfiguration(
                10, 20, 20, 2, 10,
                FixedStepDropPolicy.DROP_WHOLE_TICKS_KEEP_REMAINDER, true), supplied -> supplied);
        return runtime;
    }

    private static RuntimeResponse.Success success(RuntimeResponse response) {
        return assertInstanceOf(RuntimeResponse.Success.class, response);
    }

    private static final class IncrementingClock
            implements io.github.teemuki8.libgdx.agent.runtime.core.MonotonicClock {
        private long value;

        @Override
        public long nanoTime() {
            return ++value;
        }
    }
}
