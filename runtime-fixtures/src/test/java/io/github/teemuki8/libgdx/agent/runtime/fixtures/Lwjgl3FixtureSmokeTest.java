package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertFalse(facts.contains("Exception"));
        Files.deleteIfExists(evidence);
    }
}
