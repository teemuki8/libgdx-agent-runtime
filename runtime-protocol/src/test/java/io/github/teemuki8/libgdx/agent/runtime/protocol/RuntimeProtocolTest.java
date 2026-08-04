package io.github.teemuki8.libgdx.agent.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.ActionSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.BaselineKind;
import io.github.teemuki8.libgdx.agent.runtime.core.CommandState;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointHandle;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointOperation;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointProvider;
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
                        new ProtocolVersion(2, 0), "v", null, new RuntimeCommand.Sessions())));
        assertEquals(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED, version.error().code());
        assertEquals("1.0,1.1,1.2,1.3,1.4,1.5,1.6,1.7,1.8,1.9,1.10,1.11",
                version.error().details().get("supported"));

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

    private record TestCheckpoint(int value) implements CheckpointHandle {}

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
