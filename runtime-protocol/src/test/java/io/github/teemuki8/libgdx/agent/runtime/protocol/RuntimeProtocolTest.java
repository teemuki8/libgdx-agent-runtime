package io.github.teemuki8.libgdx.agent.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.EventSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import java.nio.charset.StandardCharsets;
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
        assertEquals("1.0,1.1", version.error().details().get("supported"));

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
