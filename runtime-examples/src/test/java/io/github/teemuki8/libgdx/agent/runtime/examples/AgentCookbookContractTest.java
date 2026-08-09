package io.github.teemuki8.libgdx.agent.runtime.examples;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.teemuki8.libgdx.agent.runtime.mcp.RuntimeToolCatalog;
import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolJson;
import io.github.teemuki8.libgdx.agent.runtime.protocol.PublishedRuntime;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AgentCookbookContractTest {
    private static final List<String> HEADINGS = List.of(
            "## Task index",
            "## Instrument and inspect state",
            "## Query changes, events, and decisions",
            "## Run controlled scenarios and input",
            "## Host same-JVM stdio MCP",
            "## Diagnose incomplete and failed evidence",
            "## Use the deterministic Box2D example");
    private static final List<String> EXAMPLES = List.of(
            "BasicInspectionApplication.java",
            "ControlledWorkflowExample.java",
            "SameJvmMcpApplication.java",
            "DeterministicBox2dExample.java");

    @Test
    void cookbookIndexesCompiledExamplesVersionsAndMandatoryUpdatePolicy() throws Exception {
        String cookbook = read("docs/guides/agent-cookbook.md");
        HEADINGS.forEach(heading -> assertTrue(cookbook.contains(heading), heading));
        EXAMPLES.forEach(name -> {
            assertTrue(cookbook.contains(name), name);
            assertTrue(Files.exists(source(name)), source(name).toString());
        });
        assertTrue(cookbook.contains("2.0.0"));
        assertTrue(cookbook.contains("2.0.1-SNAPSHOT"));
        assertTrue(cookbook.contains("absence is not proof"));
        assertTrue(cookbook.contains("INCONCLUSIVE"));
        assertTrue(cookbook.contains("UNSUPPORTED_VERSION"));
        assertTrue(cookbook.contains("WRONG_THREAD"));
        assertTrue(cookbook.contains("unknown mutation outcome"));
        assertTrue(cookbook.contains("runSameJvmMcpExample"));

        String readme = read("README.md");
        assertTrue(readme.contains("runtime-examples"));
        assertTrue(readme.contains("Agent cookbook"));
        assertTrue(read("docs/guides/getting-started.md").contains("runtime-examples"));
        assertTrue(read("docs/guides/agent-tools.md").contains("controlled-workflow.json"));
        assertTrue(read("CHANGELOG.md").contains("tested agent cookbook"));

        String agents = read("AGENTS.md");
        String skill = read(".agents/skills/libgdx-agent-runtime-dev/SKILL.md");
        assertTrue(agents.contains("in the same pull request"));
        assertTrue(skill.contains("in the same pull request"));

        String rootBuild = read("build.gradle.kts");
        String published = rootBuild.substring(rootBuild.indexOf("val publishedModules"),
                rootBuild.indexOf("val artifactNames"));
        assertFalse(published.contains("runtime-examples"));
        assertTrue(read("runtime-examples/build.gradle.kts")
                .contains("runSameJvmMcpExample"));
    }

    @Test
    void transcriptToolsRemainInTheAdvertisedClosedCatalog() throws Exception {
        Set<String> transcriptTools;
        try (InputStream input = AgentCookbookContractTest.class.getResourceAsStream(
                "/transcripts/controlled-workflow.json")) {
            JsonNode root = ProtocolJson.mapper().readTree(
                    java.util.Objects.requireNonNull(input, "controlled transcript"));
            transcriptTools = root.required("steps").valueStream()
                    .map(step -> step.required("tool").textValue())
                    .collect(Collectors.toUnmodifiableSet());
        }
        try (ControlledWorkflowExample example = ControlledWorkflowExample.createQueued()) {
            RuntimeRegistry registry = new RuntimeRegistry();
            try (PublishedRuntime publication = registry.publish(example.runtime())) {
                RuntimeProtocolService protocol = new RuntimeProtocolService(registry);
                RuntimeToolCatalog catalog = new RuntimeToolCatalog(
                        protocol.toolNames(), protocol.actionCatalog(), protocol.inputCatalog());
                assertTrue(catalog.toolNames().containsAll(transcriptTools),
                        () -> "missing transcript tools: " + transcriptTools.stream()
                                .filter(tool -> !catalog.toolNames().contains(tool)).toList());
                assertTrue(publication.sessionId().equals(ControlledWorkflowExample.SESSION_ID));
            }
        }
    }

    private static Path source(String fileName) {
        return repositoryFile("runtime-examples/src/main/java/io/github/teemuki8/libgdx/agent/"
                + "runtime/examples/" + fileName);
    }

    private static String read(String relativePath) throws Exception {
        return Files.readString(repositoryFile(relativePath));
    }

    private static Path repositoryFile(String relativePath) {
        return Path.of("..").resolve(relativePath).normalize();
    }
}
