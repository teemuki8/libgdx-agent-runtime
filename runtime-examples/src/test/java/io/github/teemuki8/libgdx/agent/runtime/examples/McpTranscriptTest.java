package io.github.teemuki8.libgdx.agent.runtime.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.teemuki8.libgdx.agent.runtime.mcp.RuntimeToolHandler;
import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolJson;
import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolVersion;
import io.github.teemuki8.libgdx.agent.runtime.protocol.PublishedRuntime;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeCommand;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRequest;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeResponse;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpTranscriptTest {
    private static final ObjectMapper MAPPER = ProtocolJson.mapper();

    @Test
    @Timeout(30)
    void machineReadableTranscriptReplaysThroughClosedLiveTools() throws Exception {
        Transcript transcript = transcript();
        assertEquals(List.of(
                "sessions", "capabilities", "scenarios", "reset", "pause",
                "replay recording start", "input", "advance", "entity", "events",
                "frame assertion", "assertion", "replay recording stop", "replay",
                "determinism"),
                transcript.steps().stream().map(Step::name).toList());

        try (ControlledWorkflowExample example = ControlledWorkflowExample.createQueued()) {
            RuntimeRegistry registry = new RuntimeRegistry();
            try (PublishedRuntime publication = registry.publish(example.runtime());
                    RuntimeToolHandler handler =
                            new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
                assertEquals(ControlledWorkflowExample.SESSION_ID, publication.sessionId());
                for (Step step : transcript.steps()) {
                    McpSchema.CallToolRequest request = McpSchema.CallToolRequest
                            .builder(step.tool()).arguments(step.arguments()).build();
                    McpSchema.CallToolResult result = handler.handle(request)
                            .block(Duration.ofSeconds(5));
                    assertNotNull(result, step.name());
                    assertFalse(result.isError(), step.name() + ": " + result.content());
                    if (step.dispatch()) {
                        example.drainAll();
                        result = handler.handle(request).block(Duration.ofSeconds(5));
                        assertNotNull(result, step.name());
                        assertFalse(result.isError(), step.name() + ": " + result.content());
                    }
                    JsonNode actual = MAPPER.valueToTree(result.structuredContent());
                    for (Map.Entry<String, JsonNode> expectation : step.expected().entrySet()) {
                        JsonNode observed = actual.at(expectation.getKey());
                        assertFalse(observed.isMissingNode(),
                                step.name() + " missing result pointer " + expectation.getKey());
                        assertJsonValueEquals(expectation.getValue(), observed,
                                step.name() + " result pointer " + expectation.getKey());
                    }
                }
                McpSchema.CallToolResult unknown = handler.handle(
                        McpSchema.CallToolRequest.builder("runtime_sessions")
                                .arguments(Map.of("script", "inspect()"))
                                .build()).block(Duration.ofSeconds(5));
                assertNotNull(unknown);
                assertTrue(unknown.isError(), "unknown MCP argument must fail closed");
            }
        }
    }

    @Test
    void transcriptSchemaRejectsUnknownFields() throws Exception {
        String json;
        try (InputStream input = resource()) {
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThrows(Exception.class,
                () -> MAPPER.readValue(json.replaceFirst("\"steps\"", "\"script\":{},\"steps\""),
                        Transcript.class));
    }

    @Test
    void representativeProtocol25EnvelopesRoundTripExactly() {
        RuntimeRequest request = new RuntimeRequest(
                ProtocolVersion.V2_5, "example-request", null, new RuntimeCommand.Sessions());
        assertEquals(request, ProtocolJson.decodeRequest(ProtocolJson.encode(request)));

        RuntimeResponse response = new RuntimeResponse.Success(
                ProtocolVersion.V2_5, "example-request",
                new RuntimeResponse.Result.Sessions(List.of()));
        assertEquals(response, ProtocolJson.decodeResponse(ProtocolJson.encode(response)));
    }

    @Test
    @Timeout(90)
    void sameJvmLwjgl3LauncherReservesStdoutForMcp() throws Exception {
        assertTrue(System.getProperty("example.mcp.launcher")
                .endsWith("run-mcp-example-xvfb.sh"));
        Path errors = Files.createTempFile("agent-runtime-example-mcp-", ".txt");
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("example.mcp.launcher"));
        Process process = new ProcessBuilder(command).redirectError(errors.toFile()).start();
        try {
            try (BufferedWriter requests = new BufferedWriter(new OutputStreamWriter(
                            process.getOutputStream(), StandardCharsets.UTF_8));
                    BufferedReader responses = new BufferedReader(new InputStreamReader(
                            process.getInputStream(), StandardCharsets.UTF_8))) {
                String initialized = exchange(requests, responses,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                                + "\"params\":{\"protocolVersion\":\"2025-06-18\","
                                + "\"capabilities\":{},\"clientInfo\":{\"name\":"
                                + "\"example-test\",\"version\":\"1\"}}}");
                assertTrue(initialized.contains("\"result\""), initialized);
                requests.write("{\"jsonrpc\":\"2.0\","
                        + "\"method\":\"notifications/initialized\"}\n");
                requests.flush();
                String sessions = exchange(requests, responses,
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                                + "\"params\":{\"name\":\"runtime_sessions\","
                                + "\"arguments\":{}}}");
                assertTrue(sessions.contains("controlled-workflow-example"), sessions);
            }
            assertTrue(process.waitFor(60, TimeUnit.SECONDS),
                    () -> "launcher timed out: " + read(errors));
            assertEquals(0, process.exitValue(), () -> "launcher failed: " + read(errors));
            assertFalse(read(errors).contains("Exception"), read(errors));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(errors);
        }
    }

    private static Transcript transcript() throws Exception {
        try (InputStream input = resource()) {
            return MAPPER.readValue(input, Transcript.class);
        }
    }

    private static InputStream resource() {
        InputStream input = McpTranscriptTest.class.getResourceAsStream(
                "/transcripts/controlled-workflow.json");
        return java.util.Objects.requireNonNull(input, "controlled workflow transcript");
    }

    private static String exchange(
            BufferedWriter requests, BufferedReader responses, String request) throws Exception {
        requests.write(request);
        requests.newLine();
        requests.flush();
        String response = responses.readLine();
        assertNotNull(response, "MCP launcher closed stdout before responding");
        MAPPER.readTree(response);
        return response;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException failure) {
            return failure.toString();
        }
    }

    private static void assertJsonValueEquals(
            JsonNode expected, JsonNode observed, String message) {
        if (expected.isNumber() && observed.isNumber()) {
            assertEquals(0, expected.decimalValue().compareTo(observed.decimalValue()), message);
        } else {
            assertEquals(expected, observed, message);
        }
    }

    private record Transcript(List<Step> steps) {
        private Transcript {
            steps = List.copyOf(steps);
        }
    }

    private record Step(String name, String tool, Map<String, Object> arguments,
            boolean dispatch, Map<String, JsonNode> expected) {
        private Step {
            arguments = Map.copyOf(new LinkedHashMap<>(arguments));
            expected = Map.copyOf(new LinkedHashMap<>(expected));
        }
    }
}
