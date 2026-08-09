package io.github.teemuki8.libgdx.agent.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTickOutcome;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTimelineSpec;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class SimulationProtocolTest {
    @Test
    void protocolTwoOneExposesStateAndBoundedTickFrameCorrelation() {
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("simulation-protocol"))
                .clock(() -> 1)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(10));
        runtime.start();
        runtime.simulation().tick(10, supplied -> supplied);
        registry.publish(runtime);
        RuntimeProtocolService service = new RuntimeProtocolService(registry);

        RuntimeResponse.Result.Simulation state = assertInstanceOf(
                RuntimeResponse.Result.Simulation.class,
                success(service.execute(new RuntimeRequest(ProtocolVersion.V2_1, "state",
                        "simulation-protocol", new RuntimeCommand.Simulation()))).result());
        assertEquals(10, state.state().configuredFixedStepNanos().orElseThrow());
        assertEquals(1, state.state().latestTickId().orElseThrow().value());

        RuntimeResponse.Result.SimulationTicks ticks = assertInstanceOf(
                RuntimeResponse.Result.SimulationTicks.class,
                success(service.execute(new RuntimeRequest(ProtocolVersion.V2_1, "ticks",
                        "simulation-protocol", new RuntimeCommand.SimulationTicks(
                                0, 1, 1, 10)))).result());
        assertEquals(1, ticks.page().ticks().size());
        assertEquals(1, ticks.page().ticks().getFirst().resultingFrameId().orElseThrow().value());
        assertEquals(SimulationTickOutcome.COMPLETED,
                ticks.page().ticks().getFirst().outcome());

        RuntimeResponse decoded = ProtocolJson.decodeResponse(ProtocolJson.encode(
                success(service.execute(new RuntimeRequest(ProtocolVersion.V2_1, "roundtrip",
                        "simulation-protocol", new RuntimeCommand.SimulationTicks(
                                0, 1, 1, 10))))));
        assertInstanceOf(RuntimeResponse.Result.SimulationTicks.class,
                assertInstanceOf(RuntimeResponse.Success.class, decoded).result());
    }

    @Test
    void protocolTwoZeroCannotUseTwoOneCommandsAndExactUnknownFieldsAreRejected() {
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("simulation-version"))
                .build();
        runtime.start();
        registry.publish(runtime);
        RuntimeProtocolService service = new RuntimeProtocolService(registry);

        RuntimeResponse.Failure old = assertInstanceOf(RuntimeResponse.Failure.class,
                service.execute(new RuntimeRequest(ProtocolVersion.V2, "old",
                        "simulation-version", new RuntimeCommand.Simulation())));
        assertEquals(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED, old.error().code());
        assertEquals(ProtocolVersion.V2, old.version());

        RuntimeResponse.Failure future = assertInstanceOf(RuntimeResponse.Failure.class,
                service.execute(new RuntimeRequest(new ProtocolVersion(2, 3), "future",
                        "simulation-version", new RuntimeCommand.Simulation())));
        assertEquals(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED, future.error().code());
        assertEquals(ProtocolVersion.V2_2, future.version());

        String unknown = """
                {"version":{"major":2,"minor":1},"requestId":"unknown",
                 "sessionId":"simulation-version","command":{"type":"simulation",
                 "unknown":true}}
                """;
        assertThrows(ProtocolJson.ProtocolJsonException.class,
                () -> ProtocolJson.decodeRequest(unknown.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void capabilitiesAdvertiseTimelineOnlyInProtocolTwoOne() {
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("simulation-capability"))
                .build();
        runtime.start();
        registry.publish(runtime);
        RuntimeProtocolService service = new RuntimeProtocolService(registry);

        RuntimeResponse.Result.Capabilities v2 = capabilities(service, ProtocolVersion.V2, "v2");
        assertEquals(false, v2.supportedTools().contains("runtime_simulation"));

        RuntimeResponse.Result.Capabilities v21 = capabilities(
                service, ProtocolVersion.V2_1, "v21");
        assertTrue(v21.supportedTools().contains("runtime_simulation"));
        assertTrue(v21.supportedTools().contains("runtime_simulation_ticks"));
        RuntimeCapability capability = v21.capabilityReport().orElseThrow().capabilities().stream()
                .filter(value -> value.id().equals("simulation-timeline"))
                .findFirst().orElseThrow();
        assertEquals(ProtocolVersion.V2_1, capability.capabilityVersion());
        assertEquals(10_000L, capability.limits().get("retainedTicks"));
    }

    private static RuntimeResponse.Result.Capabilities capabilities(
            RuntimeProtocolService service, ProtocolVersion version, String requestId) {
        return assertInstanceOf(RuntimeResponse.Result.Capabilities.class,
                success(service.execute(new RuntimeRequest(version, requestId,
                        "simulation-capability", new RuntimeCommand.Capabilities()))).result());
    }

    private static RuntimeResponse.Success success(RuntimeResponse response) {
        return assertInstanceOf(RuntimeResponse.Success.class, response);
    }
}
