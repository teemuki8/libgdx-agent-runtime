package io.github.teemuki8.libgdx.agent.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.ActionSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.ApplicationFailureEvidence;
import io.github.teemuki8.libgdx.agent.runtime.core.BaselineKind;
import io.github.teemuki8.libgdx.agent.runtime.core.CommandState;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointHandle;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointOperation;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointProvider;
import io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic;
import io.github.teemuki8.libgdx.agent.runtime.core.AssertionStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.ControlStopReason;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.EventSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameId;
import io.github.teemuki8.libgdx.agent.runtime.core.InputInjectionState;
import io.github.teemuki8.libgdx.agent.runtime.core.InputSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeAssertion;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationControllerSpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

final class RuntimeProtocolTest {
    @Test
    void verticalSliceRoundTripsWithTypedValues() {
        AgentRuntime runtime = verticalRuntime();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeRequest request = new RuntimeRequest(ProtocolVersion.V1, "snapshot-1",
                    "fixture", new RuntimeCommand.Snapshot(null, "enemy-1", false,
                            "enemy", false, 10));
            RuntimeResponse response = service.execute(ProtocolJson.decodeRequest(
                    ProtocolJson.encode(request)));
            RuntimeResponse decoded = ProtocolJson.decodeResponse(ProtocolJson.encode(response));

            RuntimeResponse.Success success =
                    assertInstanceOf(RuntimeResponse.Success.class, decoded);
            RuntimeResponse.Result.Snapshot result =
                    assertInstanceOf(RuntimeResponse.Result.Snapshot.class, success.result());
            assertEquals(RuntimeValues.integer(75), result.snapshot().entities().getFirst()
                    .property("health").orElseThrow());
            assertEquals("damage.applied", result.snapshot().events().getFirst().type().value());
            assertEquals(RuntimeValues.integer(25),
                    result.snapshot().events().getFirst().attributes().getFirst().value());
            assertFalse(result.hasMore());
        }
    }

    @Test
    void rejectsUnknownFieldsUnknownCommandsAndArbitraryPolymorphicTypes() {
        String unknownField = """
                {"version":{"major":1,"minor":0},"requestId":"x","sessionId":null,
                 "command":{"type":"sessions"},"filesystemPath":"/tmp"}""";
        assertThrows(ProtocolJson.ProtocolJsonException.class,
                () -> ProtocolJson.decodeRequest(unknownField.getBytes(StandardCharsets.UTF_8)));
        String unknownCommand = """
                {"version":{"major":1,"minor":0},"requestId":"x","sessionId":null,
                 "command":{"type":"java.lang.Runtime"}}""";
        assertThrows(ProtocolJson.ProtocolJsonException.class,
                () -> ProtocolJson.decodeRequest(unknownCommand.getBytes(StandardCharsets.UTF_8)));
        String classType = """
                {"version":{"major":1,"minor":0},"requestId":"x","sessionId":"fixture",
                 "command":{"type":"snapshot","frameId":null,"entityId":null,
                 "entityIdPrefix":false,"entityType":null,"entityTypePrefix":false,"limit":10},
                 "@class":"java.lang.Runtime"}""";
        assertThrows(ProtocolJson.ProtocolJsonException.class,
                () -> ProtocolJson.decodeRequest(classType.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void enforcesRequestSizeAndRequestedLimits() {
        byte[] excessive = new byte[ProtocolJson.MAX_REQUEST_BYTES + 1];
        assertEquals(ProtocolErrorCode.LIMIT_EXCEEDED,
                assertThrows(ProtocolJson.ProtocolJsonException.class,
                        () -> ProtocolJson.decodeRequest(excessive)).code());
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeCommand.Frames(0, 1, ProtocolJson.MAX_RESULT_ITEMS + 1));
    }

    @Test
    void returnsStableErrorsForVersionSessionFrameAndEntity() {
        RuntimeProtocolService service = new RuntimeProtocolService(new RuntimeRegistry());
        RuntimeResponse.Failure version = assertInstanceOf(RuntimeResponse.Failure.class,
                service.execute(new RuntimeRequest(
                        new ProtocolVersion(2, 1), "v", null, new RuntimeCommand.Sessions())));
        assertEquals(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED, version.error().code());
        assertEquals("1.0,1.1,1.2,1.3,1.4,1.5,1.6,1.7,1.8,1.9,1.10,1.11,1.12,1.13,2.0",
                version.error().details().get("supported"));

        RuntimeResponse.Failure future = assertInstanceOf(RuntimeResponse.Failure.class,
                service.execute(new RuntimeRequest(
                        new ProtocolVersion(3, 0), "f", null, new RuntimeCommand.Sessions())));
        assertEquals(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED, future.error().code());

        RuntimeResponse.Failure missing = assertInstanceOf(RuntimeResponse.Failure.class,
                service.execute(new RuntimeRequest(ProtocolVersion.V1, "s", "missing",
                        new RuntimeCommand.Capabilities())));
        assertEquals(ProtocolErrorCode.SESSION_NOT_FOUND, missing.error().code());

        AgentRuntime runtime = verticalRuntime();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            service = new RuntimeProtocolService(registry);
            RuntimeResponse.Failure frame = assertInstanceOf(RuntimeResponse.Failure.class,
                    service.execute(new RuntimeRequest(ProtocolVersion.V1, "f", "fixture",
                            new RuntimeCommand.Snapshot(999L, null, false, null, false, 10))));
            assertEquals(ProtocolErrorCode.FRAME_NOT_FOUND, frame.error().code());
            RuntimeResponse.Failure entity = assertInstanceOf(RuntimeResponse.Failure.class,
                    service.execute(new RuntimeRequest(ProtocolVersion.V1, "e", "fixture",
                            new RuntimeCommand.Entity("missing", 0, 1, 10))));
            assertEquals(ProtocolErrorCode.ENTITY_NOT_FOUND, entity.error().code());
        }
    }

    @Test
    void reportsExtensionAwareCapabilitiesWithoutChangingV1Shape() {
        AgentRuntime runtime = verticalRuntime();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeResponse.Result.Capabilities v1 = capabilities(
                    service, ProtocolVersion.V1, "capabilities-v1", "fixture");
            assertTrue(v1.capabilityReport().isEmpty());
            RuntimeResponse.Success v1Response = new RuntimeResponse.Success(
                    ProtocolVersion.V1, "capabilities-v1", v1);
            String v1Json = new String(ProtocolJson.encode(v1Response), StandardCharsets.UTF_8);
            assertFalse(v1Json.contains("capabilityReport"));
            RuntimeResponse.Result.Capabilities decodedV1 = assertInstanceOf(
                    RuntimeResponse.Result.Capabilities.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            ProtocolJson.decodeResponse(ProtocolJson.encode(v1Response))).result());
            assertTrue(decodedV1.capabilityReport().isEmpty());

            RuntimeResponse.Result.Capabilities current = capabilities(
                    service, ProtocolVersion.V1_1, "capabilities-v1-1", "fixture");
            CapabilityReport report = current.capabilityReport().orElseThrow();
            assertFalse(report.runtimeVersion().isBlank());
            assertEquals(List.of("changes", "decisions", "entities", "events", "frames"),
                    report.capabilities().stream().map(RuntimeCapability::id).toList());
            assertTrue(report.capabilities().stream().allMatch(capability ->
                    capability.availability() == RuntimeCapability.Availability.AVAILABLE));
            RuntimeResponse decoded = ProtocolJson.decodeResponse(ProtocolJson.encode(
                    new RuntimeResponse.Success(ProtocolVersion.V1_1,
                            "capabilities-v1-1", current)));
            assertEquals(ProtocolVersion.V1_1, decoded.version());
        }
    }

    @Test
    void reportsDisabledCapabilitiesAsUnavailable() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("disabled"))
                .configuration(io.github.teemuki8.libgdx.agent.runtime.core.RuntimeConfiguration
                        .disabled())
                .build();
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeResponse.Result.Capabilities capabilities = capabilities(
                    new RuntimeProtocolService(registry), ProtocolVersion.V1_1,
                    "disabled-capabilities", "disabled");
            assertTrue(capabilities.enabledFeatures().isEmpty());
            assertTrue(capabilities.capabilityReport().orElseThrow().capabilities().stream()
                    .allMatch(capability ->
                            capability.availability()
                                    == RuntimeCapability.Availability.UNAVAILABLE
                            && capability.unavailableReason().orElseThrow()
                                    .equals("runtime-disabled")));
        }
    }

    @Test
    void commandStatusAndCancellationUseRegisteredDispatchWithoutRunningTheTask() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        int[] executions = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("commands"))
                .commandDispatcher(applicationQueue::addLast)
                .build();
        runtime.start();
        runtime.commands().orElseThrow().submit(
                "reset-1", Duration.ofSeconds(1), () -> executions[0]++);
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            assertTrue(service.toolNames().contains("runtime_command_status"));
            RuntimeResponse.Result.CommandStatus status = assertInstanceOf(
                    RuntimeResponse.Result.CommandStatus.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_2, "status", "commands",
                                    new RuntimeCommand.CommandStatus("reset-1")))).result());
            assertEquals(CommandState.QUEUED,
                    status.command().status().orElseThrow().state());
            assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                    new RuntimeRequest(ProtocolVersion.V1, "paused-snapshot", "commands",
                            new RuntimeCommand.Snapshot(
                                    null, null, false, null, false, 10))));

            RuntimeResponse.Result.CommandCancellation cancellation = assertInstanceOf(
                    RuntimeResponse.Result.CommandCancellation.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_2, "cancel", "commands",
                                    new RuntimeCommand.CommandCancel("reset-1")))).result());
            assertTrue(cancellation.cancellation().accepted());
            applicationQueue.removeFirst().run();
            assertEquals(0, executions[0]);

            RuntimeResponse.Result.Capabilities capabilities = capabilities(
                    service, ProtocolVersion.V1_2, "capabilities-commands", "commands");
            assertFalse(capabilities(service, ProtocolVersion.V1_1,
                    "capabilities-commands-v1-1", "commands").supportedTools()
                    .contains("runtime_command_status"));
            assertTrue(capabilities.capabilityReport().orElseThrow().capabilities().stream()
                    .anyMatch(capability -> capability.id().equals("command-dispatch")
                            && capability.availability()
                                    == RuntimeCapability.Availability.AVAILABLE));

            RuntimeResponse.Result.CommandStatus unknown = assertInstanceOf(
                    RuntimeResponse.Result.CommandStatus.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_2, "unknown-status", "commands",
                                    new RuntimeCommand.CommandStatus("not-submitted")))).result());
            assertEquals(io.github.teemuki8.libgdx.agent.runtime.core.CommandLookup.Kind.UNKNOWN,
                    unknown.command().kind());
        }
    }

    @Test
    void unregisteredCommandDispatchIsDiscoverableAndUnavailable() {
        AgentRuntime runtime = verticalRuntime();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            assertFalse(service.toolNames().contains("runtime_command_status"));
            RuntimeResponse.Failure failure = assertInstanceOf(RuntimeResponse.Failure.class,
                    service.execute(new RuntimeRequest(ProtocolVersion.V1_2, "status", "fixture",
                            new RuntimeCommand.CommandStatus("unknown"))));
            assertEquals(ProtocolErrorCode.CAPABILITY_UNAVAILABLE, failure.error().code());
            RuntimeResponse.Result.Capabilities capabilities = capabilities(
                    service, ProtocolVersion.V1_2, "capabilities-v1-2", "fixture");
            RuntimeCapability dispatch = capabilities.capabilityReport().orElseThrow()
                    .capabilities().stream()
                    .filter(capability -> capability.id().equals("command-dispatch"))
                    .findFirst().orElseThrow();
            assertEquals(RuntimeCapability.Availability.UNAVAILABLE, dispatch.availability());
            assertEquals("dispatcher-not-registered", dispatch.unavailableReason().orElseThrow());
        }
    }

    @Test
    void executionEpochFramesRoundTripWithoutChangingEarlierVersions() {
        AgentRuntime runtime = verticalRuntime();
        runtime.startEpoch(BaselineKind.SCENARIO_RESET);
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeResponse.Result.EpochFrames epochFrames = assertInstanceOf(
                    RuntimeResponse.Result.EpochFrames.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_3, "epoch", "fixture",
                                    new RuntimeCommand.EpochFrames(1, 10)))).result());
            assertEquals(1, epochFrames.page().items().size());
            assertEquals(Optional.of(BaselineKind.SCENARIO_RESET),
                    epochFrames.page().items().getFirst().baselineKind());
            RuntimeResponse.Result.Snapshot filtered = assertInstanceOf(
                    RuntimeResponse.Result.Snapshot.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1, "filtered", "fixture",
                                    new RuntimeCommand.Snapshot(null, "enemy-1", false,
                                            null, false, 10)))).result());
            assertEquals(1, filtered.snapshot().executionEpochId().value());
            assertEquals(Optional.of(BaselineKind.SCENARIO_RESET),
                    filtered.snapshot().baselineKind());
            RuntimeResponse.Result.Capabilities capabilities = capabilities(
                    service, ProtocolVersion.V1_3, "capabilities-v1-3", "fixture");
            assertTrue(capabilities.enabledFeatures().contains("execution-epochs"));
            assertFalse(capabilities(service, ProtocolVersion.V1_2,
                    "capabilities-v1-2-frozen", "fixture").supportedTools()
                    .contains("runtime_epoch_frames"));
            RuntimeResponse decoded = ProtocolJson.decodeResponse(ProtocolJson.encode(
                    new RuntimeResponse.Success(ProtocolVersion.V1_3, "epoch", epochFrames)));
            assertEquals(ProtocolVersion.V1_3, decoded.version());
        }
    }

    @Test
    void capabilityMetadataIsBoundedValidatedAndDeterministicallyOrdered() {
        RuntimeCapability capability = new RuntimeCapability(
                "test", ProtocolVersion.V1,
                RuntimeCapability.Availability.AVAILABLE, Optional.empty(),
                RuntimeCapability.Access.READ_ONLY,
                List.of("Api#z", "Api#a"), List.of("z", "a"), List.of("tool-z", "tool-a"),
                Map.of("z", 2L, "a", 1L), List.of("z", "a"), List.of("frames"));
        assertEquals(List.of("Api#a", "Api#z"), capability.javaApis());
        assertEquals(List.of("a", "z"), capability.protocolCommands());
        assertEquals(List.of("a", "z"), capability.modes());
        assertEquals(List.of("a", "z"), capability.limits().keySet().stream().toList());

        assertThrows(IllegalArgumentException.class, () -> new RuntimeCapability(
                "test", ProtocolVersion.V1,
                RuntimeCapability.Availability.AVAILABLE, Optional.of("disabled"),
                RuntimeCapability.Access.READ_ONLY, List.of(), List.of(), List.of(), Map.of(),
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeCapability(
                "test", ProtocolVersion.V1,
                RuntimeCapability.Availability.AVAILABLE, Optional.empty(),
                RuntimeCapability.Access.READ_ONLY, List.of("duplicate", "duplicate"),
                List.of(), List.of(), Map.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityReport(
                "development", List.of(capability, capability)));
    }

    @Test
    void reportsTruncationAndDeterministicOutput() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("many")).build();
        IntStream.range(0, 3).forEach(index -> runtime.entities().register(
                EntityId.of("enemy-" + index), EntityType.of("enemy"), () -> "Enemy",
                inspector -> inspector.property("index", () -> (long) index)));
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeRequest request = new RuntimeRequest(ProtocolVersion.V1, "x", "many",
                    new RuntimeCommand.Snapshot(null, null, false, null, false, 1));
            RuntimeResponse response = new RuntimeProtocolService(registry).execute(request);
            RuntimeResponse.Result.Snapshot snapshot = assertInstanceOf(
                    RuntimeResponse.Result.Snapshot.class,
                    assertInstanceOf(RuntimeResponse.Success.class, response).result());
            assertTrue(snapshot.hasMore());
            assertEquals(1, snapshot.snapshot().entities().size());
            assertArrayEquals(ProtocolJson.encode(response), ProtocolJson.encode(response));
        }
    }

    @Test
    void registryRejectsDuplicatesAndPublicationRemovalIsNonOwning() {
        AgentRuntime runtime = verticalRuntime();
        RuntimeRegistry registry = new RuntimeRegistry();
        PublishedRuntime publication = registry.publish(runtime);
        assertThrows(IllegalStateException.class, () -> registry.publish(runtime));
        publication.close();
        assertTrue(registry.sessions().isEmpty());
        assertTrue(runtime.latestFrame().isPresent());
    }

    @Test
    void eventAndChangeQueriesRoundTrip() {
        AgentRuntime runtime = verticalRuntime();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeResponse.Result.Events events = assertInstanceOf(
                    RuntimeResponse.Result.Events.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            service.execute(new RuntimeRequest(ProtocolVersion.V1, "events",
                                    "fixture", new RuntimeCommand.Events(
                                            0, 1, "damage.", true, "enemy-1",
                                            "projectile-3", 10)))).result());
            assertEquals(1, events.page().items().size());
            RuntimeResponse.Result.Changes changes = assertInstanceOf(
                    RuntimeResponse.Result.Changes.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            service.execute(new RuntimeRequest(ProtocolVersion.V1, "changes",
                                    "fixture", new RuntimeCommand.Changes(
                                            0, 1, "enemy-1", "enemy", "health", 10)))).result());
            assertEquals(1, changes.page().items().size());
            assertEquals("health",
                    changes.page().items().getFirst().property().orElseThrow());
        }
    }

    @Test
    void registeredScenarioCatalogAndResetReturnBaselineEvidence() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("scenarios"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.scenarios().register("basic-combat", "Known state", () -> {});
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeResponse.Result.Scenarios catalog = assertInstanceOf(
                    RuntimeResponse.Result.Scenarios.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_4, "list", "scenarios",
                                    new RuntimeCommand.Scenarios()))).result());
            assertEquals("basic-combat", catalog.scenarios().getFirst().id());

            RuntimeCommand.Reset command = new RuntimeCommand.Reset(
                    "basic-combat", "reset-1", 1_000);
            RuntimeResponse.Result.Reset queued = assertInstanceOf(
                    RuntimeResponse.Result.Reset.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_4, "submit", "scenarios",
                                    command))).result());
            assertEquals(CommandState.QUEUED,
                    queued.reset().command().status().orElseThrow().state());
            queue.removeFirst().run();
            RuntimeResponse.Result.Reset completed = assertInstanceOf(
                    RuntimeResponse.Result.Reset.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_4, "poll", "scenarios",
                                    command))).result());
            assertEquals(1, completed.reset().baselineFrameId().orElseThrow().value());
            assertTrue(service.toolNames().containsAll(
                    List.of("runtime_scenarios", "runtime_reset")));
        }
    }

    @Test
    void attributedEventQueryRoundTripsWithoutChangingEventSourceMeaning() {
        AgentRuntime runtime = AgentRuntime.builder().sessionId(SessionId.of("facts")).build();
        runtime.start();
        runtime.frame(1, () -> runtime.emit(EventSpec.type("damage.applied")
                .source(EntityId.of("attacker"))
                .sourceSubsystem("combat")
                .sourceLocation("DamageSystem.java:84")
                .correlationId("attack-172")));
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeResponse.Result.Events result = assertInstanceOf(
                    RuntimeResponse.Result.Events.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            new RuntimeProtocolService(registry).execute(new RuntimeRequest(
                                    ProtocolVersion.V1_5, "facts", "facts",
                                    new RuntimeCommand.AttributedEvents(0, 1, null, false,
                                            null, "attacker", "combat", "attack-172", 10))))
                            .result());
            assertEquals("attacker", result.page().items().getFirst()
                    .source().orElseThrow().value());
            assertEquals("DamageSystem.java:84", result.page().items().getFirst().metadata()
                    .sourceLocation().orElseThrow());
        }
    }

    @Test
    void semanticActionCatalogAndInvocationRoundTripWithFrameEvidence() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        String[] target = {null};
        AgentRuntime runtime = AgentRuntime.builder().sessionId(SessionId.of("actions"))
                .clock(() -> 1).commandDispatcher(queue::addLast).build();
        runtime.actions().register(ActionSpec.builder("player.attack")
                .description("Attack one target")
                .requiredEntityId("targetEntity")
                .handler(parameters -> {
                    target[0] = parameters.requiredEntityId("targetEntity").value();
                    runtime.frame(1, () -> {});
                }).build());
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeResponse.Result.Actions catalog = assertInstanceOf(
                    RuntimeResponse.Result.Actions.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_6, "catalog", "actions",
                                    new RuntimeCommand.Actions()))).result());
            assertEquals("targetEntity",
                    catalog.actions().getFirst().parameters().getFirst().name());

            RuntimeCommand.Action command = new RuntimeCommand.Action("player.attack", "attack-1",
                    RuntimeValues.object(RuntimeValues.field(
                            "targetEntity", RuntimeValues.string("enemy-1"))),
                    "attack-172", 1_000);
            RuntimeCommand.Action decoded = assertInstanceOf(RuntimeCommand.Action.class,
                    ProtocolJson.decodeRequest(ProtocolJson.encode(new RuntimeRequest(
                            ProtocolVersion.V1_6, "roundtrip", "actions", command))).command());
            RuntimeResponse.Result.Action queued = assertInstanceOf(
                    RuntimeResponse.Result.Action.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_6, "invoke", "actions",
                                    decoded))).result());
            assertEquals(CommandState.QUEUED,
                    queued.invocation().command().status().orElseThrow().state());
            queue.removeFirst().run();
            RuntimeResponse.Result.Action completed = assertInstanceOf(
                    RuntimeResponse.Result.Action.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_6, "poll", "actions",
                                    command))).result());
            assertEquals("enemy-1", target[0]);
            assertEquals(1, completed.invocation().completedFrameId().orElseThrow().value());
            assertEquals("attack-172", completed.invocation().correlationId().orElseThrow());
        }
    }

    @Test
    void declarativeAssertionRoundTripsWithClosedNestedSchema() {
        AgentRuntime runtime = verticalRuntime();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeCommand.Assert command = new RuntimeCommand.Assert(
                    new RuntimeAssertion.PropertyEquals(EntityId.of("enemy-1"), "health",
                            RuntimeValues.integer(75)),
                    0, 1, 0, 8);
            RuntimeCommand.Assert decoded = assertInstanceOf(RuntimeCommand.Assert.class,
                    ProtocolJson.decodeRequest(ProtocolJson.encode(new RuntimeRequest(
                            ProtocolVersion.V1_7, "assert-roundtrip", "fixture", command))).command());
            RuntimeResponse.Result.Assertion result = assertInstanceOf(
                    RuntimeResponse.Result.Assertion.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            new RuntimeProtocolService(registry).execute(new RuntimeRequest(
                                    ProtocolVersion.V1_7, "assert", "fixture", decoded))).result());

            assertEquals(AssertionStatus.PASS, result.result().status());
            assertThrows(ProtocolJson.ProtocolJsonException.class, () ->
                    ProtocolJson.decodeRequest(("""
                            {"version":{"major":1,"minor":7},"requestId":"bad","sessionId":"fixture",
                             "command":{"type":"assert","fromFrame":0,"toFrame":1,
                             "executionEpochId":0,"evidenceLimit":8,
                             "assertion":{"assertionType":"entityExists",
                             "entityId":{"value":"enemy-1"},"unknown":true}}}
                            """).getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void simulationControlPauseAdvanceAndWaitRoundTripWithExactFrameEvidence() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        int[] ticks = {0};
        AgentRuntime runtime = AgentRuntime.builder().sessionId(SessionId.of("control"))
                .clock(() -> 1).commandDispatcher(queue::addLast).build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .tick(deltaNanos -> ticks[0]++)
                .condition("three-ticks", "Three ticks completed", () -> ticks[0] >= 3)
                .build());
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeResponse.Result.Control status = assertInstanceOf(
                    RuntimeResponse.Result.Control.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_8, "status", "control",
                                    new RuntimeCommand.Control(
                                            RuntimeCommand.ControlAction.STATUS, null, 0))))
                            .result());
            assertEquals("three-ticks",
                    status.descriptor().conditions().getFirst().id());

            RuntimeCommand.Control pause = new RuntimeCommand.Control(
                    RuntimeCommand.ControlAction.PAUSE, "pause-1", 1_000);
            service.execute(new RuntimeRequest(ProtocolVersion.V1_8, "pause", "control", pause));
            queue.removeFirst().run();
            RuntimeResponse.Result.Control paused = assertInstanceOf(
                    RuntimeResponse.Result.Control.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_8, "pause-poll", "control",
                                    pause))).result());
            assertEquals(true, paused.descriptor().paused());

            RuntimeCommand.Advance advance =
                    new RuntimeCommand.Advance("advance-1", 2, 16_666_667, 1_000);
            service.execute(new RuntimeRequest(
                    ProtocolVersion.V1_8, "advance", "control", advance));
            queue.removeFirst().run();
            RuntimeResponse.Result.Control advanced = assertInstanceOf(
                    RuntimeResponse.Result.Control.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_8, "advance-poll", "control",
                                    advance))).result());
            assertEquals(2, advanced.operation().orElseThrow().completedTicks());

            RuntimeCommand.Wait wait = new RuntimeCommand.Wait(
                    "wait-1", "three-ticks", null, 2, 16_666_667, 8, 1_000);
            service.execute(new RuntimeRequest(ProtocolVersion.V1_8, "wait", "control", wait));
            queue.removeFirst().run();
            RuntimeResponse.Result.Control waited = assertInstanceOf(
                    RuntimeResponse.Result.Control.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(
                                    ProtocolVersion.V1_8, "wait-poll", "control", wait))).result());
            assertEquals(ControlStopReason.CONDITION_SATISFIED,
                    waited.operation().orElseThrow().stopReason());
            assertEquals(3, ticks[0]);
        }
    }

    @Test
    void checkpointCreationAndRestoreRoundTripWithoutExposingOpaqueHandle() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        int[] state = {7};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("checkpoint-protocol"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override public CheckpointHandle create() {
                return new TestCheckpoint(state[0]);
            }
            @Override public void restore(CheckpointHandle handle) {
                state[0] = ((TestCheckpoint) handle).value();
            }
            @Override public void dispose(CheckpointHandle handle) {}
        });
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeCapability capability = capabilities(service, ProtocolVersion.V1_10,
                    "checkpoint-capabilities", "checkpoint-protocol")
                    .capabilityReport().orElseThrow().capabilities().stream()
                    .filter(value -> value.id().equals("checkpoints")).findFirst().orElseThrow();
            assertEquals(RuntimeCapability.Availability.AVAILABLE, capability.availability());

            RuntimeCommand.CheckpointCreate create = new RuntimeCommand.CheckpointCreate(
                    "save-1", "Before change", "create-1", 1_000);
            RuntimeCommand.CheckpointCreate decoded =
                    assertInstanceOf(RuntimeCommand.CheckpointCreate.class,
                            ProtocolJson.decodeRequest(ProtocolJson.encode(new RuntimeRequest(
                                    ProtocolVersion.V1_10, "create-json",
                                    "checkpoint-protocol", create))).command());
            service.execute(new RuntimeRequest(ProtocolVersion.V1_10, "create",
                    "checkpoint-protocol", decoded));
            queue.removeFirst().run();
            RuntimeResponse.Result.Checkpoint created = assertInstanceOf(
                    RuntimeResponse.Result.Checkpoint.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_10, "create-poll",
                                    "checkpoint-protocol", decoded))).result());
            assertEquals(CheckpointOperation.Kind.CREATE, created.operation().kind());
            RuntimeResponse catalogResponse = service.execute(new RuntimeRequest(
                    ProtocolVersion.V1_10, "catalog", "checkpoint-protocol",
                    new RuntimeCommand.Checkpoints()));
            assertFalse(new String(ProtocolJson.encode(catalogResponse), StandardCharsets.UTF_8)
                    .contains("TestCheckpoint"));

            state[0] = 9;
            runtime.frame(1, () -> {});
            RuntimeCommand.CheckpointRestore restore =
                    new RuntimeCommand.CheckpointRestore("save-1", "restore-1", 1_000);
            service.execute(new RuntimeRequest(ProtocolVersion.V1_10, "restore",
                    "checkpoint-protocol", restore));
            queue.removeFirst().run();
            RuntimeResponse.Result.Checkpoint restored = assertInstanceOf(
                    RuntimeResponse.Result.Checkpoint.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_10, "restore-poll",
                                    "checkpoint-protocol", restore))).result());
            assertEquals(7, state[0]);
            assertEquals(new FrameId(2), restored.operation().baselineFrameId().orElseThrow());
        }
    }

    @Test
    void uiCorrelationCapabilityAndQueriesRoundTripWithBoundedEvidence() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("ui-correlation-protocol")).build();
        runtime.uiCorrelations().register(new io.github.teemuki8.libgdx.agent.runtime.core.UiBinding(
                "health-binding", EntityId.of("enemy-1"), Optional.of("health"),
                "battle-ui", "health-bar",
                        new io.github.teemuki8.libgdx.agent.runtime.core.UiBindingValidity(
                                Optional.empty(), Optional.empty(), Optional.empty())));
        runtime.start();
        runtime.uiCorrelations().recordFrame(
                new io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelation(
                        runtime.currentEpoch(), new FrameId(0), "battle-ui",
                        Optional.of("ui-frame-9"), Optional.of("render-token-9")));
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeCapability capability = capabilities(service, ProtocolVersion.V1_11,
                    "ui-capabilities", "ui-correlation-protocol")
                    .capabilityReport().orElseThrow().capabilities().stream()
                    .filter(value -> value.id().equals("runtime-ui-correlation"))
                    .findFirst().orElseThrow();
            assertEquals(RuntimeCapability.Availability.AVAILABLE, capability.availability());

            RuntimeCommand.UiBindings query = new RuntimeCommand.UiBindings(
                    "enemy-1", "health", null, null, 0, 0, null, 8);
            RuntimeCommand.UiBindings decoded = assertInstanceOf(RuntimeCommand.UiBindings.class,
                    ProtocolJson.decodeRequest(ProtocolJson.encode(new RuntimeRequest(
                            ProtocolVersion.V1_11, "ui-json",
                            "ui-correlation-protocol", query))).command());
            RuntimeResponse.Result.UiBindings bindings = assertInstanceOf(
                    RuntimeResponse.Result.UiBindings.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_11, "ui-query",
                                    "ui-correlation-protocol", decoded))).result());
            assertEquals(io.github.teemuki8.libgdx.agent.runtime.core.UiBindingStatus.MATCHED,
                    bindings.result().status());
            assertEquals("health-bar", bindings.result().bindings().getFirst().uiControlId());

            RuntimeResponse.Result.UiFrames frames = assertInstanceOf(
                    RuntimeResponse.Result.UiFrames.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_11, "ui-frames",
                                    "ui-correlation-protocol",
                                    new RuntimeCommand.UiFrames(
                                            null, "render-token-9", 8)))).result());
            assertEquals(new FrameId(0), frames.page().items().getFirst().runtimeFrameId());

            RuntimeResponse.Failure oldVersion = assertInstanceOf(RuntimeResponse.Failure.class,
                    service.execute(new RuntimeRequest(ProtocolVersion.V1_10, "ui-old",
                            "ui-correlation-protocol", query)));
            assertEquals(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED,
                    oldVersion.error().code());
        }
    }

    @Test
    void registeredInputCapabilityRequiresSimulationController() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-without-controller"))
                .commandDispatcher(command -> {}).build();
        runtime.inputs().register(InputSpec.builder("key-down")
                .requiredString("key").handler(parameters -> {}).build());
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeCapability inputs = capabilities(
                    service, ProtocolVersion.V1_9, "input-capability",
                    "input-without-controller").capabilityReport().orElseThrow()
                    .capabilities().stream()
                    .filter(capability -> capability.id().equals("registered-inputs"))
                    .findFirst().orElseThrow();
            assertEquals(RuntimeCapability.Availability.UNAVAILABLE, inputs.availability());
            assertEquals("controller-not-registered",
                    inputs.unavailableReason().orElseThrow());

            RuntimeResponse.Failure failure = assertInstanceOf(RuntimeResponse.Failure.class,
                    service.execute(new RuntimeRequest(
                            ProtocolVersion.V1_9, "input", "input-without-controller",
                            new RuntimeCommand.Input("key-down", "key-1",
                                    RuntimeValues.object(RuntimeValues.field(
                                            "key", RuntimeValues.string("SPACE"))),
                                    null, 1_000))));
            assertEquals(ProtocolErrorCode.CAPABILITY_UNAVAILABLE, failure.error().code());
        }
    }

    @Test
    void registeredInputCatalogAndTargetedInjectionRoundTripWithFrameEvidence() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        String[] key = {""};
        AgentRuntime runtime = AgentRuntime.builder().sessionId(SessionId.of("input-protocol"))
                .clock(() -> 1).commandDispatcher(queue::addLast).build();
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .tick(deltaNanos -> {})
                .build());
        runtime.inputs().register(InputSpec.builder("key-down")
                .description("Registered key input")
                .requiredString("key")
                .handler(parameters -> key[0] = parameters.requiredString("key"))
                .build());
        runtime.start();
        runtime.controls().control(true, "pause-input", Duration.ofSeconds(1));
        queue.removeFirst().run();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeResponse.Result.Inputs catalog = assertInstanceOf(
                    RuntimeResponse.Result.Inputs.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_9, "inputs", "input-protocol",
                                    new RuntimeCommand.Inputs()))).result());
            assertEquals("key-down", catalog.inputs().getFirst().id());

            RuntimeCommand.Input input = new RuntimeCommand.Input(
                    "key-down", "key-1",
                    RuntimeValues.object(RuntimeValues.field(
                            "key", RuntimeValues.string("SPACE"))),
                    null, 1_000);
            RuntimeCommand.Input decoded = assertInstanceOf(RuntimeCommand.Input.class,
                    ProtocolJson.decodeRequest(ProtocolJson.encode(new RuntimeRequest(
                            ProtocolVersion.V1_9, "input-json", "input-protocol", input)))
                            .command());
            service.execute(new RuntimeRequest(
                    ProtocolVersion.V1_9, "input", "input-protocol", decoded));
            queue.removeFirst().run();
            RuntimeCommand.Advance tick =
                    new RuntimeCommand.Advance("input-tick", 1, 16_666_667, 1_000);
            service.execute(new RuntimeRequest(
                    ProtocolVersion.V1_8, "tick", "input-protocol", tick));
            queue.removeFirst().run();
            RuntimeResponse.Result.Input completed = assertInstanceOf(
                    RuntimeResponse.Result.Input.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_9, "input-poll",
                                    "input-protocol", decoded))).result());
            assertEquals(InputInjectionState.EXECUTED, completed.injection().state());
            assertEquals(1, completed.injection().actualTick().orElseThrow());
            assertEquals(new FrameId(1),
                    completed.injection().resultingFrameId().orElseThrow());
            assertEquals("SPACE", key[0]);
        }
    }

    @Test
    void recordingCapabilityAndCommandsRoundTripWithBoundedManifestEvidence() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("recording-protocol"))
                .commandDispatcher(queue::addLast)
                .build();
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeCapability capability = capabilities(service, ProtocolVersion.V1_12,
                    "recording-capabilities", "recording-protocol")
                    .capabilityReport().orElseThrow().capabilities().stream()
                    .filter(value -> value.id().equals("recording")).findFirst().orElseThrow();
            assertEquals(RuntimeCapability.Availability.AVAILABLE, capability.availability());
            assertTrue(capability.mcpTools().contains("runtime_recording_get"));

            RuntimeCommand.RecordingStart start = new RuntimeCommand.RecordingStart(
                    "run-1", "record-start-1", "scenario-1", null, 42L,
                    RuntimeValues.object(RuntimeValues.field(
                            "difficulty", RuntimeValues.string("hard"))),
                    false, 1_000_000_000);
            RuntimeCommand.RecordingStart decoded =
                    assertInstanceOf(RuntimeCommand.RecordingStart.class,
                            ProtocolJson.decodeRequest(ProtocolJson.encode(new RuntimeRequest(
                                    ProtocolVersion.V1_12, "start-json",
                                    "recording-protocol", start))).command());
            assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                    new RuntimeRequest(ProtocolVersion.V1_12, "start",
                            "recording-protocol", decoded)));
            queue.removeFirst().run();
            RuntimeResponse.Result.RecordingOperationResult started = assertInstanceOf(
                    RuntimeResponse.Result.RecordingOperationResult.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_12, "start-poll",
                                    "recording-protocol", decoded))).result());
            assertEquals(CommandState.SUCCEEDED,
                    started.operation().command().status().orElseThrow().state());

            runtime.frame(1, () -> {});
            RuntimeCommand.RecordingStop stop = new RuntimeCommand.RecordingStop(
                    "run-1", "record-stop-1", 1_000_000_000);
            service.execute(new RuntimeRequest(ProtocolVersion.V1_12, "stop",
                    "recording-protocol", stop));
            queue.removeFirst().run();
            RuntimeResponse.Result.RecordingChunkResult retrieved = assertInstanceOf(
                    RuntimeResponse.Result.RecordingChunkResult.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_12, "get",
                                    "recording-protocol",
                                    new RuntimeCommand.RecordingGet("run-1", 0, 10)))).result());
            assertEquals(42L, retrieved.chunk().metadata().randomSeed().orElseThrow());
            assertEquals("hard", ((io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue
                    .StringValue) retrieved.chunk().metadata().configuration().fields()
                            .getFirst().value()).value());
            assertEquals(1, retrieved.chunk().entries().size());
            RuntimeResponse.Result.RecordingChunkResult decodedChunk = assertInstanceOf(
                    RuntimeResponse.Result.RecordingChunkResult.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            ProtocolJson.decodeResponse(ProtocolJson.encode(service.execute(
                                    new RuntimeRequest(ProtocolVersion.V1_12, "get-json",
                                            "recording-protocol",
                                            new RuntimeCommand.RecordingGet(
                                                    "run-1", 0, 10)))))).result());
            assertInstanceOf(
                    io.github.teemuki8.libgdx.agent.runtime.core.RecordingFrameEntry.class,
                    decodedChunk.chunk().entries().getFirst());

            RuntimeResponse.Failure oldVersion = assertInstanceOf(RuntimeResponse.Failure.class,
                    service.execute(new RuntimeRequest(ProtocolVersion.V1_11, "old",
                            "recording-protocol",
                            new RuntimeCommand.RecordingGet("run-1", 0, 10))));
            assertEquals(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED,
                    oldVersion.error().code());
        }
    }

    @Test
    void determinismCapabilityAndCommandRoundTripWithBoundedEvidence() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        long[] value = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("determinism-protocol"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.entities().register(EntityId.of("counter"), EntityType.of("state"),
                () -> "Counter", inspector -> inspector.property("value", () -> value[0]));
        runtime.controls().register(
                io.github.teemuki8.libgdx.agent.runtime.core.SimulationControllerSpec.builder()
                        .pause(() -> {}).resume(() -> {}).tick(delta -> value[0]++).build());
        runtime.scenarios().register("seeded",
                context -> value[0] = context.randomSeed().orElseThrow());
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeCapability capability = capabilities(service, ProtocolVersion.V1_13,
                    "determinism-capabilities", "determinism-protocol")
                    .capabilityReport().orElseThrow().capabilities().stream()
                    .filter(valueCapability ->
                            valueCapability.id().equals("determinism-comparison"))
                    .findFirst().orElseThrow();
            assertEquals(RuntimeCapability.Availability.AVAILABLE, capability.availability());
            assertTrue(capability.mcpTools().contains("runtime_determinism_check"));

            RuntimeCommand.DeterminismCheck command = new RuntimeCommand.DeterminismCheck(
                    "determinism-1", "seeded", 7, RuntimeValues.object(), 2, 2, 1,
                    new io.github.teemuki8.libgdx.agent.runtime.core.DeterminismProfile(
                            new io.github.teemuki8.libgdx.agent.runtime.core
                                    .SnapshotComparisonScope(
                                            List.of(EntityId.of("counter")), List.of("value"),
                                            List.of(), false, false),
                            false),
                    1_000_000_000);
            RuntimeCommand.DeterminismCheck decoded = assertInstanceOf(
                    RuntimeCommand.DeterminismCheck.class,
                    ProtocolJson.decodeRequest(ProtocolJson.encode(new RuntimeRequest(
                            ProtocolVersion.V1_13, "determinism-json",
                            "determinism-protocol", command))).command());
            service.execute(new RuntimeRequest(ProtocolVersion.V1_13, "determinism-submit",
                    "determinism-protocol", decoded));
            queue.removeFirst().run();
            RuntimeResponse.Result.Determinism result = assertInstanceOf(
                    RuntimeResponse.Result.Determinism.class,
                    assertInstanceOf(RuntimeResponse.Success.class,
                            ProtocolJson.decodeResponse(ProtocolJson.encode(service.execute(
                                    new RuntimeRequest(ProtocolVersion.V1_13, "determinism-poll",
                                            "determinism-protocol", decoded))))).result());
            assertEquals(
                    io.github.teemuki8.libgdx.agent.runtime.core.DeterminismStatus.EQUAL,
                    result.operation().result().orElseThrow().status());
            RuntimeResponse.Failure oldVersion = assertInstanceOf(RuntimeResponse.Failure.class,
                    service.execute(new RuntimeRequest(ProtocolVersion.V1_12, "old-determinism",
                            "determinism-protocol", command)));
            assertEquals(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED,
                    oldVersion.error().code());
        }
    }

    private record TestCheckpoint(int value) implements CheckpointHandle {}

    @Test
    void legacyProtocolSnapshotRetainsDiagnosticFieldsAndEnvelopeOnly() throws Exception {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("legacy-diag"))
                .clock(() -> 1)
                .build();
        runtime.entities().register(EntityId.of("bad"), EntityType.of("enemy"),
                () -> "Bad", inspector -> inspector.property("health",
                        (java.util.function.Supplier<io.github.teemuki8.libgdx.agent.runtime.core
                                .RuntimeValue>) () -> {
                    throw new IllegalStateException("token=secret-123 /home/private/save.dat");
                }));
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeResponse response = service.execute(new RuntimeRequest(
                    ProtocolVersion.V1, "diag-snapshot", "legacy-diag",
                    new RuntimeCommand.Snapshot(null, null, false, null, false, 10)));
            byte[] encoded = ProtocolJson.encode(response);
            String json = new String(encoded, StandardCharsets.UTF_8);
            assertFalse(json.contains("token=secret-123"));
            assertFalse(json.contains("/home/private/save.dat"));
            assertFalse(json.contains("sanitizedDetail"));
            assertFalse(json.contains("applicationFailure"));
            com.fasterxml.jackson.databind.JsonNode diagnostic = ProtocolJson.mapper()
                    .readTree(encoded).path("result").path("snapshot").path("stats")
                    .path("diagnostics").get(0);
            assertFalse(diagnostic.isMissingNode());
            assertEquals("java.lang.IllegalStateException",
                    diagnostic.get("exceptionClass").asText());
            assertEquals("legacy-diag|failure-1|provider.property"
                    + "|java.lang.IllegalStateException",
                    diagnostic.get("message").asText());
            assertFalse(diagnostic.has("failure"));

            RuntimeResponse.Success success = assertInstanceOf(RuntimeResponse.Success.class,
                    ProtocolJson.decodeResponse(encoded));
            RuntimeResponse.Result.Snapshot snapshot = assertInstanceOf(
                    RuntimeResponse.Result.Snapshot.class, success.result());
            io.github.teemuki8.libgdx.agent.runtime.core.ApplicationFailureEvidence failure =
                    snapshot.snapshot().stats().diagnostics().getFirst().failure();
            assertEquals("provider.property", failure.category());
            assertEquals("java.lang.IllegalStateException", failure.exceptionClass());
            assertEquals("legacy-diag|failure-1", failure.correlationId());
            assertEquals("legacy-diag|failure-1|provider.property"
                    + "|java.lang.IllegalStateException", failure.legacyEnvelope());
            assertTrue(failure.sanitizedDetail().isEmpty());
        }
    }

    @Test
    void legacyProtocolDecodesHistoricRawDiagnosticWithoutRetainingSecrets() throws Exception {
        String historic = """
                {"provider":"enemies","entityId":{"value":"bad"},"property":"health",
                 "exceptionClass":"java.lang.IllegalStateException",
                 "message":"token=secret-123 /home/private/save.dat"}""";
        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic decoded =
                ProtocolJson.mapper().readValue(historic,
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class);
        io.github.teemuki8.libgdx.agent.runtime.core.ApplicationFailureEvidence failure =
                decoded.failure();
        assertEquals("legacy.capture", failure.category());
        assertEquals("java.lang.IllegalStateException", failure.exceptionClass());
        assertTrue(failure.sanitizedDetail().isEmpty());
        assertFalse(failure.correlationId().contains("token=secret-123"));
        assertFalse(failure.correlationId().contains("/home/private/save.dat"));
        assertFalse(failure.legacyEnvelope().contains("token=secret-123"));
        assertFalse(failure.legacyEnvelope().contains("/home/private/save.dat"));
        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic again =
                ProtocolJson.mapper().readValue(historic,
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class);
        assertEquals(failure.correlationId(), again.failure().correlationId(),
                "legacy synthesis must be deterministic");
    }

    @Test
    void legacyProtocolDecodeRejectsUnknownAndCoercedFields() throws Exception {
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue("""
                        {"provider":"enemies","entityId":{"value":"bad"},"property":"health",
                         "exceptionClass":"java.lang.IllegalStateException","message":"legacy|1|a|b",
                         "unknownField":"x"}""",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue("""
                        {"provider":"enemies","entityId":{"value":"bad","extra":1},
                         "property":"health","exceptionClass":"java.lang.IllegalStateException",
                         "message":"legacy|1|a|b"}""",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue("""
                        {"provider":123,"entityId":{"value":"bad"},"property":"health",
                         "exceptionClass":"java.lang.IllegalStateException","message":"legacy|1|a|b"}""",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue("""
                        {"provider":"enemies","entityId":{"value":"bad"},"property":true,
                         "exceptionClass":"java.lang.IllegalStateException","message":"legacy|1|a|b"}""",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue("""
                        {"provider":"enemies","entityId":{"value":"bad"},"property":"health",
                         "exceptionClass":false,"message":"legacy|1|a|b"}""",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue("""
                        {"provider":"enemies","entityId":{"value":"bad"},"property":"health",
                         "exceptionClass":"java.lang.IllegalStateException","message":[1,2]}""",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class));
    }

    @Test
    void legacyProtocolDecodeRejectsMalformedEntityId() throws Exception {
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue("""
                        {"provider":"enemies","entityId":"bad","property":"health",
                         "exceptionClass":"java.lang.IllegalStateException","message":"legacy|1|a|b"}""",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue("""
                        {"provider":"enemies","entityId":{},"property":"health",
                         "exceptionClass":"java.lang.IllegalStateException","message":"legacy|1|a|b"}""",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue("""
                        {"provider":"enemies","entityId":{"value":7},"property":"health",
                         "exceptionClass":"java.lang.IllegalStateException","message":"legacy|1|a|b"}""",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class));
    }

    @Test
    void legacyProtocolDecodeRejectsBlankOrOversizedExceptionClass() throws Exception {
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue("""
                        {"provider":"enemies","entityId":{"value":"bad"},"property":"health",
                         "exceptionClass":"","message":"legacy|1|a|java.lang.IllegalStateException"}""",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue("""
                        {"provider":"enemies","entityId":{"value":"bad"},"property":"health",
                         "exceptionClass":"   ","message":"legacy|1|a|java.lang.IllegalStateException"}""",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue("""
                        {"provider":"enemies","entityId":{"value":"bad"},"property":"health",
                         "exceptionClass":"","message":"token=secret-123"}""",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class));
        String over = "c".repeat(257);
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(
                        "{\"provider\":\"enemies\",\"entityId\":{\"value\":\"bad\"},"
                                + "\"property\":\"health\",\"exceptionClass\":\"" + over
                                + "\",\"message\":\"legacy|1|a|" + over + "\"}",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class));
    }

    @Test
    void legacyProtocolDecodeRejectsEnvelopeExceptionClassMismatch() throws Exception {
        JsonProcessingException mismatch = assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue("""
                        {"provider":"enemies","entityId":{"value":"bad"},"property":"health",
                         "exceptionClass":"com.example.Other",
                         "message":"legacy|1|a|com.example.Expected"}""",
                        CaptureDiagnostic.class));
        assertNoWireValuesInError(mismatch, "com.example.Expected", "com.example.Other");
        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue("""
                        {"provider":"enemies","entityId":{"value":"bad"},"property":"health",
                         "exceptionClass":"java.lang.IllegalStateException",
                         "message":"legacy|1|a|java.lang.RuntimeException"}""",
                        CaptureDiagnostic.class));
    }

    @Test
    void legacyProtocolDecodeSanitizesEnvelopeWithOversizedCategory() throws Exception {
        String category = "c".repeat(ApplicationFailureEvidence.MAX_CATEGORY_LENGTH + 1);
        assertDecodesToLegacyCapture("raw|" + category + "|secret-tail", "secret-tail", category);
    }

    @Test
    void legacyProtocolDecodeSanitizesOtherOutOfBoundsEnvelopes() throws Exception {
        String longCorrelation = "r".repeat(ApplicationFailureEvidence.MAX_CORRELATION_ID_LENGTH + 1);
        assertDecodesToLegacyCapture(longCorrelation + "|a|b", longCorrelation);
        String longClass = "f".repeat(ApplicationFailureEvidence.MAX_EXCEPTION_CLASS_LENGTH + 1);
        assertDecodesToLegacyCapture("legacy|1|a|" + longClass, longClass);
        assertDecodesToLegacyCapture("legacy|1|   |java.lang.IllegalStateException");
    }

    private static void assertDecodesToLegacyCapture(String message, String... absent) throws Exception {
        String payload = "{\"provider\":\"enemies\",\"entityId\":{\"value\":\"bad\"},"
                + "\"property\":\"health\",\"exceptionClass\":\"java.lang.IllegalStateException\","
                + "\"message\":\"" + message + "\"}";
        ApplicationFailureEvidence failure = ProtocolJson.mapper().readValue(payload,
                CaptureDiagnostic.class).failure();
        assertEquals("legacy.capture", failure.category());
        assertEquals("java.lang.IllegalStateException", failure.exceptionClass());
        assertTrue(failure.sanitizedDetail().isEmpty());
        for (String value : absent) {
            assertFalse(failure.correlationId().contains(value),
                    "correlation must not retain wire content '" + value + "'");
            assertFalse(failure.legacyEnvelope().contains(value),
                    "envelope must not retain wire content '" + value + "'");
        }
        ApplicationFailureEvidence again = ProtocolJson.mapper().readValue(payload,
                CaptureDiagnostic.class).failure();
        assertEquals(failure.correlationId(), again.correlationId(),
                "legacy synthesis must be deterministic");
    }

    private static void assertNoWireValuesInError(JsonProcessingException failure,
            String... values) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null) {
                for (String value : values) {
                    assertFalse(message.contains(value),
                            "decode error must not echo wire content '" + value + "'");
                }
            }
        }
    }

    @Test
    void legacyProtocolDecodeAcceptsBoundaryLengthExceptionClass() throws Exception {
        String klass = "e".repeat(256);
        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic decoded =
                ProtocolJson.mapper().readValue(
                        "{\"provider\":\"enemies\",\"entityId\":{\"value\":\"bad\"},"
                                + "\"property\":\"health\",\"exceptionClass\":\"" + klass
                                + "\",\"message\":\"legacy|1|a|" + klass + "\"}",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class);
        assertEquals(klass, decoded.failure().exceptionClass());
        assertEquals("legacy|1|a|" + klass, decoded.failure().legacyEnvelope());
        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic raw =
                ProtocolJson.mapper().readValue(
                        "{\"provider\":\"enemies\",\"entityId\":{\"value\":\"bad\"},"
                                + "\"property\":\"health\",\"exceptionClass\":\"" + klass
                                + "\",\"message\":\"token=secret-123\"}",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class);
        assertEquals(klass, raw.failure().exceptionClass());
        assertEquals("legacy.capture", raw.failure().category());
        assertFalse(raw.failure().legacyEnvelope().contains("secret-123"));
    }

    @Test
    void legacyProtocolDecodeAcceptsNullOptionals() throws Exception {
        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic decoded =
                ProtocolJson.mapper().readValue("""
                        {"provider":"enemies","entityId":null,"property":null,
                         "exceptionClass":"java.lang.IllegalStateException",
                         "message":"legacy|1|a|java.lang.IllegalStateException"}""",
                        io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic.class);
        assertTrue(decoded.entityId().isEmpty());
        assertTrue(decoded.property().isEmpty());
        assertEquals("legacy|1|a|java.lang.IllegalStateException",
                decoded.failure().legacyEnvelope());
    }

    @Test
    void legacyProtocolCommandStatusDiagnosticIsEnvelopeOnly() throws Exception {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("legacy-command"))
                .captureThread(Thread.currentThread())
                .clock(() -> 1)
                .commandDispatcher(applicationQueue::addLast)
                .build();
        runtime.start();
        runtime.commands().orElseThrow().submit("fail-1", 100, () -> {
            throw new IllegalStateException("token=secret-123 /home/private/save.dat");
        });
        applicationQueue.removeFirst().run();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeResponse response = service.execute(new RuntimeRequest(
                    ProtocolVersion.V1_2, "diag-status", "legacy-command",
                    new RuntimeCommand.CommandStatus("fail-1")));
            byte[] encoded = ProtocolJson.encode(response);
            String json = new String(encoded, StandardCharsets.UTF_8);
            assertFalse(json.contains("token=secret-123"));
            assertFalse(json.contains("sanitizedDetail"));
            assertFalse(json.contains("applicationFailure"));
            com.fasterxml.jackson.databind.JsonNode status = ProtocolJson.mapper()
                    .readTree(encoded).path("result").path("command").path("status");
            assertEquals("legacy-command|failure-1|command.failed"
                    + "|java.lang.IllegalStateException",
                    status.get("diagnostic").asText());
        }
    }

    @Test
    void protocolOneThroughThirteenFixturesRemainByteForByte() throws Exception {
        AgentRuntime runtime = verticalRuntime();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            assertFrozen(service, ProtocolVersion.V1,
                    "4c20fab177bca505eb384bd56049624f91a0ed9bf4c28cbc84a3980fc0a907de",
                    "78dc43e0ed20292a1ff8a9472e278262be9536ab137a9cba3ce02e1034ad7edd",
                    "b42390475ac5fb0c091ca918dace1f146d5e2146db842e3f4117ecc64f6aeeec");
            assertFrozen(service, ProtocolVersion.V1_1,
                    "6babdab1b227fe68d1440e3f25fe44d1e4c1fdb9903d72c121057ab131924703",
                    "c3b39a6d7a57bc5e1e0ab2ca5610b827631488c8e4731c2aea472d33fe8f71c6",
                    "6057f328648a0e56ccfb555095cf024be6e939ec9f5f3e8af614d401304ee43f");
            assertFrozen(service, ProtocolVersion.V1_2,
                    "0eebfc403e5c140aa2b967910c0c3f3b7bc72b35639433aa94d5bbb13b69b5fd",
                    "47817f02a1a8a1a03960774b46ff87fb8a102b393c1b3607c4c02243d7b453fd",
                    "99d4ccac7315705e41e90ebe697fb1aa54ed1fb1a3991e68d35ea17dcf84cd0f");
            assertFrozen(service, ProtocolVersion.V1_3,
                    "f7fcf176b660fcb12a822505292f0c827bd277e6ff33df11b3aee0ca3fc46603",
                    "c61d151a1d21d8e42efeaab83500ed4c642703ee400a85d0aa317464b7f0dd13",
                    "281f7f86da67a71e393b8437353f873a187a671271946490869ee26e17dbce1b");
            assertFrozen(service, ProtocolVersion.V1_4,
                    "66474a4f930893fca4701039e397aa3d6793b68d18904df02092f9dd82c29919",
                    "e43277ea45547b6764fba2f5e91b29590a563a8c8d13c0695f8d1ef44c0546c8",
                    "70ce9d5c8817b198e0cff59d76e38bc535a1a8cf7d0bad4a64ead7705129cbe6");
            assertFrozen(service, ProtocolVersion.V1_5,
                    "93c6a3fe3a66ccb638202a5f429d8a36e70ee96357dea0727688c0db54a06440",
                    "3a85836e1b12d6a8e790e26fca9cfe3ad31c0bbc014161eb0fefa51376aaa3ea",
                    "d809c74e3635d212e043a5ed6dc8c40d49dd348728cb0be4e4cd59a04988cf3d");
            assertFrozen(service, ProtocolVersion.V1_6,
                    "c930b5b07fd7f2a74b137ab055e84cb64e7c13a3235a05ccacb2a5477abf8f22",
                    "a6f99b3518a869fca405b64b06a33ff01a8b0e1924306a69f54f2c2d9451c744",
                    "bcced65294c91780f3548df06e4721a608352fc80753d4a8bfe0db1438d79979");
            assertFrozen(service, ProtocolVersion.V1_7,
                    "61831e9f8d976aeb6dd5ed2b8c4ecf3e6e31c81c70786c0409338bba5d0e7209",
                    "68859b847c34f670bebb1a3b448451747939233327eb82ab70cade5562bb970d",
                    "be1127c104975e92938fb84102c374076afe26c09a9abfc5eb3a92a127a9f597");
            assertFrozen(service, ProtocolVersion.V1_8,
                    "c7978ff0c2e774f56b6fc8416a2426848af90a41bfae2443ca7c2dc8791336c8",
                    "e12efb9f852191e6efc8c1661e5d0681a3e0cacfcb737201f3a0b48e586ea745",
                    "fd83b6e67305d1aaecfba7c8e73b1ac27f231d76a3616398157761128e87ea36");
            assertFrozen(service, ProtocolVersion.V1_9,
                    "a8a66349de68f44554b6673b43af75712bf5e7dd925f0936c8e9574a9d4ad4de",
                    "646e55ee74923745537e41452741777a25d606d94a76c21249b0519755e39f06",
                    "992dca2d8740bbf300b46b044c96c9c0cccdd6e7080e59d2948ef9c132fcfb4d");
            assertFrozen(service, ProtocolVersion.V1_10,
                    "a54a20fff79248a3ae9caf669808c92295c5cb7972f1d460499f4b3efb2a2521",
                    "4b44346d80f8a675ce6284670c8f2ad436d9689d0c6b39c51894831805a2e2a0",
                    "2fd500545f1ad6b446c74d70841561106d3250f5bb2129cf5ccee637dec21d7d");
            assertFrozen(service, ProtocolVersion.V1_11,
                    "59f3c4e68025b7fa51ab7c6b4ed90588b69f99cd49f2f041d9bf463efa1d8c07",
                    "06042a492b9382f61a2f2cdd3dfaa4d56909954a123b23a166116e949e86d730",
                    "a44797153e274f97bb671aead84d48bca714d59c59234eee1cc87636bbc14cb3");
            assertFrozen(service, ProtocolVersion.V1_12,
                    "b70ccff81abcbd4edeeb29a9043de0a864b3f45173d2cf8e1f4beb8bde2c3e9e",
                    "85ee0595e3907d330f9212f9358626cfd107ec4eb34bda3d51a568d80791be2d",
                    "adc80b048344d18f1ea56c3229d999ccc4613b54dda61b049f56cbe06d36e405");
            assertFrozen(service, ProtocolVersion.V1_13,
                    "3c89a7f23a041dbed53098acc35913e3db7b2e1b30f3e64ce94660682817a9b6",
                    "24bbe455c9652024c9890f91de508cc218792686abf15277bf41564cf1b378a4",
                    "c30e2106920951fe77b6991ea0d50b182f7128ae3d9e7b090a8b220d0e91c48a");
        }
    }

    private static void assertFrozen(RuntimeProtocolService service, ProtocolVersion version,
            String capabilitiesSha, String entitySha, String errorSha) throws Exception {
        RuntimeResponse capabilities = service.execute(new RuntimeRequest(
                version, "cap", "fixture", new RuntimeCommand.Capabilities()));
        assertEquals(capabilitiesSha, sha256(ProtocolJson.encode(capabilities)),
                "capabilities bytes for " + version);
        RuntimeResponse entity = service.execute(new RuntimeRequest(
                version, "entity", "fixture",
                new RuntimeCommand.Entity("enemy-1", 0, 1, 10)));
        assertEquals(entitySha, sha256(ProtocolJson.encode(entity)),
                "entity bytes for " + version);
        RuntimeResponse missing = service.execute(new RuntimeRequest(
                version, "missing", "fixture",
                new RuntimeCommand.Entity("absent", 0, 1, 10)));
        assertEquals(errorSha, sha256(ProtocolJson.encode(missing)),
                "error bytes for " + version);
    }

    private static String sha256(byte[] value) throws Exception {
        java.security.MessageDigest digest =
                java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest(value));
    }

    @Test
    void protocolTwoZeroNegotiatesAndReportsTheFullCapabilityMatrix() {
        RuntimeProtocolService service = new RuntimeProtocolService(new RuntimeRegistry());
        RuntimeResponse.Result.Sessions sessions = assertInstanceOf(
                RuntimeResponse.Result.Sessions.class,
                assertInstanceOf(RuntimeResponse.Success.class, service.execute(new RuntimeRequest(
                        ProtocolVersion.V2, "sessions", null,
                        new RuntimeCommand.Sessions()))).result());
        assertEquals(0, sessions.sessions().size());

        AgentRuntime runtime = verticalRuntime();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            service = new RuntimeProtocolService(registry);
            RuntimeResponse.Result.Capabilities current = capabilities(
                    service, ProtocolVersion.V2, "capabilities-v2", "fixture");
            assertEquals(ProtocolVersion.V2, current.protocolVersion());
            // The additive V2 tool is always available; base tools stay present.
            assertTrue(current.supportedTools().containsAll(List.of(
                    "runtime_entity", "runtime_frames", "runtime_entity_history")));
            // Every V1.13 capability is reported under V2.0 regardless of registration state.
            List<String> capabilityIds = current.capabilityReport().orElseThrow()
                    .capabilities().stream().map(RuntimeCapability::id).toList();
            assertTrue(capabilityIds.containsAll(List.of(
                    "command-dispatch", "execution-epochs", "resettable-scenarios",
                    "explicit-fact-attribution", "semantic-actions", "declarative-assertions",
                    "simulation-control", "registered-inputs", "checkpoints",
                    "runtime-ui-correlation", "recording", "determinism-comparison",
                    "removed-entity-history")));
            assertEquals(RuntimeCapability.Availability.AVAILABLE,
                    current.capabilityReport().orElseThrow().capabilities().stream()
                            .filter(capability -> capability.id().equals("removed-entity-history"))
                            .findFirst().orElseThrow().availability());
            assertEquals(RuntimeCapability.Availability.AVAILABLE,
                    current.capabilityReport().orElseThrow().capabilities().stream()
                            .filter(capability -> capability.id().equals("execution-epochs"))
                            .findFirst().orElseThrow().availability());
        }
    }

    @Test
    void protocolTwoZeroRunsEveryV1CommandWithFrozenResultShapes() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        int[] ticks = {0};
        int[] state = {7};
        int[] executions = {0};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("matrix"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.entities().register(EntityId.of("enemy-1"), EntityType.of("enemy"),
                () -> "Enemy", inspector -> inspector.property("health", () -> 75L));
        runtime.actions().register(ActionSpec.builder("player.attack")
                .description("Attack one target").requiredEntityId("targetEntity")
                .handler(parameters -> {
                    executions[0]++;
                    runtime.frame(1, () -> {});
                }).build());
        runtime.inputs().register(InputSpec.builder("key-down")
                .description("Registered key input")
                .requiredString("key")
                .handler(parameters -> {}).build());
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .tick(deltaNanos -> ticks[0]++)
                .condition("three-ticks", "Three ticks completed", () -> ticks[0] >= 3)
                .build());
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override public CheckpointHandle create() {
                return new TestCheckpoint(state[0]);
            }
            @Override public void restore(CheckpointHandle handle) {
                state[0] = ((TestCheckpoint) handle).value();
            }
            @Override public void dispose(CheckpointHandle handle) {}
        });
        runtime.scenarios().register(new io.github.teemuki8.libgdx.agent.runtime.core
                .ScenarioDescriptor("basic", Optional.of("Basic scenario")),
                context -> state[0] = 1);
        runtime.uiCorrelations().register(new io.github.teemuki8.libgdx.agent.runtime.core
                .UiBinding("health-binding", EntityId.of("enemy-1"), Optional.of("health"),
                        "battle-ui", "health-bar",
                        new io.github.teemuki8.libgdx.agent.runtime.core.UiBindingValidity(
                                Optional.empty(), Optional.empty(), Optional.empty())));
        runtime.uiCorrelations().recordFrame(
                new io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelation(
                        runtime.currentEpoch(), new FrameId(0), "battle-ui",
                        Optional.of("ui-frame-9"), Optional.of("render-token-9")));
        runtime.start();
        runtime.frame(1, () -> {});
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            assertEquals(runtime.sessionId(), publication.sessionId());
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            assertV2(service, new RuntimeCommand.Frames(0, 1, 10),
                    RuntimeResponse.Result.Frames.class);
            assertV2(service, new RuntimeCommand.Snapshot(null, null, false, null, false, 10),
                    RuntimeResponse.Result.Snapshot.class);
            assertV2(service, new RuntimeCommand.Entity("enemy-1", 0, 1, 10),
                    RuntimeResponse.Result.Entity.class);
            assertV2(service, new RuntimeCommand.Changes(0, 1, "enemy-1", "enemy", "health", 10),
                    RuntimeResponse.Result.Changes.class);
            assertV2(service, new RuntimeCommand.Events(
                            0, 1, "damage.", true, "enemy-1", "projectile-3", 10),
                    RuntimeResponse.Result.Events.class);
            assertV2(service, new RuntimeCommand.Decisions(
                            0, 1, "target-selection", "tower-1", "enemy-1", "nearest", 10),
                    RuntimeResponse.Result.Decisions.class);
            assertV2(service, new RuntimeCommand.EpochFrames(0, 10),
                    RuntimeResponse.Result.EpochFrames.class);
            assertV2(service, new RuntimeCommand.Scenarios(),
                    RuntimeResponse.Result.Scenarios.class);
            assertV2(service, new RuntimeCommand.Reset("basic", "reset-1", 1_000),
                    RuntimeResponse.Result.Reset.class);
            queue.removeFirst().run();
            assertV2(service, new RuntimeCommand.Reset("basic", "reset-1", 1_000),
                    RuntimeResponse.Result.Reset.class);
            assertV2(service, new RuntimeCommand.AttributedChanges(
                            0, 1, "enemy-1", "enemy", "health", null, null, 10),
                    RuntimeResponse.Result.Changes.class);
            assertV2(service, new RuntimeCommand.AttributedEvents(
                            0, 1, "damage.", true, "enemy-1", "projectile-3", null, null, 10),
                    RuntimeResponse.Result.Events.class);
            assertV2(service, new RuntimeCommand.AttributedDecisions(
                            0, 1, "target-selection", "tower-1", "enemy-1", "nearest",
                            null, null, 10),
                    RuntimeResponse.Result.Decisions.class);
            assertV2(service, new RuntimeCommand.Actions(), RuntimeResponse.Result.Actions.class);
            RuntimeCommand.Action action = new RuntimeCommand.Action("player.attack", "attack-1",
                    RuntimeValues.object(RuntimeValues.field(
                            "targetEntity", RuntimeValues.string("enemy-1"))),
                    "attack-172", 1_000);
            assertV2(service, action, RuntimeResponse.Result.Action.class);
            queue.removeFirst().run();
            assertV2(service, action, RuntimeResponse.Result.Action.class);
            assertV2(service, new RuntimeCommand.Assert(
                            new RuntimeAssertion.PropertyEquals(EntityId.of("enemy-1"), "health",
                                    RuntimeValues.integer(75)),
                            0, 1, 0, 8),
                    RuntimeResponse.Result.Assertion.class);
            assertV2(service, new RuntimeCommand.Control(
                            RuntimeCommand.ControlAction.STATUS, null, 0),
                    RuntimeResponse.Result.Control.class);
            RuntimeCommand.Control pause = new RuntimeCommand.Control(
                    RuntimeCommand.ControlAction.PAUSE, "pause-1", 1_000);
            assertV2(service, pause, RuntimeResponse.Result.Control.class);
            queue.removeFirst().run();
            assertV2(service, pause, RuntimeResponse.Result.Control.class);
            RuntimeCommand.Advance advance =
                    new RuntimeCommand.Advance("advance-1", 2, 16_666_667, 1_000);
            assertV2(service, advance, RuntimeResponse.Result.Control.class);
            queue.removeFirst().run();
            RuntimeCommand.Wait wait = new RuntimeCommand.Wait(
                    "wait-1", "three-ticks", null, 2, 16_666_667, 8, 1_000);
            assertV2(service, wait, RuntimeResponse.Result.Control.class);
            queue.removeFirst().run();
            assertV2(service, wait, RuntimeResponse.Result.Control.class);
            assertV2(service, new RuntimeCommand.Inputs(), RuntimeResponse.Result.Inputs.class);
            RuntimeCommand.Input input = new RuntimeCommand.Input(
                    "key-down", "key-1",
                    RuntimeValues.object(RuntimeValues.field(
                            "key", RuntimeValues.string("SPACE"))),
                    null, 1_000);
            assertV2(service, input, RuntimeResponse.Result.Input.class);
            queue.removeFirst().run();
            assertV2(service, new RuntimeCommand.Advance("input-tick", 1, 16_666_667, 1_000),
                    RuntimeResponse.Result.Control.class);
            queue.removeFirst().run();
            assertV2(service, input, RuntimeResponse.Result.Input.class);
            assertV2(service, new RuntimeCommand.Checkpoints(),
                    RuntimeResponse.Result.Checkpoints.class);
            RuntimeCommand.CheckpointCreate create = new RuntimeCommand.CheckpointCreate(
                    "save-1", "Before change", "create-1", 1_000);
            assertV2(service, create, RuntimeResponse.Result.Checkpoint.class);
            queue.removeFirst().run();
            assertV2(service, create, RuntimeResponse.Result.Checkpoint.class);
            RuntimeCommand.CheckpointRestore restore =
                    new RuntimeCommand.CheckpointRestore("save-1", "restore-1", 1_000);
            assertV2(service, restore, RuntimeResponse.Result.Checkpoint.class);
            queue.removeFirst().run();
            assertV2(service, restore, RuntimeResponse.Result.Checkpoint.class);
            assertV2(service, new RuntimeCommand.UiBindings(
                            "enemy-1", "health", null, null, 0, 0, null, 8),
                    RuntimeResponse.Result.UiBindings.class);
            assertV2(service, new RuntimeCommand.UiFrames(null, "render-token-9", 8),
                    RuntimeResponse.Result.UiFrames.class);
            RuntimeCommand.RecordingStart start = new RuntimeCommand.RecordingStart(
                    "run-1", "record-start-1", "basic", null, 42L,
                    RuntimeValues.object(), false, 1_000_000_000);
            assertV2(service, start, RuntimeResponse.Result.RecordingOperationResult.class);
            queue.removeFirst().run();
            assertV2(service, start, RuntimeResponse.Result.RecordingOperationResult.class);
            runtime.frame(1, () -> {});
            RuntimeCommand.RecordingStop stop = new RuntimeCommand.RecordingStop(
                    "run-1", "record-stop-1", 1_000_000_000);
            assertV2(service, stop, RuntimeResponse.Result.RecordingOperationResult.class);
            queue.removeFirst().run();
            assertV2(service, new RuntimeCommand.RecordingGet("run-1", 0, 10),
                    RuntimeResponse.Result.RecordingChunkResult.class);
            RuntimeCommand.DeterminismCheck determinism = new RuntimeCommand.DeterminismCheck(
                    "determinism-1", "basic", 7, RuntimeValues.object(), 2, 2, 1,
                    new io.github.teemuki8.libgdx.agent.runtime.core.DeterminismProfile(
                            new io.github.teemuki8.libgdx.agent.runtime.core
                                    .SnapshotComparisonScope(
                                            List.of(EntityId.of("counter")), List.of("value"),
                                            List.of(), false, false),
                            false),
                    1_000_000_000);
            assertV2(service, determinism, RuntimeResponse.Result.Determinism.class);
            queue.removeFirst().run();
            assertV2(service, determinism, RuntimeResponse.Result.Determinism.class);

            // A queued command status keeps its frozen 1.x bytes under 2.0 (no evidence).
            RuntimeCommand.CommandStatus status = new RuntimeCommand.CommandStatus("reset-1");
            RuntimeResponse.Success legacyStatus = assertInstanceOf(RuntimeResponse.Success.class,
                    service.execute(new RuntimeRequest(ProtocolVersion.V1_2, "status-1",
                            "matrix", status)));
            RuntimeResponse.Success v2Status = assertInstanceOf(RuntimeResponse.Success.class,
                    service.execute(new RuntimeRequest(ProtocolVersion.V2, "status-2",
                            "matrix", status)));
            assertArrayEquals(ProtocolJson.encode(new RuntimeResponse.Success(
                            ProtocolVersion.V2, "status", legacyStatus.result())),
                    ProtocolJson.encode(new RuntimeResponse.Success(
                            ProtocolVersion.V2, "status", v2Status.result())));
        }
    }

    private static void assertV2(RuntimeProtocolService service, RuntimeCommand command,
            Class<? extends RuntimeResponse.Result> resultType) {
        assertInstanceOf(resultType, v2Result(service, command));
    }

    private static RuntimeResponse.Result v2Result(
            RuntimeProtocolService service, RuntimeCommand command) {
        RuntimeResponse.Success success = assertInstanceOf(RuntimeResponse.Success.class,
                service.execute(new RuntimeRequest(
                        ProtocolVersion.V2, "v2-" + command.getClass().getSimpleName(),
                        "matrix", command)));
        assertEquals(ProtocolVersion.V2, success.version());
        return success.result();
    }

    @Test
    void protocolTwoZeroEntityHistoryPagesRemovedEntityWithTypedExpiration() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("removed-history"))
                .clock(() -> 1)
                .build();
        boolean[] include = {true};
        long[] health = {100};
        runtime.entities().registerSource("enemies", () -> include[0]
                ? Stream.of(io.github.teemuki8.libgdx.agent.runtime.core.InspectableEntity.of(
                        EntityId.of("enemy-1"), EntityType.of("enemy"),
                        () -> "Enemy", inspector -> inspector.property("health", () -> health[0])))
                : Stream.empty());
        runtime.start();
        for (int frame = 1; frame <= 5; frame++) {
            int value = frame;
            runtime.frame(1, () -> health[0] = 100 - value * 5);
        }
        include[0] = false;
        runtime.frame(1, () -> {});
        runtime.frame(1, () -> {});
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeResponse.Result.EntityHistory first = assertInstanceOf(
                    RuntimeResponse.Result.EntityHistory.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V2, "page-1", "removed-history",
                                    new RuntimeCommand.EntityHistory("enemy-1", 1, 7, 0, 2))))
                            .result());
            assertTrue(first.page().current().isEmpty());
            assertEquals(RuntimeValues.integer(75), first.page().finalRetainedState()
                    .orElseThrow().property("health").orElseThrow());
            assertEquals(List.of(1L, 2L), first.page().versions().stream()
                    .map(version -> version.frameId().value()).toList());
            assertEquals(2, first.page().nextVersionOffset());
            assertTrue(first.page().hasMoreVersions());
            assertFalse(first.page().requestedRangePartiallyEvicted());
            assertEquals(Optional.of(new FrameId(0)), first.page().oldestRetainedFrame());
            assertEquals(Optional.of(new FrameId(7)), first.page().newestRetainedFrame());

            RuntimeResponse.Result.EntityHistory second = assertInstanceOf(
                    RuntimeResponse.Result.EntityHistory.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V2, "page-2", "removed-history",
                                    new RuntimeCommand.EntityHistory("enemy-1", 1, 7, 2, 2))))
                            .result());
            assertEquals(List.of(3L, 4L), second.page().versions().stream()
                    .map(version -> version.frameId().value()).toList());
            assertEquals(4, second.page().nextVersionOffset());
            assertTrue(second.page().hasMoreVersions());

            RuntimeResponse.Result.EntityHistory last = assertInstanceOf(
                    RuntimeResponse.Result.EntityHistory.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V2, "page-3", "removed-history",
                                    new RuntimeCommand.EntityHistory("enemy-1", 1, 7, 4, 2))))
                            .result());
            assertEquals(List.of(5L), last.page().versions().stream()
                    .map(version -> version.frameId().value()).toList());
            assertEquals(5, last.page().nextVersionOffset());
            assertFalse(last.page().hasMoreVersions());

            // The V1 runtime_entity command keeps rejecting the removed entity; only the
            // additive 2.0 command preserves removed history.
            RuntimeResponse.Failure legacy = assertInstanceOf(RuntimeResponse.Failure.class,
                    service.execute(new RuntimeRequest(ProtocolVersion.V1_13, "legacy-entity",
                            "removed-history",
                            new RuntimeCommand.Entity("enemy-1", 1, 7, 10))));
            assertEquals(ProtocolErrorCode.ENTITY_NOT_FOUND, legacy.error().code());

            // The new command is gated to protocol 2.0.
            RuntimeResponse.Failure oldVersion = assertInstanceOf(RuntimeResponse.Failure.class,
                    service.execute(new RuntimeRequest(ProtocolVersion.V1_13, "old-command",
                            "removed-history",
                            new RuntimeCommand.EntityHistory("enemy-1", 1, 7, 0, 2))));
            assertEquals(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED,
                    oldVersion.error().code());
        }
    }

    @Test
    void protocolTwoZeroEntityHistoryReportsNotRetainedWhenEvicted() {
        io.github.teemuki8.libgdx.agent.runtime.core.RuntimeLimits limits =
                new io.github.teemuki8.libgdx.agent.runtime.core.RuntimeLimits(
                        1, 2_000, 5_000, 128, 256, 256, 64, 4_096, 256, 16, 1_000);
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("evicted-history"))
                .clock(() -> 1)
                .configuration(new io.github.teemuki8.libgdx.agent.runtime.core
                        .RuntimeConfiguration(true, limits))
                .build();
        boolean[] include = {true};
        runtime.entities().registerSource("enemies", () -> include[0]
                ? Stream.of(io.github.teemuki8.libgdx.agent.runtime.core.InspectableEntity.of(
                        EntityId.of("enemy-1"), EntityType.of("enemy"),
                        () -> "Enemy", inspector -> inspector.property("index", () -> 1L)))
                : Stream.empty());
        runtime.start();
        runtime.frame(1, () -> {});
        runtime.frame(1, () -> {});
        include[0] = false;
        runtime.frame(1, () -> {});
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeResponse.Failure failure = assertInstanceOf(RuntimeResponse.Failure.class,
                    service.execute(new RuntimeRequest(ProtocolVersion.V2, "evicted",
                            "evicted-history",
                            new RuntimeCommand.EntityHistory("enemy-1", 1, 3, 0, 10))));
            assertEquals(ProtocolErrorCode.ENTITY_HISTORY_NOT_RETAINED, failure.error().code());
        }
    }

    @Test
    void protocolTwoZeroCarriesStructuredFailureEvidenceOnFailingCommand() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("evidence-command"))
                .clock(() -> 1)
                .commandDispatcher(applicationQueue::addLast)
                .build();
        runtime.start();
        runtime.commands().orElseThrow().submit("fail-1", 100, () -> {
            throw new IllegalStateException("token=secret-123 /home/private/save.dat");
        });
        applicationQueue.removeFirst().run();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeCommand.CommandStatus query = new RuntimeCommand.CommandStatus("fail-1");

            RuntimeResponse.Result.CommandStatus legacy = assertInstanceOf(
                    RuntimeResponse.Result.CommandStatus.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V1_2, "legacy", "evidence-command",
                                    query))).result());
            assertTrue(legacy.applicationFailure().isEmpty());
            String legacyJson = new String(ProtocolJson.encode(service.execute(
                    new RuntimeRequest(ProtocolVersion.V1_2, "legacy", "evidence-command", query))),
                    StandardCharsets.UTF_8);
            assertFalse(legacyJson.contains("applicationFailure"));
            assertFalse(legacyJson.contains("sanitizedDetail"));
            assertEquals("evidence-command|failure-1|command.failed"
                    + "|java.lang.IllegalStateException",
                    legacy.command().status().orElseThrow().diagnostic().orElseThrow());

            RuntimeResponse.Result.CommandStatus v2 = assertInstanceOf(
                    RuntimeResponse.Result.CommandStatus.class,
                    assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                            new RuntimeRequest(ProtocolVersion.V2, "structured", "evidence-command",
                                    query))).result());
            ApplicationFailureEvidence evidence = v2.applicationFailure().orElseThrow();
            assertEquals("command.failed", evidence.category());
            assertEquals("java.lang.IllegalStateException", evidence.exceptionClass());
            assertEquals("evidence-command|failure-1", evidence.correlationId());
            String v2Json = new String(ProtocolJson.encode(service.execute(
                    new RuntimeRequest(ProtocolVersion.V2, "structured", "evidence-command",
                            query))), StandardCharsets.UTF_8);
            assertTrue(v2Json.contains("\"applicationFailure\""));
            assertFalse(v2Json.contains("token=secret-123"));
            assertFalse(v2Json.contains("sanitizedDetail"));
        }
    }

    @Test
    void protocolTwoZeroRejectsUnknownEntityHistoryCommandFields() {
        assertThrows(ProtocolJson.ProtocolJsonException.class, () ->
                ProtocolJson.decodeRequest("""
                        {"version":{"major":2,"minor":0},"requestId":"x","sessionId":"fixture",
                         "command":{"type":"entityHistory","entityId":"enemy-1",
                         "fromFrame":0,"toFrame":1,"versionOffset":0,"versionLimit":10,
                         "unknown":true}}""".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ProtocolJson.ProtocolJsonException.class, () ->
                ProtocolJson.decodeRequest("""
                        {"version":{"major":2,"minor":0},"requestId":"x","sessionId":"fixture",
                         "command":{"type":"entityHistory","entityId":"enemy-1",
                         "fromFrame":0,"toFrame":1,"versionOffset":-1,"versionLimit":10}}"""
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void protocolTwoZeroCarriesStructuredEvidenceAcrossCallbackFamilies() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("evidence-families"))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .build();
        runtime.scenarios().register(new io.github.teemuki8.libgdx.agent.runtime.core
                .ScenarioDescriptor("boom", Optional.of("Boom scenario")),
                context -> {
                    throw new IllegalStateException(
                            "token=secret-123 /home/private/save.dat");
                });
        runtime.actions().register(ActionSpec.builder("boom-action")
                .description("Throwing action")
                .handler(parameters -> {
                    throw new IllegalStateException(
                            "token=secret-123 /home/private/save.dat");
                }).build());
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {
                    throw new IllegalStateException(
                            "token=secret-123 /home/private/save.dat");
                })
                .resume(() -> {})
                .tick(deltaNanos -> {})
                .build());
        runtime.start();
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            String secret = "token=secret-123";

            // Reset family.
            RuntimeCommand.Reset reset = new RuntimeCommand.Reset("boom", "reset-1", 1_000);
            service.execute(new RuntimeRequest(
                    ProtocolVersion.V1_4, "reset", "evidence-families", reset));
            queue.removeFirst().run();
            assertEvidence(service, ProtocolVersion.V2, reset,
                    RuntimeResponse.Result.Reset.class, secret, "reset-1");
            assertLegacyOnly(service, ProtocolVersion.V1_4, reset, "reset-1");

            // Action family.
            RuntimeCommand.Action action = new RuntimeCommand.Action(
                    "boom-action", "action-1", RuntimeValues.object(), null, 1_000);
            service.execute(new RuntimeRequest(
                    ProtocolVersion.V1_6, "action", "evidence-families", action));
            queue.removeFirst().run();
            assertEvidence(service, ProtocolVersion.V2, action,
                    RuntimeResponse.Result.Action.class, secret, "action-1");
            assertLegacyOnly(service, ProtocolVersion.V1_6, action, "action-1");

            // Control family.
            RuntimeCommand.Control pause = new RuntimeCommand.Control(
                    RuntimeCommand.ControlAction.PAUSE, "pause-1", 1_000);
            service.execute(new RuntimeRequest(
                    ProtocolVersion.V1_8, "pause", "evidence-families", pause));
            queue.removeFirst().run();
            assertEvidence(service, ProtocolVersion.V2, pause,
                    RuntimeResponse.Result.Control.class, secret, "pause-1");
            assertLegacyOnly(service, ProtocolVersion.V1_8, pause, "pause-1");

            // Recording family: the internal start callback fails on a second concurrent
            // recording with a bounded structured failure.
            RuntimeCommand.RecordingStart first = new RuntimeCommand.RecordingStart(
                    "run-1", "record-1", "boom", null, 1L,
                    RuntimeValues.object(), false, 1_000_000_000);
            service.execute(new RuntimeRequest(
                    ProtocolVersion.V1_12, "record-1", "evidence-families", first));
            queue.removeFirst().run();
            RuntimeCommand.RecordingStart second = new RuntimeCommand.RecordingStart(
                    "run-2", "record-2", "boom", null, 2L,
                    RuntimeValues.object(), false, 1_000_000_000);
            service.execute(new RuntimeRequest(
                    ProtocolVersion.V1_12, "record-2", "evidence-families", second));
            queue.removeFirst().run();
            assertEvidence(service, ProtocolVersion.V2, second,
                    RuntimeResponse.Result.RecordingOperationResult.class, null, "record-2");
            assertLegacyOnly(service, ProtocolVersion.V1_12, second, "record-2");
        }
    }

    private static void assertEvidence(RuntimeProtocolService service, ProtocolVersion version,
            RuntimeCommand command, Class<? extends RuntimeResponse.Result> resultType,
            String absentSecret, String requestId) {
        RuntimeResponse.Result result = assertInstanceOf(resultType,
                assertInstanceOf(RuntimeResponse.Success.class, service.execute(
                        new RuntimeRequest(version, "v2-" + requestId, "evidence-families",
                                command))).result());
        ApplicationFailureEvidence evidence = switch (result) {
            case RuntimeResponse.Result.Reset value -> value.applicationFailure().orElseThrow();
            case RuntimeResponse.Result.Action value -> value.applicationFailure().orElseThrow();
            case RuntimeResponse.Result.Control value -> value.applicationFailure().orElseThrow();
            case RuntimeResponse.Result.RecordingOperationResult value ->
                    value.applicationFailure().orElseThrow();
            default -> throw new AssertionError("unexpected result " + result);
        };
        assertFalse(evidence.category().isBlank());
        assertFalse(evidence.exceptionClass().isBlank());
        assertEquals("evidence-families|failure-", evidence.correlationId().substring(0,
                "evidence-families|failure-".length()));
        String json = new String(ProtocolJson.encode(service.execute(
                new RuntimeRequest(version, "v2-" + requestId, "evidence-families", command))),
                StandardCharsets.UTF_8);
        assertTrue(json.contains("\"applicationFailure\""), json);
        if (absentSecret != null) {
            assertFalse(json.contains(absentSecret), json);
        }
        assertFalse(json.contains("sanitizedDetail"), json);
    }

    private static void assertLegacyOnly(RuntimeProtocolService service, ProtocolVersion version,
            RuntimeCommand command, String requestId) {
        RuntimeResponse.Success success = assertInstanceOf(RuntimeResponse.Success.class,
                service.execute(new RuntimeRequest(version, "legacy-" + requestId,
                        "evidence-families", command)));
        String json = new String(ProtocolJson.encode(success), StandardCharsets.UTF_8);
        assertFalse(json.contains("\"applicationFailure\""), json);
        assertFalse(json.contains("token=secret-123"), json);
    }

    @Test
    void protocolProjectsCaptureDiagnosticsLegacyUnderV1AndStructuredUnderV2() throws Exception {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("diag-v2"))
                .clock(() -> 1)
                .build();
        runtime.entities().register(EntityId.of("bad"), EntityType.of("enemy"),
                () -> "Bad", inspector -> inspector.property("health",
                        (java.util.function.Supplier<
                                io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue>) () -> {
                            throw new IllegalStateException(
                                    "token=secret-123 /home/private/save.dat");
                        }));
        runtime.start();
        runtime.frame(1, () -> {});
        RuntimeRegistry registry = new RuntimeRegistry();
        try (PublishedRuntime publication = registry.publish(runtime)) {
            RuntimeProtocolService service = new RuntimeProtocolService(registry);
            RuntimeCommand.Snapshot query =
                    new RuntimeCommand.Snapshot(null, null, false, null, false, 10);

            String v1 = new String(ProtocolJson.encode(service.execute(
                    new RuntimeRequest(ProtocolVersion.V1, "diag-v1", "diag-v2", query))),
                    StandardCharsets.UTF_8);
            assertTrue(v1.contains("\"exceptionClass\"") && v1.contains("\"message\""), v1);
            assertFalse(v1.contains("\"failure\""), v1);
            assertFalse(v1.contains("token=secret-123"), v1);

            String v2 = new String(ProtocolJson.encode(service.execute(
                    new RuntimeRequest(ProtocolVersion.V2, "diag-v2", "diag-v2", query))),
                    StandardCharsets.UTF_8);
            assertTrue(v2.contains("\"failure\""), v2);
            assertTrue(v2.contains("\"category\""), v2);
            assertTrue(v2.contains("\"correlationId\""), v2);
            assertTrue(v2.contains("\"exceptionClass\""), v2);
            assertTrue(v2.contains("provider.property"), v2);
            assertFalse(v2.contains("\"message\""), v2);
            assertFalse(v2.contains("token=secret-123"), v2);

            // Both closed wire shapes decode back into structured evidence.
            RuntimeResponse v1Decoded = ProtocolJson.decodeResponse(
                    ProtocolJson.encode(service.execute(new RuntimeRequest(
                            ProtocolVersion.V1, "diag-v1", "diag-v2", query))));
            RuntimeResponse v2Decoded = ProtocolJson.decodeResponse(
                    ProtocolJson.encode(service.execute(new RuntimeRequest(
                            ProtocolVersion.V2, "diag-v2", "diag-v2", query))));
            assertInstanceOf(RuntimeResponse.Success.class, v1Decoded);
            assertInstanceOf(RuntimeResponse.Success.class, v2Decoded);
        }
    }

    private static AgentRuntime verticalRuntime() {
        long[] health = {100};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("fixture")).build();
        runtime.entities().register(EntityId.of("enemy-1"), EntityType.of("enemy"),
                () -> "Enemy 1", inspector -> inspector
                        .property("health", () -> health[0])
                        .property("position", () -> RuntimeValues.vector2(20, 5)));
        runtime.start();
        runtime.frame(16_000_000, () -> {
            runtime.emit(EventSpec.type("damage.applied")
                    .subject(EntityId.of("enemy-1"))
                    .source(EntityId.of("projectile-3"))
                    .attribute("amount", RuntimeValues.integer(25)));
            health[0] = 75;
        });
        return runtime;
    }

    private static RuntimeResponse.Result.Capabilities capabilities(
            RuntimeProtocolService service, ProtocolVersion version,
            String requestId, String sessionId) {
        RuntimeResponse.Success response = assertInstanceOf(RuntimeResponse.Success.class,
                service.execute(new RuntimeRequest(version, requestId, sessionId,
                        new RuntimeCommand.Capabilities())));
        assertEquals(version, response.version());
        return assertInstanceOf(RuntimeResponse.Result.Capabilities.class, response.result());
    }
}
