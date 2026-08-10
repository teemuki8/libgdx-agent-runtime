# Agent Examples and Cookbook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add CI-compiled, runnable consumer examples and tested transcripts so coding and MCP
agents can use every major runtime workflow without reverse-engineering fixture tests.

**Architecture:** A new non-published `runtime-examples` module depends only on public artifacts and
ordinary libGDX dependencies. Its four applications expose small immutable workflow results; tests
execute the public APIs and actual MCP handler, while Linux-only smoke tests run LWJGL3/Box2D under
Xvfb. The existing cookbook becomes the task index and links to canonical compiled sources/assets.

**Tech Stack:** Java 25, Gradle Kotlin DSL, libGDX/LWJGL3/Box2D 1.14.2, runtime protocol 2.4,
MCP SDK 2.0, Jackson, JUnit 6, Xvfb.

## Global Constraints

- `runtime-examples` is never added to `publishedModules` or `artifactNames`.
- `runtime-core` remains JDK-only and all examples use only public runtime APIs.
- Application code owns dispatch, mutation, render/update, stdio hosting, native objects, and close.
- Stdout is reserved exclusively for MCP in the launcher; human diagnostics use stderr.
- Every request, response, collection, transcript, and diagnostic stays within existing hard bounds.
- Protocol/MCP objects remain closed; unknown fields must be rejected by the real implementation.
- Every agent-visible contract change updates `docs/guides/agent-cookbook.md` in the same commit.
- Linux native verification uses Xvfb; no cross-platform Box2D equality claim is added.
- No staging, signing, tag, or Maven Central publication is authorized.

---

### Task 1: Establish the non-published consumer module and drift boundary

**Files:**
- Modify: `settings.gradle.kts`
- Create: `runtime-examples/build.gradle.kts`
- Create: `runtime-examples/src/test/java/io/github/teemuki8/libgdx/agent/runtime/examples/ExampleModuleContractTest.java`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: the five existing runtime projects and `libs.gdx.backend.lwjgl3`.
- Produces: an `example.classpath` test property and a module excluded from publication.

- [ ] **Step 1: Add a failing module contract test**

```java
@Test
void examplesAreConsumerOnlyAndCurrent() throws Exception {
    String rootBuild = Files.readString(repo("build.gradle.kts"));
    assertFalse(rootBuild.substring(rootBuild.indexOf("val publishedModules"),
            rootBuild.indexOf("val artifactNames")).contains("runtime-examples"));
    assertEquals("2.0.1-SNAPSHOT", projectVersion());
    assertEquals("1.14.2", gdxVersion());
}
```

- [ ] **Step 2: Run the test to verify RED**

Run: `./gradlew :runtime-examples:test --tests '*ExampleModuleContractTest' --warning-mode=fail`

Expected: FAIL because `runtime-examples` is not included.

- [ ] **Step 3: Add the minimal module**

Add `"runtime-examples"` to `settings.gradle.kts`. Configure dependencies:

```kotlin
dependencies {
    implementation(project(":runtime-core"))
    implementation(project(":runtime-box2d"))
    implementation(project(":runtime-libgdx"))
    implementation(project(":runtime-protocol"))
    implementation(project(":runtime-mcp"))
    implementation(libs.gdx.backend.lwjgl3)
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
    runtimeOnly("com.badlogicgames.gdx:gdx-box2d-platform:${libs.versions.gdx.get()}:natives-desktop")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("example.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}
```

On Windows/macOS, exclude `:runtime-examples:test` beside `:runtime-fixtures:test` and compile its
`testClasses` in the native-compilation step. Linux `clean check` executes it under Xvfb.

- [ ] **Step 4: Verify GREEN and module isolation**

Run: `./gradlew :runtime-examples:test :runtime-examples:javadoc --warning-mode=fail`

Expected: PASS; root publication tasks contain no `runtime-examples` publication.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts runtime-examples .github/workflows/ci.yml
git commit -m "build: add non-published runtime examples"
```

### Task 2: Add the basic libGDX inspection application

**Files:**
- Create: `runtime-examples/src/main/java/io/github/teemuki8/libgdx/agent/runtime/examples/BasicInspectionApplication.java`
- Create: `runtime-examples/src/test/java/io/github/teemuki8/libgdx/agent/runtime/examples/BasicInspectionApplicationTest.java`

**Interfaces:**
- Produces: `BasicInspectionApplication.main(String[])` and evidence file keys `session`,
  `latestFrame`, `health`, `eventCount`, `captureThreadCorrect`.

- [ ] **Step 1: Write the failing hidden-application smoke test**

Launch the application from `example.classpath` with one temporary evidence path and assert:

```java
assertTrue(facts.contains("session=basic-inspection-example"));
assertTrue(facts.contains("latestFrame=1"));
assertTrue(facts.contains("health=75"));
assertTrue(facts.contains("eventCount=1"));
assertTrue(facts.contains("captureThreadCorrect=true"));
```

- [ ] **Step 2: Run the test to verify RED**

Run: `xvfb-run -a ./gradlew :runtime-examples:test --tests '*BasicInspectionApplicationTest' --warning-mode=fail`

Expected: FAIL compilation because the application class is missing.

- [ ] **Step 3: Implement the minimal `ApplicationAdapter`**

In `create()`, build with `captureThread(Thread.currentThread())` and
`commandDispatcher(Gdx.app::postRunnable)`, register `player` with integer `health`, and call
`start()`. In the first `render()` call:

```java
runtime.frame(16_666_667L, () -> {
    health = 75;
    runtime.emit(EventSpec.type("player.damaged")
            .subject(EntityId.of("player"))
            .attribute("amount", RuntimeValues.integer(25)));
});
```

Query the immutable latest entity and exact event range, write bounded evidence, and exit. Close
the runtime in `dispose()`. Never log to stdout.

- [ ] **Step 4: Run smoke test and Javadocs**

Run: `xvfb-run -a ./gradlew :runtime-examples:test --tests '*BasicInspectionApplicationTest' :runtime-examples:javadoc --warning-mode=fail`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runtime-examples/src/main runtime-examples/src/test
git commit -m "docs: add runnable basic inspection example"
```

### Task 3: Add the controlled Java workflow example

**Files:**
- Create: `runtime-examples/src/main/java/io/github/teemuki8/libgdx/agent/runtime/examples/ControlledWorkflowExample.java`
- Create: `runtime-examples/src/test/java/io/github/teemuki8/libgdx/agent/runtime/examples/ControlledWorkflowExampleTest.java`

**Interfaces:**
- Produces: `ControlledWorkflowExample.createQueued()`,
  `ControlledWorkflowExample.create(ApplicationCommandDispatcher)`, `runtime()`, `drainOne()`,
  `drainAll()`, and `runWorkflow()` returning immutable `WorkflowResult`. Drain methods are valid
  only for the explicit queued factory.

- [ ] **Step 1: Write failing end-to-end tests**

Test one exact flow: reset is initially `QUEUED`; same request/parameters remains idempotently
queued; different parameters with the same request ID throws; draining succeeds; pause; schedule
`set-velocity` for epoch tick 1; advance exactly 60 configured ticks; inspect `player`; evaluate
PASS and FAIL assertions; create/restore `start`; retrieve recording input/ticks; run two equal
selected-evidence repeats. Add wrong-thread and open-frame mutations that retain typed failure.

- [ ] **Step 2: Run tests to verify RED**

Run: `./gradlew :runtime-examples:test --tests '*ControlledWorkflowExampleTest' --warning-mode=fail`

Expected: FAIL compilation because the controlled example is missing.

- [ ] **Step 3: Implement the explicit application-owned model**

Use one `ArrayDeque<Runnable>` dispatcher, mutable `x/velocity/paused`, a fixed-step
`LibGdxFixedStepSimulation`, an entity `player`, scenario `walk`, input `set-velocity`, and an
opaque checkpoint containing `x` and `velocity`. The acknowledged callback advances `x` from the
configured step and returns `tick.fixedStepNanos()`. Reset and restore clear the accumulator.

`runWorkflow()` must use public registries and construct generic `SimulationAssertionSpec` values
directly; Box2D assertion factories do not belong in the backend-neutral example. Record with
protocol version `2.4`, stop before retrieval, and compare only `player.position` and
`player.velocity` with `checkSimulation`.

- [ ] **Step 4: Verify focused GREEN**

Run: `./gradlew :runtime-examples:test --tests '*ControlledWorkflowExampleTest' :runtime-examples:javadoc --warning-mode=fail`

Expected: PASS with exact tick/frame correlation and no worker thread.

- [ ] **Step 5: Commit**

```bash
git add runtime-examples/src/main runtime-examples/src/test
git commit -m "docs: add controlled agent workflow example"
```

### Task 4: Add same-JVM MCP hosting and machine-readable transcript

**Files:**
- Create: `runtime-examples/src/main/java/io/github/teemuki8/libgdx/agent/runtime/examples/SameJvmMcpApplication.java`
- Create: `runtime-examples/src/main/resources/transcripts/controlled-workflow.json`
- Create: `runtime-examples/src/test/java/io/github/teemuki8/libgdx/agent/runtime/examples/McpTranscriptTest.java`

**Interfaces:**
- Transcript entries contain closed `name`, `arguments`, `dispatch`, and `expectedContains` fields.
- Produces: one real stdio launcher and a live-handler transcript replay.

- [ ] **Step 1: Write failing transcript/launcher tests**

Load the asset through a locally closed Jackson record. Reject an added `script` field. For every
entry, call `RuntimeToolHandler`; when `dispatch=true`, drain application commands and poll the same
call. Assert all `expectedContains` values occur in the structured result. Require this exact tool
sequence:

```text
runtime_sessions, runtime_capabilities, runtime_scenarios, runtime_reset,
runtime_control, runtime_input, runtime_simulation_advance, runtime_entity,
runtime_events, runtime_assert, runtime_simulation_assert,
runtime_simulation_determinism_check
```

Construct representative `RuntimeRequest`/`RuntimeResponse` values for protocol 2.4, encode them
with `ProtocolJson`, decode them again, and assert exact equality.

Also launch `SameJvmMcpApplication`, exchange MCP initialize plus `runtime_sessions`, close stdin,
and assert stdout contains only JSON-RPC frames while diagnostics appear only on stderr.

- [ ] **Step 2: Run tests to verify RED**

Run: `xvfb-run -a ./gradlew :runtime-examples:test --tests '*McpTranscriptTest' --warning-mode=fail`

Expected: FAIL because the launcher/transcript are missing.

- [ ] **Step 3: Implement transcript and launcher**

The launcher creates the controlled model with `Gdx.app::postRunnable`, publishes it through
`RuntimeRegistry`, opens one `RuntimeMcpServer` on `System.in/System.out`, drains render-thread work
from normal rendering, and closes in this order:

```java
server.close();
publication.close();
example.close();
```

No other code writes stdout and no network listener is introduced.

- [ ] **Step 4: Verify live transcript and strict failure**

Run: `xvfb-run -a ./gradlew :runtime-examples:test --tests '*McpTranscriptTest' :runtime-examples:javadoc --warning-mode=fail`

Expected: PASS; unknown transcript/tool fields are rejected.

- [ ] **Step 5: Commit**

```bash
git add runtime-examples/src/main runtime-examples/src/test
git commit -m "docs: add tested same-JVM MCP transcript"
```

### Task 5: Add the standalone deterministic Box2D consumer example

**Files:**
- Create: `runtime-examples/src/main/java/io/github/teemuki8/libgdx/agent/runtime/examples/DeterministicBox2dExample.java`
- Create: `runtime-examples/src/test/java/io/github/teemuki8/libgdx/agent/runtime/examples/DeterministicBox2dExampleTest.java`

**Interfaces:**
- Produces: `DeterministicBox2dExample.create(ApplicationCommandDispatcher)`, `runWorkflow()`,
  and `Box2dResult(positionStatus, contactStatus, determinismStatus, tickFrameCorrelated)`.

- [ ] **Step 1: Write failing actual-native test**

Initialize real Box2D natives, construct the example, run scheduled movement for 60 exact ticks,
and assert PASS position/contact, EQUAL selected reruns, joint/body/fixture/contact entity presence,
and tick/frame correlation. Add focused fault configurations for executed-step mismatch,
unregistered endpoint, suspicious render extent, and bounded contact evidence.

- [ ] **Step 2: Run test to verify RED**

Run: `xvfb-run -a ./gradlew :runtime-examples:test --tests '*DeterministicBox2dExampleTest' --warning-mode=fail`

Expected: FAIL compilation because the example is missing.

- [ ] **Step 3: Implement the consumer sample**

Use the canonical `LibGdxFixedStepSimulation.acknowledged` callback:

```java
contacts.captureStep(() -> world.step(tick.fixedStepSeconds(), 8, 3));
gameLogicAfterPhysics();
return tick.fixedStepNanos();
```

Register stable world/body/fixture/joint IDs, install `contacts.compose(gameListener)`, recreate and
rebind during scenario reset, schedule `move-player`, and keep rendering absent from authoritative
checks. Close registrations/runtime before `world.dispose()`.

- [ ] **Step 4: Verify actual-native GREEN**

Run: `xvfb-run -a ./gradlew :runtime-examples:test --tests '*DeterministicBox2dExampleTest' :runtime-examples:javadoc --warning-mode=fail`

Expected: PASS with actual desktop natives.

- [ ] **Step 5: Commit**

```bash
git add runtime-examples/src/main runtime-examples/src/test
git commit -m "docs: add deterministic Box2D consumer example"
```

### Task 6: Expand the cookbook and enforce documentation drift checks

**Files:**
- Modify: `docs/guides/agent-cookbook.md`
- Modify: `README.md`
- Modify: `docs/guides/getting-started.md`
- Modify: `docs/guides/agent-tools.md`
- Modify: `CHANGELOG.md`
- Create: `runtime-examples/src/test/java/io/github/teemuki8/libgdx/agent/runtime/examples/AgentCookbookContractTest.java`

**Interfaces:**
- Consumes: compiled example paths and transcript resource from Tasks 2-5.
- Produces: task-indexed recipes plus link/version/tool/policy drift failures.

- [ ] **Step 1: Write the failing cookbook contract test**

Assert all issue #73 task headings exist; links resolve; example class paths exist; transcript tool
names are in `RuntimeToolCatalog`; dependency examples distinguish `2.0.0` from
`2.0.1-SNAPSHOT`; `AGENTS.md` and the repository skill require same-PR cookbook updates; and no
historical release note was edited.

- [ ] **Step 2: Run test to verify RED**

Run: `./gradlew :runtime-examples:test --tests '*AgentCookbookContractTest' --warning-mode=fail`

Expected: FAIL on missing general workflow/failure headings.

- [ ] **Step 3: Add compact complete recipes**

Add the task index and general Java/MCP/failure chapters. Every recipe includes artifact/version,
registration, exact call, representative result, state interpretation, thread/lifecycle rule,
bound/incompleteness behavior, cleanup, and one incorrect call with typed failure. Link full code to
the examples instead of duplicating long snippets.

- [ ] **Step 4: Verify documentation GREEN**

Run: `./gradlew :runtime-examples:test --tests '*AgentCookbookContractTest' --warning-mode=fail`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add docs README.md CHANGELOG.md runtime-examples/src/test
git commit -m "docs: complete tested agent cookbook"
```

### Task 7: Qualify the full consumer vertical slice

**Files:**
- Modify only files required by observed verification failures.

- [ ] **Step 1: Run focused example gate**

Run: `xvfb-run -a ./gradlew :runtime-examples:clean :runtime-examples:check :runtime-examples:javadoc --warning-mode=fail`

Expected: PASS.

- [ ] **Step 2: Run repository check gate**

Run: `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh check`

Expected: PASS.

- [ ] **Step 3: Run native fixture and full gates**

Run:

```bash
.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh fixture
.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh full
```

Expected: PASS under Xvfb; full executes `clean check javadoc` with every task.

- [ ] **Step 4: Re-prove core dependency direction and diff hygiene**

Run:

```bash
jdeps --ignore-missing-deps --multi-release 25 --print-module-deps \
  runtime-core/build/libs/runtime-core-2.0.1-SNAPSHOT.jar
git diff --check
```

Expected: `java.base` and no diff errors.

- [ ] **Step 5: Request independent review and commit only observed fixes**

Review issue #73 acceptance, example readability, closed transcript schemas, native lifecycle,
failure honesty, cookbook accuracy, and non-publication. Resolve every Critical/Important finding,
rerun affected gates, and create a final scoped commit if necessary.
