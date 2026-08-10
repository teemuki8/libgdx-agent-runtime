package io.github.teemuki8.libgdx.agent.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.InputTimelineResult;
import io.github.teemuki8.libgdx.agent.runtime.core.InputTimelineStopReason;
import io.github.teemuki8.libgdx.agent.runtime.core.InputTimelineTransition;
import io.github.teemuki8.libgdx.agent.runtime.core.InputTimelineTransitionState;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationControllerSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTimelineSpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

final class InputTimelineProtocolTest {
    @Test
    void protocolTwoSixRoundTripsAndExecutesInputTimeline() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        List<String> observed = new java.util.ArrayList<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-timeline-protocol"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(10));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(deltaNanos -> deltaNanos)
                .build());
        runtime.inputs().register(io.github.teemuki8.libgdx.agent.runtime.core.InputSpec
                .builder("button")
                .requiredBoolean("active")
                .handler(parameters -> observed.add(
                        "active:" + parameters.requiredBoolean("active")))
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        queue.removeFirst().run();
        RuntimeRegistry registry = new RuntimeRegistry();
        registry.publish(runtime);
        RuntimeProtocolService service = new RuntimeProtocolService(registry);

        RuntimeCommand.InputTimeline command = new RuntimeCommand.InputTimeline(
                "protocol-timeline", 2, List.of(
                        new InputTimelineTransition("protocol-press", 1, "button",
                                RuntimeValues.object(RuntimeValues.field(
                                        "active", RuntimeValues.bool(true)))),
                        new InputTimelineTransition("protocol-release", 2, "button",
                                RuntimeValues.object(RuntimeValues.field(
                                        "active", RuntimeValues.bool(false))))),
                Duration.ofSeconds(2).toNanos());
        RuntimeRequest request = new RuntimeRequest(
                ProtocolVersion.V2_6, "request", "input-timeline-protocol", command);
        assertEquals(request, ProtocolJson.decodeRequest(ProtocolJson.encode(request)));

        RuntimeResponse.Result.InputTimeline queued = assertInstanceOf(
                RuntimeResponse.Result.InputTimeline.class,
                success(service.execute(request)).result());
        assertTrue(queued.operation().result().isEmpty());
        queue.removeFirst().run();

        RuntimeResponse poll = service.execute(request);
        RuntimeResponse.Result.InputTimeline completed = assertInstanceOf(
                RuntimeResponse.Result.InputTimeline.class,
                assertInstanceOf(RuntimeResponse.Success.class, poll).result());
        InputTimelineResult result = completed.operation().result().orElseThrow();
        assertEquals(InputTimelineStopReason.COMPLETED, result.stopReason());
        assertEquals(2, result.bounds().completedTicks());
        assertEquals(List.of("active:true", "active:false"), observed);
        assertEquals(List.of("protocol-press", "protocol-release"), result.transitions().stream()
                .map(value -> value.transitionId()).toList());
        assertTrue(result.transitions().stream().allMatch(value ->
                value.state() == InputTimelineTransitionState.EXECUTED));
    }

    @Test
    void protocolTwoSixRejectsUnknownFieldsAndLegacyVersionsBeforeMutation() {
        assertThrows(ProtocolJson.ProtocolJsonException.class, () ->
                ProtocolJson.decodeRequest("""
                        {"version":{"major":2,"minor":6},"requestId":"x","sessionId":"s",
                         "command":{"type":"inputTimeline","timelineRequestId":"t","totalTicks":1,
                          "transitions":[{"transitionId":"one","timelineTick":1,"inputId":"button",
                          "parameters":{"active":true}}],"timeoutNanos":1000000000,
                          "unknownField":true}}}"""
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(ProtocolJson.ProtocolJsonException.class, () ->
                ProtocolJson.decodeRequest("""
                        {"version":{"major":2,"minor":6},"requestId":"x","sessionId":"s",
                         "command":{"type":"inputTimeline","timelineRequestId":"t","totalTicks":1,
                          "transitions":[{"transitionId":"one","timelineTick":1,"inputId":"button",
                          "parameters":{"active":true},"unknownTransitionField":true}],
                          "timeoutNanos":1000000000}}}"""
                        .getBytes(StandardCharsets.UTF_8)));

        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        int[] handlerCalls = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-timeline-legacy"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(10));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(deltaNanos -> deltaNanos)
                .build());
        runtime.inputs().register(io.github.teemuki8.libgdx.agent.runtime.core.InputSpec
                .builder("button")
                .requiredBoolean("active")
                .handler(parameters -> handlerCalls[0]++)
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        queue.removeFirst().run();
        RuntimeRegistry registry = new RuntimeRegistry();
        registry.publish(runtime);
        RuntimeProtocolService service = new RuntimeProtocolService(registry);

        RuntimeCommand.InputTimeline command = new RuntimeCommand.InputTimeline(
                "legacy-timeline", 1, List.of(new InputTimelineTransition(
                        "legacy-press", 1, "button", RuntimeValues.object(
                                RuntimeValues.field("active", RuntimeValues.bool(true))))),
                Duration.ofSeconds(1).toNanos());
        RuntimeResponse.Failure rejected = assertInstanceOf(RuntimeResponse.Failure.class,
                service.execute(new RuntimeRequest(
                        ProtocolVersion.V2_5, "legacy", "input-timeline-legacy", command)));
        assertEquals(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED, rejected.error().code());
        assertEquals("command requires protocol version 2.6", rejected.error().message());
        assertEquals(0, handlerCalls[0]);
        assertTrue(queue.isEmpty());
    }

    @Test
    void protocolTwoSixCapabilitiesAdvertiseInputTimelinesWithEffectiveLimits() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] position = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-timeline-capabilities"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(10));
        runtime.entities().register(io.github.teemuki8.libgdx.agent.runtime.core.EntityId.of("world"),
                io.github.teemuki8.libgdx.agent.runtime.core.EntityType.of("state"),
                () -> "world", inspector -> inspector.property("position", () -> position[0]));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(deltaNanos -> deltaNanos)
                .build());
        runtime.inputs().register(io.github.teemuki8.libgdx.agent.runtime.core.InputSpec
                .builder("button")
                .requiredBoolean("active")
                .handler(parameters -> position[0]++)
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        queue.removeFirst().run();
        RuntimeRegistry registry = new RuntimeRegistry();
        registry.publish(runtime);
        RuntimeProtocolService service = new RuntimeProtocolService(registry);

        RuntimeResponse.Result.Capabilities capabilities = assertInstanceOf(
                RuntimeResponse.Result.Capabilities.class,
                success(service.execute(new RuntimeRequest(ProtocolVersion.V2_6,
                        "capabilities", "input-timeline-capabilities",
                        new RuntimeCommand.Capabilities()))).result());
        assertTrue(capabilities.supportedTools().contains("runtime_input_timeline"));
        RuntimeCapability timelineCapability = capabilities.capabilityReport().orElseThrow()
                .capabilities().stream().filter(value -> value.id().equals("input-timelines"))
                .findFirst().orElseThrow();
        assertEquals(ProtocolVersion.V2_6, timelineCapability.capabilityVersion());
        assertEquals(RuntimeCapability.Availability.AVAILABLE,
                timelineCapability.availability());
        assertEquals(RuntimeCapability.Access.MUTATING, timelineCapability.access());
        assertTrue(timelineCapability.modes().containsAll(List.of(
                "exact-fixed-tick", "bounded", "application-owned", "same-tick-order")));
        assertTrue(timelineCapability.requiredCapabilities().containsAll(List.of(
                "command-dispatch", "registered-inputs", "simulation-timeline",
                "acknowledged-simulation-control")));
        assertTrue(timelineCapability.limits().containsKey("configuredMaximumTransitions"));
        assertTrue(timelineCapability.limits().containsKey("effectiveMaximumTransitions"));
        assertTrue(timelineCapability.limits().containsKey("configuredMaximumTicks"));
        assertTrue(timelineCapability.limits().containsKey("effectiveMaximumTicks"));
        assertTrue(timelineCapability.limits().containsKey("maximumEncodedEvidenceBytes"));
        assertTrue(timelineCapability.limits().containsKey("retainedOperations"));
        assertTrue(timelineCapability.limits().containsKey("maximumExecutionNanos"));
        assertEquals(runtime.inputs().timelineLimits().maximumTransitions(),
                timelineCapability.limits().get("configuredMaximumTransitions"));
        assertEquals(
                Math.min(runtime.inputs().timelineLimits().maximumTransitions(),
                        Math.min(runtime.inputs().limits().queuedInputs(),
                                runtime.inputs().limits().retainedInjections())),
                timelineCapability.limits().get("effectiveMaximumTransitions"));
    }

    private static RuntimeResponse.Success success(RuntimeResponse response) {
        return assertInstanceOf(RuntimeResponse.Success.class, response);
    }
}
