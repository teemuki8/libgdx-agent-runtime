package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class Lwjgl3FixtureSmokeTest {
    @Test
    @Timeout(90)
    void realHiddenLwjgl3ApplicationCapturesDeterministicEvidence() throws Exception {
        Path evidence = Files.createTempFile("agent-runtime-fixture-", ".txt");
        Files.deleteIfExists(evidence);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(System.getProperty("fixture.classpath"));
        command.add(FixtureApplication.class.getName());
        command.add(evidence.toString());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(exited, () -> "fixture timed out: " + output);
        assertEquals(0, process.exitValue(), () -> "fixture failed: " + output);
        assertTrue(Files.isRegularFile(evidence));
        String facts = Files.readString(evidence);
        assertTrue(facts.contains("session=deterministic-fixture"));
        assertTrue(facts.contains("latestFrame=45"));
        assertTrue(facts.contains("healthChanges=1"));
        assertTrue(facts.contains("decisions=1"));
        assertTrue(facts.contains("enemy2Present=false"));
        assertTrue(facts.contains("controlledFrames=2"));
        assertTrue(facts.contains("controlledDeltaNanos=[16000000, 16000000]"));
        assertTrue(facts.contains("dispatchThreadCorrect=true"));
        assertTrue(facts.contains("mutationThreadCorrect=true"));
        assertFalse(facts.contains("Exception"));
        Files.deleteIfExists(evidence);
    }

    @Test
    @Timeout(90)
    void realHiddenLwjgl3McpFixtureServesControlAndActionOverStdio() throws Exception {
        Path errors = Files.createTempFile("agent-runtime-mcp-fixture-", ".txt");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(System.getProperty("fixture.classpath"));
        command.add(McpFixtureApplication.class.getName());
        Process process = new ProcessBuilder(command).redirectError(errors.toFile()).start();
        try {
            try (BufferedWriter requests = new BufferedWriter(new OutputStreamWriter(
                            process.getOutputStream(), StandardCharsets.UTF_8));
                    BufferedReader responses = new BufferedReader(new InputStreamReader(
                            process.getInputStream(), StandardCharsets.UTF_8))) {
                String initialized = exchange(requests, responses,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                                + "\"clientInfo\":{\"name\":\"native-fixture-test\","
                                + "\"version\":\"1\"}}}");
                assertTrue(initialized.contains("\"result\""), initialized);
                requests.write(
                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
                requests.flush();

                awaitSucceeded(requests, responses,
                        "{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"tools/call\","
                                + "\"params\":{\"name\":\"runtime_control\",\"arguments\":{"
                                + "\"sessionId\":\"deterministic-fixture\","
                                + "\"action\":\"PAUSE\","
                                + "\"controlRequestId\":\"native-stdio-pause\","
                                + "\"timeoutNanos\":1000000000}}}",
                        2);
                awaitSucceeded(requests, responses,
                        "{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"tools/call\","
                                + "\"params\":{\"name\":\"runtime_advance\",\"arguments\":{"
                                + "\"sessionId\":\"deterministic-fixture\","
                                + "\"controlRequestId\":\"native-stdio-advance\","
                                + "\"ticks\":2,\"deltaNanos\":16000000,"
                                + "\"timeoutNanos\":1000000000}}}",
                        100);
                awaitSucceeded(requests, responses,
                        "{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"tools/call\","
                                + "\"params\":{\"name\":\"runtime_action\",\"arguments\":{"
                                + "\"sessionId\":\"deterministic-fixture\","
                                + "\"action\":\"set-tower-state\","
                                + "\"actionRequestId\":\"native-stdio-action\","
                                + "\"correlationId\":\"fixture-action-1\","
                                + "\"parameters\":{\"state\":\"ALERT\"},"
                                + "\"timeoutNanos\":1000000000}}}",
                        200);
            }
            boolean exited =
                    process.waitFor(Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS);
            String errorOutput = Files.readString(errors);
            assertTrue(exited, () -> "MCP fixture timed out: " + errorOutput);
            assertEquals(0, process.exitValue(), () -> "MCP fixture failed: " + errorOutput);
            assertFalse(errorOutput.contains("Exception"), errorOutput);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(errors);
        }
    }

    private static void awaitSucceeded(
            BufferedWriter requests,
            BufferedReader responses,
            String requestTemplate,
            int firstId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        for (int id = firstId; System.nanoTime() < deadline; id++) {
            String response = exchange(requests, responses, requestTemplate.formatted(id));
            if (response.contains("SUCCEEDED")) {
                return;
            }
            assertTrue(response.contains("QUEUED") || response.contains("EXECUTING"), response);
        }
        throw new AssertionError("MCP fixture command did not complete before its deadline");
    }

    private static String exchange(
            BufferedWriter requests, BufferedReader responses, String request) throws Exception {
        requests.write(request);
        requests.newLine();
        requests.flush();
        String response = responses.readLine();
        assertTrue(response != null, "MCP fixture closed stdout before responding");
        return response;
    }

}
