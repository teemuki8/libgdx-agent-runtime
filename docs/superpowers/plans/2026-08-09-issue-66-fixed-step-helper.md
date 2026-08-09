# Canonical fixed-step helper implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one bounded application-owned fixed-step accumulator, a thin libGDX facade, and
protocol 2.2 inspection/exact-advance tools.

**Architecture:** `runtime-core` owns integer accumulator state and immutable evidence so protocol
can inspect it without a reverse dependency. `runtime-libgdx` converts finite float seconds and
delegates to core. Normal and controlled ticks both use the #65 simulation timeline boundary.

**Tech Stack:** Java 25, JUnit 5, libGDX, Jackson, MCP Java SDK, Gradle, LWJGL3/Xvfb.

## Global constraints

- `runtime-core` remains JDK-only and creates no thread, timer, scheduler, sleep, or loop.
- The application owns callback mutation, rendering, dispatch, reset hooks, and disposal.
- Protocols 1.0-1.13, 2.0, and 2.1 remain exact and closed; new operations use 2.2.
- Values, report history, queries, diagnostics, and request evidence are immutable and bounded.
- Existing `runtime_advance` and controlled-tick recording semantics do not change.
- Every affected public API and agent-visible behavior updates the cookbook in this PR.
- No Maven Central publication is authorized.

---

### Task 1: Immutable fixed-step configuration and evidence model

**Files:**
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/FixedStepDropPolicy.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/FixedStepUpdateDiagnostic.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/FixedStepSimulationConfiguration.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/FixedStepUpdateReport.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/FixedStepSimulationState.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/FixedStepUpdateQuery.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/FixedStepUpdatePage.java`
- Test: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/FixedStepSimulationTest.java`

**Interfaces:**
- Produces:
  `FixedStepSimulationConfiguration(long fixedStepNanos, long maximumRenderDeltaNanos,
  long maximumAccumulatedTimeNanos, int maximumCatchUpTicks, int retainedUpdateReports,
  FixedStepDropPolicy dropPolicy, boolean acknowledgementRequired)`.
- Produces: immutable report/state/query/page records using `SimulationTickId` and `FrameId` for
  correlation and a `List<FixedStepUpdateDiagnostic>` for bounded diagnostics.

- [ ] **Step 1: Write model validation tests**

```java
assertThrows(IllegalArgumentException.class, () -> new FixedStepSimulationConfiguration(
        0, 20, 20, 1, 1, FixedStepDropPolicy.DROP_WHOLE_TICKS_KEEP_REMAINDER, true));
assertThrows(IllegalArgumentException.class, () -> new FixedStepSimulationConfiguration(
        10, Long.MAX_VALUE, Long.MAX_VALUE, 2, 1,
        FixedStepDropPolicy.DROP_WHOLE_TICKS_KEEP_REMAINDER, true));
```

- [ ] **Step 2: Run the focused test and observe missing-type compilation failure**

Run: `./gradlew :runtime-core:test --tests '*FixedStepSimulationTest*'`

- [ ] **Step 3: Implement the minimal closed records/enums with defensive validation/copying**

```java
public enum FixedStepDropPolicy { DROP_WHOLE_TICKS_KEEP_REMAINDER }

public record FixedStepUpdateQuery(long fromSequence, long toSequence, int limit) {}
```

- [ ] **Step 4: Run focused core tests and Javadocs**

Run: `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh core`

---

### Task 2: Core accumulator registry and honest update failure behavior

**Files:**
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/FixedStepSimulationRegistry.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/AgentRuntime.java`
- Modify: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/FixedStepSimulationTest.java`

**Interfaces:**
- Produces:
  `register(FixedStepSimulationConfiguration, SimulationTickCallback)`,
  `registerUnacknowledged(FixedStepSimulationConfiguration, LongConsumer)`,
  `FixedStepUpdateReport update(long)`, `clearAccumulator()`, `restoreAccumulator(long)`,
  `state()`, and `updates(FixedStepUpdateQuery)`.
- Produces: `AgentRuntime.fixedStepSimulation()`.

- [ ] **Step 1: Write failing behavior tests for exact accumulation and drop evidence**

```java
runtime.fixedStepSimulation().register(configuration(10, 25, 30, 2), supplied -> supplied);
runtime.start();
FixedStepUpdateReport first = runtime.fixedStepSimulation().update(9);
FixedStepUpdateReport second = runtime.fixedStepSimulation().update(26);
assertEquals(2, second.ticksCompleted());
assertEquals(5, second.accumulatorRemainderNanos());
assertEquals(1, second.clampedRenderTimeNanos());
```

- [ ] **Step 2: Run the focused test and observe the missing registry/accessor failure**

Run: `./gradlew :runtime-core:test --tests '*FixedStepSimulationTest*'`

- [ ] **Step 3: Implement registration and the integer update loop**

```java
while (accumulatorNanos >= configuration.fixedStepNanos()
        && attempted < configuration.maximumCatchUpTicks()) {
    accumulatorNanos -= configuration.fixedStepNanos();
    Optional<SimulationTick> tick = runtime.simulation().tick(
            configuration.fixedStepNanos(), callback);
    attempted++;
    completed++;
}
```

Retain one report for success or failure, drop remaining whole steps after catch-up/failure, keep
the remainder, and rethrow the original callback/capture failure after retention.

- [ ] **Step 4: Add failing lifecycle, thread, reentrancy, pause, reset/restore, disabled, overflow,
      eviction, ordering, and unacknowledged-callback tests**

```java
assertThrows(AgentRuntimeException.class,
        () -> runtime.fixedStepSimulation().update(1));
assertThrows(IllegalStateException.class, () -> runtime.fixedStepSimulation().update(10));
```

- [ ] **Step 5: Implement the minimal lifecycle guards, retention, and close hook**

The close hook releases application callbacks while retaining immutable completed reports. A
disabled runtime executes callbacks and accumulator bookkeeping without retaining report history.

- [ ] **Step 6: Run focused core verification**

Run: `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh core`

---

### Task 3: Configured-step controlled advance

**Files:**
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationControlRegistry.java`
- Modify: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationControlRegistryTest.java`
- Modify: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/FixedStepSimulationTest.java`

**Interfaces:**
- Produces: `ControlOperation advanceFixed(String requestId, int ticks, Duration timeout)`.

- [ ] **Step 1: Write a failing exact-advance test with a frozen partial accumulator**

```java
runtime.fixedStepSimulation().update(5);
runtime.controls().control(true, "pause", Duration.ofSeconds(1));
ControlOperation operation = runtime.controls().advanceFixed(
        "fixed-advance", 2, Duration.ofSeconds(1));
assertEquals(2, operation.completedTicks());
assertEquals(5, runtime.fixedStepSimulation().state().accumulatorRemainderNanos());
```

- [ ] **Step 2: Run the test and observe the missing method failure**

Run: `./gradlew :runtime-core:test --tests '*FixedStepSimulationTest*'`

- [ ] **Step 3: Delegate to the existing bounded control operation with configured delta only**

```java
long fixedStep = runtime.simulation().state().configuredFixedStepNanos()
        .orElseThrow(() -> new AgentRuntimeException(
                RuntimeErrorCode.INVALID_LIFECYCLE, "fixed step is not configured"));
return tickOperation(requestId,
        new Signature(ControlOperation.Kind.ADVANCE, ticks, fixedStep,
                Optional.empty(), Optional.empty(), 0), timeout);
```

- [ ] **Step 4: Run the full core gate**

Run: `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh core`

---

### Task 4: Thin libGDX float-seconds facade

**Files:**
- Create: `runtime-libgdx/src/main/java/io/github/teemuki8/libgdx/agent/runtime/libgdx/LibGdxFixedStepTick.java`
- Create: `runtime-libgdx/src/main/java/io/github/teemuki8/libgdx/agent/runtime/libgdx/LibGdxFixedStepCallback.java`
- Create: `runtime-libgdx/src/main/java/io/github/teemuki8/libgdx/agent/runtime/libgdx/LibGdxFixedStepSimulation.java`
- Modify: `runtime-libgdx/src/test/java/io/github/teemuki8/libgdx/agent/runtime/libgdx/LibGdxAdapterTest.java`

**Interfaces:**
- Produces:
  `LibGdxFixedStepSimulation.acknowledged(AgentRuntime,
  FixedStepSimulationConfiguration, LibGdxFixedStepCallback)`,
  `update(float renderDeltaSeconds)`, `fixedStepSeconds()`, and `interpolationAlpha()`.

- [ ] **Step 1: Write failing tests for deterministic finite float conversion and delegation**

```java
LibGdxFixedStepSimulation simulation = LibGdxFixedStepSimulation.acknowledged(
        runtime, configuration(16_666_667L), tick -> tick.fixedStepNanos());
runtime.start();
simulation.update(1f / 30f);
assertEquals(2, runtime.simulation().state().completedEpochTicks());
assertThrows(IllegalArgumentException.class, () -> simulation.update(Float.NaN));
```

- [ ] **Step 2: Run the libGDX test and observe missing facade types**

Run: `./gradlew :runtime-libgdx:test --tests '*LibGdxAdapterTest*'`

- [ ] **Step 3: Implement checked float conversion and core delegation without another accumulator**

```java
long nanos = Math.round((double) renderDeltaSeconds * 1_000_000_000d);
return delegate.update(nanos);
```

Reject non-finite, negative, and conversion-overflow values. Calculate `fixedStepSeconds` once from
configuration and pass the same value in every `LibGdxFixedStepTick`.

- [ ] **Step 4: Run the libGDX gate**

Run: `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh libgdx`

---

### Task 5: Protocol 2.2 and closed MCP tools

**Files:**
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/ProtocolVersion.java`
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeCommand.java`
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeResponse.java`
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeProtocolService.java`
- Create: `runtime-protocol/src/test/java/io/github/teemuki8/libgdx/agent/runtime/protocol/FixedStepProtocolTest.java`
- Modify: `runtime-mcp/src/main/java/io/github/teemuki8/libgdx/agent/runtime/mcp/RuntimeToolCatalog.java`
- Modify: `runtime-mcp/src/main/java/io/github/teemuki8/libgdx/agent/runtime/mcp/RuntimeToolHandler.java`
- Create: `runtime-mcp/src/test/java/io/github/teemuki8/libgdx/agent/runtime/mcp/FixedStepMcpTest.java`

**Interfaces:**
- Produces exact 2.2 commands/results for `fixedStep`, `fixedStepUpdates`, and
  `simulationAdvance`.
- Produces MCP tools `runtime_fixed_step`, `runtime_fixed_step_updates`, and
  `runtime_simulation_advance`; advance takes tick count/request ID/timeout but no delta.

- [ ] **Step 1: Write failing 2.2 protocol tests**

```java
RuntimeRequest request = new RuntimeRequest(ProtocolVersion.V2_2, "advance", sessionId,
        new RuntimeCommand.SimulationAdvance("advance-1", 2, 1_000_000_000L));
assertInstanceOf(RuntimeResponse.Result.Control.class, success(service.execute(request)).result());
```

Also assert 2.1 rejects all three commands, 2.2 unknown fields fail decoding, ranges are bounded,
and capability metadata exposes effective configuration/report limits.

- [ ] **Step 2: Run protocol tests and observe missing version/command failures**

Run: `./gradlew :runtime-protocol:test --tests '*FixedStepProtocolTest*'`

- [ ] **Step 3: Implement the additive versioned command/result dispatch**

```java
case RuntimeCommand.FixedStep ignored ->
        new RuntimeResponse.Result.FixedStep(runtime.fixedStepSimulation().state());
case RuntimeCommand.SimulationAdvance command -> runtime.controls().advanceFixed(
        command.controlRequestId(), command.ticks(), Duration.ofNanos(command.timeoutNanos()));
```

- [ ] **Step 4: Write failing MCP catalog/handler tests, including no `deltaNanos` field**

- [ ] **Step 5: Implement the three closed MCP schemas and exact 2.2 mapping**

- [ ] **Step 6: Run protocol and MCP gates**

Run: `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh protocol`

Run: `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh mcp`

---

### Task 6: Real fixture, cookbook, and bootstrap-facing integration

**Files:**
- Modify: `runtime-fixtures/src/main/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/DeterministicSimulation.java`
- Modify: `runtime-fixtures/src/test/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/PublicJavaWorkflowTest.java`
- Modify: `runtime-fixtures/src/test/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/FixtureProtocolAndMcpTest.java`
- Modify: `README.md`
- Modify: `docs/design-contract.md`
- Modify: `docs/guides/getting-started.md`
- Modify: `docs/guides/agent-tools.md`
- Modify: `docs/guides/agent-cookbook.md`

**Interfaces:**
- Consumes the exact core/libGDX/protocol/MCP APIs from Tasks 1-5.
- Produces one tested canonical loop and exact controlled-advance transcript.

- [ ] **Step 1: Add a failing fixture test for accumulator update, pause, fixed advance, timeline
      inspection, and update-report inspection**

- [ ] **Step 2: Run the fixture gate and observe the expected fixture assertion failure**

Run: `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh fixture`

- [ ] **Step 3: Integrate the facade in the deterministic fixture and make the vertical slice pass**

- [ ] **Step 4: Update every affected guide and cookbook recipe in the same change**

The cookbook must show configuration, acknowledged simulation, render separation, clamp/drop
diagnostics, pause plus `runtime_simulation_advance`, reset clearing, state/update queries, and the
development-version warning.

- [ ] **Step 5: Run fixture and repository check gates**

Run: `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh fixture`

Run: `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh check`

---

### Task 7: Final review, full verification, and stacked PR

**Files:** Review every file changed relative to `issue-65-simulation-timeline`.

- [ ] **Step 1: Run diff and public-contract review**

Run: `git diff --check issue-65-simulation-timeline...HEAD`

Verify bounds, defensive copies, Javadocs, exact 2.2 gating, no callback retention after close,
no changed 2.1 JSON, and no controlled-recording identity change.

- [ ] **Step 2: Prove core remains JDK-only**

Run: `jdeps --ignore-missing-deps --multi-release 25 --print-module-deps runtime-core/build/libs/runtime-core-2.0.1-SNAPSHOT.jar`

Expected: `java.base`

- [ ] **Step 3: Run the clean Linux-native gate**

Run: `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh full`

- [ ] **Step 4: Commit, push, and open a stacked PR**

Base the PR on `issue-65-simulation-timeline`, include `Fixes #66`, identify dependency on PR #74,
and do not merge without explicit authorization.
