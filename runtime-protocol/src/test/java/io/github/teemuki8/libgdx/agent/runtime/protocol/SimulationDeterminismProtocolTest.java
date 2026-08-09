package io.github.teemuki8.libgdx.agent.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismProfile;
import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.ScenarioDescriptor;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationConfigurationRequirement;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationControllerSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationDeterminismInput;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationDeterminismSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationEvidenceRequirement;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTimelineSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SnapshotComparisonScope;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SimulationDeterminismProtocolTest {
    @Test
    void protocolTwoFourRoundTripsAndExecutesClosedTickAwareRequest() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("simulation-determinism-protocol"))
                .clock(() -> 1).commandDispatcher(queue::addLast).build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(16));
        runtime.entities().register(EntityId.of("world"), EntityType.of("physics"),
                () -> "world", inspector -> inspector.property("step", () -> 16L));
        runtime.entities().register(EntityId.of("body"), EntityType.of("physics"),
                () -> "body", inspector -> inspector.property("position", () -> position[0]));
        runtime.entities().register(EntityId.of("contacts"), EntityType.of("physics"),
                () -> "contacts", inspector -> inspector.property("complete", () -> true));
        runtime.inputs().register(io.github.teemuki8.libgdx.agent.runtime.core.InputSpec
                .builder("move").requiredInteger("amount")
                .handler(parameters -> position[0] += parameters.requiredInteger("amount"))
                .build());
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {}).resume(() -> {}).acknowledgedTick(delta -> delta).build());
        runtime.scenarios().register(new ScenarioDescriptor("move", Optional.empty()),
                context -> position[0] = 0);
        runtime.start();
        registry.publish(runtime);
        RuntimeProtocolService service = new RuntimeProtocolService(registry);
        RuntimeResponse.Result.Capabilities capabilities = assertInstanceOf(
                RuntimeResponse.Result.Capabilities.class,
                success(service.execute(new RuntimeRequest(ProtocolVersion.V2_4, "capabilities",
                        "simulation-determinism-protocol",
                        new RuntimeCommand.Capabilities()))).result());
        RuntimeCapability capability = capabilities.capabilityReport().orElseThrow()
                .capabilities().stream()
                .filter(value -> value.id().equals("simulation-determinism"))
                .findFirst().orElseThrow();
        assertEquals(ProtocolVersion.V2_4, capability.capabilityVersion());
        assertTrue(capabilities.supportedTools()
                .contains("runtime_simulation_determinism_check"));
        RuntimeCommand.SimulationDeterminismCheck command =
                new RuntimeCommand.SimulationDeterminismCheck(
                        "physics-determinism", spec(), Duration.ofSeconds(1).toNanos());

        RuntimeRequest request = new RuntimeRequest(ProtocolVersion.V2_4, "submit",
                "simulation-determinism-protocol", command);
        RuntimeRequest decoded = ProtocolJson.decodeRequest(ProtocolJson.encode(request));
        assertEquals(request, decoded);
        RuntimeResponse.Result.SimulationDeterminism queued = assertInstanceOf(
                RuntimeResponse.Result.SimulationDeterminism.class,
                success(service.execute(decoded)).result());
        assertTrue(queued.operation().result().isEmpty());
        queue.removeFirst().run();
        RuntimeResponse.Result.SimulationDeterminism completed = assertInstanceOf(
                RuntimeResponse.Result.SimulationDeterminism.class,
                success(service.execute(new RuntimeRequest(ProtocolVersion.V2_4, "poll",
                        "simulation-determinism-protocol", command))).result());

        assertEquals(DeterminismStatus.EQUAL,
                completed.operation().result().orElseThrow().status());
        RuntimeResponse roundTrip = ProtocolJson.decodeResponse(ProtocolJson.encode(
                success(service.execute(new RuntimeRequest(ProtocolVersion.V2_4, "encoded",
                        "simulation-determinism-protocol", command)))));
        assertInstanceOf(RuntimeResponse.Result.SimulationDeterminism.class,
                assertInstanceOf(RuntimeResponse.Success.class, roundTrip).result());
    }

    @Test
    void protocolTwoThreeAndUnknownNestedFieldsAreRejected() {
        RuntimeRegistry registry = new RuntimeRegistry();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("simulation-determinism-version")).build();
        runtime.start();
        registry.publish(runtime);
        RuntimeProtocolService service = new RuntimeProtocolService(registry);
        RuntimeResponse.Failure old = assertInstanceOf(RuntimeResponse.Failure.class,
                service.execute(new RuntimeRequest(ProtocolVersion.V2_3, "old",
                        "simulation-determinism-version",
                        new RuntimeCommand.SimulationDeterminismCheck(
                                "request", spec(), 1))));
        assertEquals(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED, old.error().code());
        assertEquals("command requires protocol version 2.4", old.error().message());

        String encoded = new String(ProtocolJson.encode(new RuntimeRequest(
                ProtocolVersion.V2_4, "closed", "simulation-determinism-version",
                new RuntimeCommand.SimulationDeterminismCheck("request", spec(), 1))),
                StandardCharsets.UTF_8);
        String unknown = encoded.replace("\"epochTick\":1",
                "\"epochTick\":1,\"unknown\":true");
        assertThrows(ProtocolJson.ProtocolJsonException.class,
                () -> ProtocolJson.decodeRequest(unknown.getBytes(StandardCharsets.UTF_8)));
    }

    private static SimulationDeterminismSpec spec() {
        return new SimulationDeterminismSpec(new DeterminismSpec(
                "move", 7, RuntimeValues.object(), 2, 2, 16,
                new DeterminismProfile(new SnapshotComparisonScope(
                        List.of(EntityId.of("body")), List.of("position"), List.of(),
                        false, false), false)),
                List.of(new SimulationDeterminismInput(1, "move", RuntimeValues.object(
                        RuntimeValues.field("amount", RuntimeValues.integer(1))))),
                List.of(new SimulationConfigurationRequirement(
                        EntityId.of("world"), "step", RuntimeValues.integer(16))),
                List.of(new SimulationEvidenceRequirement(
                        EntityId.of("contacts"), "complete")), List.of());
    }

    private static RuntimeResponse.Success success(RuntimeResponse response) {
        return assertInstanceOf(RuntimeResponse.Success.class, response);
    }
}
