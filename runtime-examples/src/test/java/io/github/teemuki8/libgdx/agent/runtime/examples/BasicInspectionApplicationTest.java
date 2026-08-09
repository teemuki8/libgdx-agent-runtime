package io.github.teemuki8.libgdx.agent.runtime.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class BasicInspectionApplicationTest {
    @Test
    @Timeout(90)
    void hiddenApplicationCapturesCopyableStructuredEvidence() throws Exception {
        Path evidence = Files.createTempFile("agent-runtime-basic-example-", ".txt");
        Files.deleteIfExists(evidence);
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp",
                System.getProperty("example.classpath"),
                BasicInspectionApplication.class.getName(), evidence.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean exited = process.waitFor(
                Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertTrue(exited, () -> "basic example timed out: " + output);
        assertEquals(0, process.exitValue(), () -> "basic example failed: " + output);
        String facts = Files.readString(evidence);
        assertTrue(facts.contains("session=basic-inspection-example"));
        assertTrue(facts.contains("latestFrame=1"));
        assertTrue(facts.contains("health=75"));
        assertTrue(facts.contains("eventCount=1"));
        assertTrue(facts.contains("captureThreadCorrect=true"));
        assertFalse(facts.contains("Exception"));
        Files.deleteIfExists(evidence);
    }
}
