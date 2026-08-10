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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        assertTrue(cookbook.contains(":runtime-examples:installDist"));
        assertTrue(cookbook.contains("run-mcp-example-xvfb.sh"));
        assertTrue(cookbook.contains("### Exact failure calls"));
        assertTrue(cookbook.contains("runtime.checkpoints().create"));
        assertTrue(cookbook.contains("runtime.recordings().start"));
        assertLocalLinksResolve(cookbook);
        assertCurrentConsumerVersions();

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
        String exampleBuild = read("runtime-examples/build.gradle.kts");
        assertTrue(exampleBuild.contains("applicationName = \"runtime-mcp-example\""));
        assertTrue(exampleBuild.contains("example.mcp.launcher"));
        assertTrue(Files.isExecutable(repositoryFile(
                "runtime-examples/run-mcp-example-xvfb.sh")));
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
                Set<String> cookbookTools = tokens(
                        read("docs/guides/agent-cookbook.md"), "runtime_[a-z0-9_]+");
                assertTrue(catalog.toolNames().containsAll(cookbookTools),
                        () -> "unknown cookbook tools: " + cookbookTools.stream()
                                .filter(tool -> !catalog.toolNames().contains(tool)).toList());
                assertTrue(publication.sessionId().equals(ControlledWorkflowExample.SESSION_ID));
            }
        }
    }

    private static Path source(String fileName) {
        return repositoryFile("runtime-examples/src/main/java/io/github/teemuki8/libgdx/agent/"
                + "runtime/examples/" + fileName);
    }

    private static void assertLocalLinksResolve(String cookbook) {
        Matcher links = Pattern.compile("\\[[^]]*]\\(([^)]+)\\)").matcher(cookbook);
        int checked = 0;
        while (links.find()) {
            String target = links.group(1);
            if (target.startsWith("http://") || target.startsWith("https://")
                    || target.startsWith("#")) {
                continue;
            }
            int anchor = target.indexOf('#');
            String path = anchor < 0 ? target : target.substring(0, anchor);
            Path resolved = repositoryFile("docs/guides").resolve(path).normalize();
            assertTrue(Files.exists(resolved), () -> "broken cookbook link: " + target);
            checked++;
        }
        assertTrue(checked >= 10, "expected local cookbook links to be checked");
    }

    private static void assertCurrentConsumerVersions() throws Exception {
        String rootBuild = read("build.gradle.kts");
        String development = capture(rootBuild, "orElse\\(\"([^\"]+)\"\\)");
        String release = capture(read("README.md"), "Current release: `([^`]+)`");
        Set<String> allowed = Set.of(release, development);
        Pattern coordinate = Pattern.compile(
                "io\\.github\\.teemuki8:agent-runtime-[a-z0-9-]+:([0-9A-Za-z.-]+)");
        try (var paths = Files.walk(repositoryFile("docs/guides"))) {
            List<Path> guides = paths.filter(path -> path.toString().endsWith(".md")).toList();
            int checked = 0;
            for (Path guide : guides) {
                Matcher matcher = coordinate.matcher(Files.readString(guide));
                while (matcher.find()) {
                    assertTrue(allowed.contains(matcher.group(1)),
                            () -> "stale consumer version in " + guide + ": " + matcher.group());
                    checked++;
                }
            }
            assertTrue(checked >= 5, "expected consumer coordinates to be checked");
        }
    }

    private static Set<String> tokens(String content, String expression) {
        Matcher matcher = Pattern.compile(expression).matcher(content);
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return Set.copyOf(values);
    }

    private static String capture(String content, String expression) {
        Matcher matcher = Pattern.compile(expression).matcher(content);
        if (!matcher.find()) {
            throw new AssertionError("expected metadata pattern: " + expression);
        }
        return matcher.group(1);
    }

    private static String read(String relativePath) throws Exception {
        return Files.readString(repositoryFile(relativePath));
    }

    private static Path repositoryFile(String relativePath) {
        return Path.of("..").resolve(relativePath).normalize();
    }
}
