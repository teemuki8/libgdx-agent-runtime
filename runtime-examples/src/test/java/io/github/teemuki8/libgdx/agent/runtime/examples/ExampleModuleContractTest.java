package io.github.teemuki8.libgdx.agent.runtime.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class ExampleModuleContractTest {
    @Test
    void examplesAreConsumerOnlyAndCurrent() throws IOException {
        String rootBuild = Files.readString(repositoryFile("build.gradle.kts"));
        String publishedModules = rootBuild.substring(
                rootBuild.indexOf("val publishedModules"),
                rootBuild.indexOf("val artifactNames"));
        assertFalse(publishedModules.contains("runtime-examples"));
        assertEquals("2.0.1-SNAPSHOT", capture(rootBuild,
                "orElse\\(\"([^\"]+)\"\\)"));

        String versions = Files.readString(repositoryFile("gradle/libs.versions.toml"));
        assertEquals("1.14.2", capture(versions, "gdx = \"([^\"]+)\""));
        assertNotNull(System.getProperty("example.classpath"));
        assertTrue(Files.exists(repositoryFile("runtime-examples/gradle.lockfile")));
        String verification = Files.readString(repositoryFile(
                ".agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh"));
        assertTrue(verification.contains("-x :runtime-examples:test"));
        assertTrue(verification.contains(":runtime-examples:testClasses"));
    }

    private static Path repositoryFile(String relativePath) {
        return Path.of("..").resolve(relativePath).normalize();
    }

    private static String capture(String content, String expression) {
        var matcher = Pattern.compile(expression).matcher(content);
        if (!matcher.find()) {
            throw new AssertionError("expected metadata pattern: " + expression);
        }
        return matcher.group(1);
    }
}
