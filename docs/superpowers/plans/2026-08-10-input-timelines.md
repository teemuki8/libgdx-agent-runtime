# Deterministic Input Timelines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one bounded, application-dispatched operation that validates and executes an ordered sequence of explicit registered input transitions through exact fixed simulation ticks, with complete Java, protocol 2.6, MCP, recording/replay, native fixture, and cookbook evidence.

**Architecture:** `InputRegistry` remains the public owner of registered semantic inputs and delegates aggregate-operation retention/execution to one package-private `InputTimelineExecutor`. Timeline transitions reuse normal `InputInjection` staging and recording hooks, while a small package-private exact-tick seam in `SimulationControlRegistry` avoids nested public command dispatch. The application owns persistent input state and simulation callbacks; the runtime owns only bounded validation, ordering, dispatch, exact timing, and immutable evidence.

**Tech Stack:** Java 25 records and sealed runtime values, JUnit 5, Jackson typed protocol, MCP Java SDK closed schemas, libGDX/LWJGL3, native Box2D, Gradle, Xvfb.

## Global Constraints

- Preserve module direction: `runtime-mcp -> runtime-protocol -> runtime-core <- runtime-libgdx`; `runtime-fixtures` and `runtime-examples` remain unpublished.
- Keep `runtime-core` JDK-only: no libGDX, Jackson, MCP, transport, filesystem, shell, networking, scheduler, worker, timer, or sleep dependency.
- Execute mutation only through the existing application-owned command dispatcher on the configured capture thread.
- Require a known paused controller, acknowledged tick callback, and configured fixed step before timeline execution.
- Require positive `totalTicks`, at least one transition, non-decreasing local ticks in `1..totalTicks`, unique transition IDs, and stable request-list order at the same tick.
- Persistent key/button/analog state remains application-owned; the runtime performs no interpolation, implicit release, platform event injection, or OS/global input hook.
- Development defaults are 32 retained operations, 4,096 transitions, 600 ticks, 1,048,576 canonical evidence bytes, and 30 seconds.
- The effective transition/tick bounds are the minima of timeline, ordinary input, control, runtime-value, and dispatcher bounds, and capability metadata must report both configured and effective values.
- All public values are immutable, defensively copied, deeply bounded, deterministically ordered, and documented with warning-free Javadocs.
- Failed, redacted, truncated, timed-out, lifecycle-invalidated, or otherwise incomplete timeline evidence must never leave replay capture conclusive.
- Protocol 2.6 and `runtime_input_timeline` are additive and unreleased; protocols 1.0-1.13 and 2.0-2.5 retain their closed shapes and reject the new command before dispatch.
- MCP remains local same-JVM stdio with closed schemas and no listener or remote-attachment claim.
- Linux fixture and full verification run under isolated `xvfb-run`, never the developer desktop display.
- Do not push, open a PR, tag, stage, sign, or publish without separate explicit authorization.

---

## File Structure

### New core contract files

- `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineLimits.java` — configured hard limits and absolute supported ceilings.
- `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineTransition.java` — one explicit local-tick registered-input transition.
- `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineSpec.java` — ordered aggregate request and structural validation.
- `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineTransitionState.java` — `EXECUTED`, `FAILED`, or `NOT_EXECUTED`.
- `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineStopReason.java` — closed terminal reason.
- `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineTransitionEvidence.java` — redaction-safe per-transition evidence.
- `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineBounds.java` — requested/completed/outcome/byte/deadline testimony.
- `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineResult.java` — bounded terminal aggregate result.
- `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineOperation.java` — parent command lookup and optional terminal result.
- `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineCanonicalSize.java` — saturating JDK-only canonical evidence accounting.
- `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineExecutor.java` — package-private reservation, retention, execution, and cleanup.

### Modified core files

- `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/AgentRuntime.java` — builder limit wiring.
- `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputRegistry.java` — public timeline API, exclusive mode, child evidence, normal recording hooks.
- `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationControlRegistry.java` — shared package-private exact-tick primitive.
- `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayRegistry.java` — fail-closed timeline-stop testimony during capture.
- `docs/design-contract.md` — normative behavioral clause.
- `docs/adr/0020-bounded-input-timeline-execution.md` — lasting architectural decision.

### Core tests

- `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineContractTest.java` — records, limits, copies, canonical size, and invariants.
- `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineRegistryTest.java` — success, ordering, lifecycle, deadlines, failures, retention, and at-most-once behavior.
- `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayRegistryTest.java` — schema-1 recording and replay completeness regressions.
- `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/InputRegistryTest.java` — ordinary input/exclusive-mode compatibility.

### Protocol 2.6

- `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/ProtocolVersion.java` — `V2_6`, current version, capability gate, exact rejection.
- `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeCommand.java` — closed `InputTimeline` command.
- `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeResponse.java` — closed timeline result.
- `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeProtocolService.java` — dispatch mapping, tool/capability advertisement, effective limits.
- `runtime-protocol/src/test/java/io/github/teemuki8/libgdx/agent/runtime/protocol/InputTimelineProtocolTest.java` — round trip, live execution, compatibility, capabilities, and closed JSON.
- `runtime-protocol/src/test/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeProtocolTest.java` — supported-version string/current-version expectations.

### MCP

- `runtime-mcp/src/main/java/io/github/teemuki8/libgdx/agent/runtime/mcp/RuntimeToolCatalog.java` — registered-input-specific closed timeline schema.
- `runtime-mcp/src/main/java/io/github/teemuki8/libgdx/agent/runtime/mcp/RuntimeToolHandler.java` — strict natural-JSON transition mapping to protocol 2.6.
- `runtime-mcp/src/test/java/io/github/teemuki8/libgdx/agent/runtime/mcp/InputTimelineMcpTest.java` — schema and live boolean/integer/decimal/string execution plus rejection tests.
- `runtime-mcp/src/test/java/io/github/teemuki8/libgdx/agent/runtime/mcp/RuntimeMcpTest.java` — catalog/version expectations if shared counts change.

### Native fixture, compiled examples, transcript, and documentation

- `runtime-fixtures/src/main/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/Box2dConformanceSimulation.java` — application-owned boolean control state alongside the existing decimal input.
- `runtime-fixtures/src/test/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/Box2dConformanceFixtureTest.java` — actual-native start/stop, analog, same-tick order, idle ticks, and recording/replay proof.
- `runtime-examples/src/main/java/io/github/teemuki8/libgdx/agent/runtime/examples/ControlledWorkflowExample.java` — compiled Java timeline recipe.
- `runtime-examples/src/test/java/io/github/teemuki8/libgdx/agent/runtime/examples/ControlledWorkflowExampleTest.java` — terminal timeline evidence expectations.
- `runtime-examples/src/main/resources/transcripts/controlled-workflow.json` — live `runtime_input_timeline` transcript step.
- `runtime-examples/src/test/java/io/github/teemuki8/libgdx/agent/runtime/examples/McpTranscriptTest.java` — transcript order and protocol 2.6 envelope.
- `runtime-examples/src/test/java/io/github/teemuki8/libgdx/agent/runtime/examples/AgentCookbookContractTest.java` — recipe/version/tool drift guards.
- `docs/guides/agent-cookbook.md` — compiled Java recipe, tested MCP call, evidence/failure guidance.
- `docs/guides/agent-tools.md` — tool table, protocol, capability, limits, and semantics.
- `SECURITY.md` — closed registered-input sequence boundary and corrected replay wording.
- `README.md` — concise protocol 2.6 capability summary.
- `CHANGELOG.md` — unreleased additive feature entry.

---

### Task 1: Immutable Core Contract, Limits, and ADR

**Files:**
- Create: all nine public `InputTimeline*.java` model/limit files listed under “New core contract files,” excluding `InputTimelineCanonicalSize.java` and `InputTimelineExecutor.java`
- Create: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineContractTest.java`
- Create: `docs/adr/0020-bounded-input-timeline-execution.md`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/AgentRuntime.java`

**Interfaces:**
- Consumes: existing `RuntimeValue.ObjectValue`, `InputInjection`, `CommandLookup`, `ExecutionEpochId`, `FrameId`, and `ApplicationFailureEvidence`.
- Produces: the exact public records/enums used by every later task and `AgentRuntime.Builder#inputTimelineLimits(InputTimelineLimits)`.

- [ ] **Step 1: Write failing record and limit tests**

Create tests that lock the exact names, defaults, order, defensive copying, and result consistency:

```java
@Test
void timelineSpecPreservesOrderedTransitionsAndDefensivelyCopies() {
    ArrayList<InputTimelineTransition> source = new ArrayList<>(List.of(
            transition("press", 1, "button", RuntimeValues.bool(true)),
            transition("steer", 3, "analog", RuntimeValues.decimal("0.5")),
            transition("release", 3, "button", RuntimeValues.bool(false))));

    InputTimelineSpec spec = new InputTimelineSpec(3, source);
    source.clear();

    assertEquals(List.of("press", "steer", "release"), spec.transitions().stream()
            .map(InputTimelineTransition::transitionId).toList());
    assertThrows(UnsupportedOperationException.class,
            () -> spec.transitions().add(transition(
                    "extra", 3, "button", RuntimeValues.bool(false))));
}

@Test
void timelineSpecRejectsInvalidOrderIdentityRangeAndNestedValues() {
    assertThrows(IllegalArgumentException.class, () -> new InputTimelineSpec(0, List.of(
            transition("press", 1, "button", RuntimeValues.bool(true)))));
    assertThrows(IllegalArgumentException.class, () -> new InputTimelineSpec(2, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new InputTimelineSpec(2, List.of(
            transition("late", 2, "button", RuntimeValues.bool(true)),
            transition("early", 1, "button", RuntimeValues.bool(false)))));
    assertThrows(IllegalArgumentException.class, () -> new InputTimelineSpec(2, List.of(
            transition("same", 1, "button", RuntimeValues.bool(true)),
            transition("same", 2, "button", RuntimeValues.bool(false)))));
    assertThrows(IllegalArgumentException.class, () -> new InputTimelineTransition(
            "nested", 1, "button", RuntimeValues.object(RuntimeValues.field(
                    "active", RuntimeValues.list(RuntimeValues.bool(true))))));
}

@Test
void developmentTimelineLimitsMatchTheApprovedContract() {
    assertEquals(new InputTimelineLimits(
            32, 4_096, 600, 1_048_576, Duration.ofSeconds(30).toNanos()),
            InputTimelineLimits.developmentDefaults());
}

private static InputTimelineTransition transition(
        String id, int tick, String input, RuntimeValue value) {
    return new InputTimelineTransition(id, tick, input,
            RuntimeValues.object(RuntimeValues.field("value", value)));
}
```

Also construct valid/invalid `InputTimelineTransitionEvidence`, `InputTimelineBounds`,
`InputTimelineResult`, and `InputTimelineOperation` instances. Assert that `COMPLETED` requires all
ticks/transitions completed, non-completed results may retain authoritative completed work without
claiming success, unattempted evidence has no `InputInjection`, messages remain within 642 code
units, and all optional/list fields reject null.

- [ ] **Step 2: Run the focused tests and confirm the missing-type failure**

Run:

```bash
./gradlew :runtime-core:test --tests '*InputTimelineContractTest' --warning-mode=fail
```

Expected: compilation fails because the input-timeline types do not exist.

- [ ] **Step 3: Implement the closed public records and limits**

Use these exact signatures:

```java
public record InputTimelineLimits(int retainedOperations, int maximumTransitions,
        int maximumTicks, int maximumEncodedEvidenceBytes, long maximumExecutionNanos) {}

public record InputTimelineTransition(String transitionId, int timelineTick,
        String inputId, RuntimeValue.ObjectValue parameters) {}

public record InputTimelineSpec(int totalTicks,
        List<InputTimelineTransition> transitions) {}

public enum InputTimelineTransitionState { EXECUTED, FAILED, NOT_EXECUTED }

public enum InputTimelineStopReason {
    COMPLETED, TIMED_OUT, LIFECYCLE_CHANGED, INPUT_FAILED,
    TICK_FAILED, EVIDENCE_LIMIT, CLEANUP_FAILED
}

public record InputTimelineTransitionEvidence(String transitionId, int timelineTick,
        String inputId, InputTimelineTransitionState state,
        Optional<InputInjection> injection, Optional<String> diagnostic) {}

public record InputTimelineBounds(int requestedTicks, int completedTicks,
        int requestedTransitions, int executedTransitions, int failedTransitions,
        int notExecutedTransitions, long encodedEvidenceBytes,
        int maximumTicks, int maximumTransitions, int maximumEncodedEvidenceBytes,
        long executionDeadlineNanos) {}

public record InputTimelineResult(InputTimelineStopReason stopReason, String message,
        ExecutionEpochId startingExecutionEpochId, long startingControlledTick,
        long fixedStepNanos, Optional<FrameId> firstFrameId, Optional<FrameId> finalFrameId,
        List<InputTimelineTransitionEvidence> transitions, InputTimelineBounds bounds,
        Optional<ApplicationFailureEvidence> applicationFailure) {}

public record InputTimelineOperation(String requestId, CommandLookup command,
        Optional<InputTimelineResult> result) {}
```

`InputTimelineLimits` must expose public constants `MAXIMUM_RETAINED_OPERATIONS`,
`MAXIMUM_TRANSITIONS`, `MAXIMUM_TICKS`, `MAXIMUM_ENCODED_EVIDENCE_BYTES`, and
`MAXIMUM_EXECUTION_NANOS`. Their absolute supported ceilings match replay/determinism: 100,000
operations/transitions/ticks, 16,777,216 bytes, and five minutes. Validate all fields positive and
within those ceilings. `InputTimelineTransition` accepts only boolean, integer,
decimal, string, enum, and string-backed entity-ID parameter values; reject null, vector, list, or
nested object values before registry lookup, and invokes the existing bounded runtime-value
validator for absolute string/node/depth limits. `InputTimelineSpec` copies the list, enforces the
absolute ceiling, stable non-decreasing order, range, and uniqueness.

Wire the builder without exposing a second registry:

```java
private InputTimelineLimits inputTimelineLimits = InputTimelineLimits.developmentDefaults();

public Builder inputTimelineLimits(InputTimelineLimits value) {
    inputTimelineLimits = Objects.requireNonNull(value, "value");
    return this;
}
```

Pass the value into the existing `InputRegistry` constructor. Keep validation in the record compact
and use `List.copyOf`/`Optional` normalization consistently with neighboring public records.

- [ ] **Step 4: Write ADR 0020 before execution code**

Record these exact decisions:

- one parent application-dispatched command executes the entire timeline;
- application handlers own persistent input state;
- transitions are explicit local-tick facts with list-order same-tick semantics;
- logical child input evidence reuses normal `InputInjection` without child dispatcher submission;
- partial application mutation is not rolled back or retried;
- hard input/tick/evidence/deadline limits fail closed; and
- recording schema 1 and replay consume normal input/tick evidence.

Include rejected schedule-only batching, runtime-owned held controls, and one-command-per-transition.

- [ ] **Step 5: Run focused tests and Javadocs**

Run:

```bash
./gradlew :runtime-core:test --tests '*InputTimelineContractTest' :runtime-core:javadoc --warning-mode=fail
```

Expected: all contract tests pass and Javadoc emits no warnings.

- [ ] **Step 6: Commit the contract**

```bash
git add runtime-core/src/main/java runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineContractTest.java docs/adr/0020-bounded-input-timeline-execution.md
git commit -m "feat: define bounded input timeline evidence"
```

---

### Task 2: Successful Exact-Tick Timeline Execution

**Files:**
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineCanonicalSize.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineExecutor.java`
- Create: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineRegistryTest.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputRegistry.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationControlRegistry.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/AgentRuntime.java`

**Interfaces:**
- Consumes: every Task 1 type and the existing application dispatcher/input/control/simulation paths.
- Produces: `InputRegistry#executeTimeline(InputTimelineSpec,String,Duration)`, `InputRegistry#timelineLimits()`, `InputRegistry#timelineAvailable()`, normal terminal child `InputInjection` evidence, and package-private `SimulationControlRegistry#tickExact(long,Runnable)`.

- [ ] **Step 1: Write the failing success, order, and idle-tick test**

Build a queued runtime with `SimulationTimelineSpec.fixedStep(10)`, an acknowledged controller, and
three inputs. Use this execution shape:

```java
InputTimelineSpec spec = new InputTimelineSpec(4, List.of(
        transition("press", 1, "active", RuntimeValues.bool(true)),
        transition("first", 2, "label", RuntimeValues.string("A")),
        transition("second", 2, "label", RuntimeValues.string("B")),
        transition("steer", 3, "amount", RuntimeValues.decimal("0.5")),
        transition("release", 4, "active", RuntimeValues.bool(false))));

InputTimelineOperation queued = runtime.inputs().executeTimeline(
        spec, "timeline-1", Duration.ofSeconds(2));
assertEquals(CommandState.QUEUED, queued.command().status().orElseThrow().state());
dispatch.removeFirst().run();
InputTimelineResult result = runtime.inputs().executeTimeline(
        spec, "timeline-1", Duration.ofSeconds(2)).result().orElseThrow();

assertEquals(InputTimelineStopReason.COMPLETED, result.stopReason());
assertEquals(4, result.bounds().completedTicks());
assertEquals(5, result.bounds().executedTransitions());
assertEquals(List.of("active:true", "label:A", "label:B", "amount:0.5", "active:false"),
        observed);
assertEquals(4, runtime.controls().currentTick());
assertEquals(List.of(1L, 2L, 2L, 3L, 4L), result.transitions().stream()
        .map(value -> value.injection().orElseThrow().actualTick().orElseThrow()).toList());
assertTrue(result.transitions().stream().allMatch(value ->
        value.injection().orElseThrow().resultingFrameId().isPresent()));
```

Assert that the simulation callback ran four times, proving tick 0 and idle intervals were not
skipped, and that a second identical poll does not add queue work or handler calls.

- [ ] **Step 2: Run the focused test and confirm the missing-method failure**

```bash
./gradlew :runtime-core:test --tests '*InputTimelineRegistryTest' --warning-mode=fail
```

Expected: compilation fails because `InputRegistry#executeTimeline` does not exist.

- [ ] **Step 3: Add the shared exact-tick primitive without changing public control behavior**

Extract the body currently shared conceptually by `tickForDeterminism` into:

```java
ExactTickEvidence tickExact(long deltaNanos, Runnable beforeSimulation) {
    SimulationControllerSpec spec = requireController();
    Objects.requireNonNull(beforeSimulation, "beforeSimulation");
    long tick;
    synchronized (this) {
        requireKnownPauseState();
        if (!paused) {
            throw new AgentRuntimeException(RuntimeErrorCode.INVALID_LIFECYCLE,
                    "exact tick execution requires paused simulation");
        }
        tick = Math.addExact(currentTick, 1);
    }
    SimulationTick simulationTick = runtime.simulation().tickControlled(
            deltaNanos, tick, spec.acknowledgedTick(), spec.tick(), beforeSimulation);
    FrameId frameId = simulationTick.resultingFrameId().orElseThrow(() ->
            new IllegalStateException("exact tick did not complete a frame"));
    synchronized (this) {
        currentTick = tick;
    }
    runtime.recordings().recordTick(tick, deltaNanos, runtime.currentEpoch(), frameId);
    return new ExactTickEvidence(simulationTick, runtime.frame(frameId).orElseThrow());
}
```

Define `record ExactTickEvidence(SimulationTick tick, FrameSnapshot frame)` and replace the current
package-private `DeterminismTickEvidence` references consistently in `SimulationControlRegistry`,
`DeterminismRegistry`, and `ReplayRegistry`. Make both determinism overloads delegate to this method
and preserve their existing input ordering. Do not route through public `advanceFixed(...)` or
submit another command.

- [ ] **Step 4: Implement aggregate reservation and the successful execution path**

`InputRegistry` owns the public entrypoint under its existing `submissionLock`:

```java
public InputTimelineOperation executeTimeline(
        InputTimelineSpec spec, String requestId, Duration timeout) {
    runtime.requireSubmissionsOpen();
    synchronized (submissionLock) {
        return timelines.execute(spec, requestId, timeout);
    }
}

public InputTimelineLimits timelineLimits() {
    return timelines.limits();
}

public boolean timelineAvailable() {
    return runtime.commands().isPresent()
            && runtime.controls().acknowledgedTicksAvailable()
            && runtime.simulation().state().configured()
            && !inputs.isEmpty();
}
```

`InputTimelineExecutor` must use one retained `LinkedHashMap<String, Evidence>` keyed by parent ID,
one active/reserved parent at a time, and a `Signature(InputTimelineSpec,long timeoutNanos)` so a
changed retry is rejected. Preflight the complete descriptor/handler/parameter set, parent/child ID
collisions, configured/effective limits, canonical result reservation, paused/acknowledged/fixed
state, and empty ordinary input staging before `CommandDispatch.submit`.

Extend `InputRegistry` with package-private operations that:

- reserve all transition IDs before submission;
- make room for the entire transition count atomically by evicting only terminal ordinary input
  evidence;
- stage all validated transitions at `startingControlledTick + timelineTick` in list order;
- attach a logical child `CommandStatus` whose `requestId` equals the transition ID;
- update child status `QUEUED -> EXECUTING -> SUCCEEDED/FAILED` around the existing handler;
- return normal `InputInjection` snapshots after each tick; and
- release exclusive staging while retaining bounded terminal child evidence.

The executor loop must call `tickExact(fixedStepNanos, lifecycleCheck)` once for every local tick and
update first/final frames and transition evidence only from authoritative returned tick/frame data.
Use the existing `recordings().recordInput(...)` and `replays().recordInput(...)` hooks for staged and
terminal child snapshots. The parent result is produced only after all four ticks and all five
transitions complete.

Logical child status timestamps use the parent submission time/deadline, the monotonic instant
immediately before the handler as `startedAtNanos`, and the instant immediately after success or
failure as `completedAtNanos`. The child lookup is `FOUND` while retained and its status request ID
must exactly equal `InputInjection.requestId()`; it is evidence for the logical child operation, not
a separately dispatched application command.

- [ ] **Step 5: Add saturating canonical size accounting**

`InputTimelineCanonicalSize` must use `DeterminismCanonicalSize.string(...)`,
`DeterminismCanonicalSize.value(...)`, and `DeterminismCanonicalSize.add(...)`; never materialize
JSON or `toString()` text. Count type tags, fixed-width numbers/count prefixes, optional tags,
diagnostics, child input fields, and structured failure fields. Add focused ASCII/multibyte and
saturation assertions to `InputTimelineContractTest`.

- [ ] **Step 6: Run the success tests and existing input/control regressions**

```bash
./gradlew :runtime-core:test \
  --tests '*InputTimelineContractTest' \
  --tests '*InputTimelineRegistryTest' \
  --tests '*InputRegistryTest' \
  --tests '*SimulationControlTest' \
  --tests '*SimulationDeterminismRegistryTest' \
  --tests '*ReplayRegistryTest' \
  --warning-mode=fail
```

Expected: all selected tests pass; ordinary input, determinism, replay, and public control outcomes
remain unchanged.

- [ ] **Step 7: Commit the successful engine**

```bash
git add runtime-core/src/main/java runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core
git commit -m "feat: execute exact-tick input timelines"
```

---

### Task 3: Fail-Stop Semantics, Deadlines, Exclusivity, and Retention

**Files:**
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineExecutor.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputRegistry.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationControlRegistry.java`
- Modify: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineRegistryTest.java`
- Modify: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/InputRegistryTest.java`

**Interfaces:**
- Consumes: Task 2 successful executor and logical child evidence.
- Produces: complete first-failure, timeout, lifecycle, eviction, redaction, and cleanup behavior with no automatic retry or rollback.

- [ ] **Step 1: Add failing preflight, collision, and exclusivity tests**

Cover these observable cases before implementation changes:

```java
assertThrows(IllegalArgumentException.class, () -> runtime.inputs().executeTimeline(
        new InputTimelineSpec(2, List.of(
                transition("valid", 1, "button", RuntimeValues.bool(true)),
                transition("invalid", 2, "missing", RuntimeValues.bool(false)))),
        "invalid-late", TIMEOUT));
assertEquals(0, handlerCalls.get());
assertTrue(dispatch.isEmpty());

InputTimelineOperation queued = runtime.inputs().executeTimeline(valid, "reserved", TIMEOUT);
assertThrows(AgentRuntimeException.class, () -> runtime.inputs().inject(
        "button", "ordinary", boolParameters(true), OptionalLong.empty(), TIMEOUT));
assertThrows(IllegalArgumentException.class, () -> runtime.inputs().executeTimeline(
        changedSpec, "reserved", TIMEOUT));
assertEquals(CommandState.QUEUED, queued.command().status().orElseThrow().state());
```

Also test parent/child ID equality, child collision with retained ordinary input or command status,
transition/input/tick/evidence limits, a queued resume that executes before the timeline callback,
determinism/replay exclusive mode conflicts, and retention eviction that never re-executes a handler.

- [ ] **Step 2: Add failing callback and post-callback deadline tests**

Use a mutable `AtomicLong` clock. Advance it inside an input handler and separately inside the final
acknowledged simulation callback:

```java
clock.set(1);
runtime.inputs().register(InputSpec.builder("expires")
        .requiredBoolean("active")
        .handler(parameters -> clock.set(101))
        .build());
InputTimelineSpec expires = new InputTimelineSpec(1, List.of(
        new InputTimelineTransition("expires-1", 1, "expires", boolParameters(true))));
runtime.inputs().executeTimeline(expires, "timeline-timeout", Duration.ofNanos(50));
dispatch.removeFirst().run();
InputTimelineResult result = runtime.inputs().executeTimeline(
        expires, "timeline-timeout", Duration.ofNanos(50)).result().orElseThrow();
assertEquals(InputTimelineStopReason.TIMED_OUT, result.stopReason());
assertEquals(0, result.bounds().completedTicks());
assertEquals(InputTimelineTransitionState.EXECUTED,
        result.transitions().getFirst().state());
```

For the final-tick callback clock advance, assert `completedTicks == 1` but stop reason remains
`TIMED_OUT`, never `COMPLETED`. Add input-handler and simulation-callback throw tests that assert the
first bounded `ApplicationFailureEvidence`, no raw message/stack trace, prior same-tick mutations
retained, and later transitions `NOT_EXECUTED`.

- [ ] **Step 3: Implement complete preflight and at-most-once reservation**

Validate timeout before retaining any parent or child ID. Compute effective limits as:

```java
int effectiveTransitions = Math.min(timelineLimits.maximumTransitions(),
        Math.min(inputLimits.queuedInputs(), inputLimits.retainedInjections()));
int effectiveTicks = Math.min(
        timelineLimits.maximumTicks(), runtime.controls().limits().ticksPerOperation());
long timeoutNanos = Math.min(dispatch.limits().maximumTimeoutNanos(),
        timelineLimits.maximumExecutionNanos());
```

Reject caller timeout above the effective duration instead of silently extending it. Reserve the
full result capacity and all IDs under the submission lock. Ordinary injection and determinism/
replay `beginDeterminism(...)` must reject while a timeline is reserved or executing; timeline
preflight must reject their exclusive state and any ordinary queued/scheduled/executed staging.

When dispatcher submission rejects or cancels before execution, publish a terminal non-completed
result with every transition `NOT_EXECUTED`; do not stage a handler or fabricate an actual tick.
Keep the bounded parent and child identity reservations until normal parent-result eviction.

- [ ] **Step 4: Implement stage-by-stage deadline and lifecycle checks**

Use one absolute saturated deadline. Invoke the same checker:

1. before staging/application mutation;
2. before each local tick;
3. after ordinary input handlers and before the simulation callback, through `tickExact`'s
   `beforeSimulation` callback;
4. after each simulation/capture return;
5. before terminal `COMPLETED`; and
6. after cleanup.

The checker must compare the frozen execution epoch, pause-known/paused state, acknowledged
controller availability, and configured fixed step. Deadline wins over later comparison or success
when the clock has expired. Catch handler/tick failures once, derive `INPUT_FAILED` when the current
child injection failed and otherwise `TICK_FAILED`, retain authoritative frames/counts already
completed, and never execute another transition.

The existing ordinary-input `executeTick(...)` intentionally attempts every due ordinary injection
before rethrowing its first failure. Preserve that behavior for ordinary input, but add an explicit
timeline mode that stops the due deque after the first failed timeline handler. It must mark every
remaining same-tick child `NOT_EXECUTED`, decrement its staged/outstanding accounting without calling
the handler, and pass those children to aggregate cleanup. Invoke the deadline/lifecycle checker
after each successful timeline handler as well as before simulation, so expiry or invalidation after
the first same-tick transition cannot execute the second transition.

Build evidence for every requested transition in request order. Attempted transitions contain their
normal injection; untouched transitions use `NOT_EXECUTED`, no injection, and a bounded deterministic
reason. Run exclusive-mode release in `finally`; convert cleanup failure to `CLEANUP_FAILED` while
retaining an earlier structured application failure if it fits.

- [ ] **Step 5: Implement bounded retention and close behavior**

Evict only terminal parent results in insertion order. If the configured retention is full and no
terminal result can be removed, reject before dispatch with `LIMIT_EXCEEDED`. Parent eviction
releases its unmaterialized transition-ID reservations; normal `InputInjection` evidence continues
under existing `InputLimits.retainedInjections` rules. `InputRegistry.close()` must clear active
timeline closures, staged transitions, parent operations, and reservations while retaining only the
immutable registered input catalog, matching existing input close semantics.

- [ ] **Step 6: Run the hardening tests**

```bash
./gradlew :runtime-core:test \
  --tests '*InputTimelineRegistryTest' \
  --tests '*InputRegistryTest' \
  --tests '*CommandDispatchTest' \
  --tests '*SimulationTimelineTest' \
  --warning-mode=fail
```

Expected: success, invalid lifecycle/thread use, bounds, eviction, ordering, redaction, timeout,
partial mutation, and structured failure tests pass.

- [ ] **Step 7: Commit fail-stop semantics**

```bash
git add runtime-core/src/main/java runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core
git commit -m "fix: make input timelines fail closed"
```

---

### Task 4: Recording Schema 1 and Replay-Ready Integration

**Files:**
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineExecutor.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputRegistry.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayRegistry.java`
- Modify: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayRegistryTest.java`
- Modify: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/InputTimelineRegistryTest.java`

**Interfaces:**
- Consumes: Task 3 complete transition and tick evidence.
- Produces: unchanged `RecordingInputEntry`/schema-1 manifests and replay sidecars that are conclusive only after a fully completed timeline.

- [ ] **Step 1: Add a failing successful capture/replay regression**

Start replay-ready capture from a registered deterministic scenario, execute a three-tick timeline
with two transitions, stop the ordinary recording, and replay it:

```java
runtime.replays().start(replaySpec("timeline-recording"), "capture-timeline", TIMEOUT);
dispatch.removeFirst().run();
InputTimelineSpec timeline = new InputTimelineSpec(3, List.of(
        transition("timeline-move", 1, "move", RuntimeValues.integer(2)),
        transition("timeline-stop", 3, "move", RuntimeValues.integer(0))));
runtime.inputs().executeTimeline(timeline, "execute-timeline", TIMEOUT);
dispatch.removeFirst().run();
runtime.recordings().stop("timeline-recording", "stop-timeline-recording", TIMEOUT);
dispatch.removeFirst().run();

RecordingChunk recording = runtime.recordings().get("timeline-recording", 0, 64);
assertEquals(2, recording.entries().stream()
        .filter(RecordingInputEntry.class::isInstance).count());
assertEquals(3, recording.entries().stream()
        .filter(RecordingTickEntry.class::isInstance).count());
runtime.replays().execute("timeline-recording", "replay-timeline", TIMEOUT);
dispatch.removeFirst().run();
ReplayResult replay = runtime.replays().execute(
        "timeline-recording", "replay-timeline", TIMEOUT).result().orElseThrow();
assertEquals(DeterminismStatus.EQUAL, replay.status());
assertEquals(3, replay.bounds().completedTicks());
```

Assert every manifest input is still a normal schema-1 `RecordingInputEntry`, transition list order
is preserved at a shared tick, and replay maps session-wide controlled targets back to authoritative
epoch-relative ticks.

- [ ] **Step 2: Add failing incomplete-capture regressions**

Add separate tests for:

- a redacted transition;
- an input handler failure with later `NOT_EXECUTED` transitions;
- an auto-stop caused by recording duration/item/encoded-size limits while future transitions are
  staged;
- epoch or pause invalidation before execution;
- timeout after the final simulation callback; and
- timeline evidence-byte exhaustion.

After stopping each replay-ready recording, assert `runtime.replays().execute(...)` returns
`INCONCLUSIVE`, never `EQUAL` or `DIVERGED`.

- [ ] **Step 3: Reuse normal input hooks and add timeline terminal testimony**

Ensure staging inserts every child into the existing `InputRegistry.requests`/scheduled evidence
path and invokes the normal recording/replay hook. Invoke the hook again after transition terminal
state/frame correlation changes so `RecordingRegistry.reconcileRequests(...)` sees the final normal
`InputInjection` without a new recording entry type.

Add one narrow package-private replay hook:

```java
synchronized void recordInputTimelineStop(InputTimelineStopReason reason) {
    if (activeCapture != null && reason != InputTimelineStopReason.COMPLETED) {
        activeCapture.markIncomplete("input timeline did not complete: "
                + reason.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
    }
}
```

Call it exactly once before the parent result is published. Existing replay freeze validation of
failed, scheduled, redacted, missing-frame, or non-succeeded input evidence remains authoritative;
do not special-case a failed child into a replay script.

- [ ] **Step 4: Run focused recording/replay tests**

```bash
./gradlew :runtime-core:test \
  --tests '*InputTimelineRegistryTest' \
  --tests '*RecordingRegistryTest' \
  --tests '*ReplayRegistryTest' \
  --warning-mode=fail
```

Expected: the successful timeline replays `EQUAL`; every incomplete case is `INCONCLUSIVE`; all
existing auto-stop, epoch, UI-eviction, and deadline regressions remain green.

- [ ] **Step 5: Commit recording/replay integration**

```bash
git add runtime-core/src/main/java runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core
git commit -m "feat: record and replay input timelines"
```

---

### Task 5: Closed Protocol 2.6 Vertical Slice

**Files:**
- Create: `runtime-protocol/src/test/java/io/github/teemuki8/libgdx/agent/runtime/protocol/InputTimelineProtocolTest.java`
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/ProtocolVersion.java`
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeCommand.java`
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeResponse.java`
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeProtocolService.java`
- Modify: `runtime-protocol/src/test/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeProtocolTest.java`

**Interfaces:**
- Consumes: Task 4 public core API and immutable result.
- Produces: exact protocol 2.6 `inputTimeline` command/result plus `input-timelines` capability and `runtime_input_timeline` tool advertisement.

- [ ] **Step 1: Write failing version, JSON, live execution, and capability tests**

Use this exact command shape:

```java
RuntimeCommand.InputTimeline command = new RuntimeCommand.InputTimeline(
        "protocol-timeline", 2, List.of(
                new InputTimelineTransition("protocol-press", 1, "button",
                        RuntimeValues.object(RuntimeValues.field(
                                "active", RuntimeValues.bool(true)))),
                new InputTimelineTransition("protocol-release", 2, "button",
                        RuntimeValues.object(RuntimeValues.field(
                                "active", RuntimeValues.bool(false))))),
        Duration.ofSeconds(2).toNanos());
RuntimeRequest request = new RuntimeRequest(
        ProtocolVersion.V2_6, "request", "timeline-session", command);
assertEquals(request, ProtocolJson.decodeRequest(ProtocolJson.encode(request)));
```

Execute it against a direct-dispatch paused fixed-step runtime and assert a
`RuntimeResponse.Result.InputTimeline` with `COMPLETED`, two ticks, and two ordered transitions.
Decode JSON with an unknown top-level command field and an unknown transition field and assert
rejection. Send the same command under V2.5 and assert exact message
`command requires protocol version 2.6` before handler execution.

Query V2.6 capabilities and assert:

- ID `input-timelines`;
- tool `runtime_input_timeline`;
- mode `exact-fixed-tick` plus bounded/application-owned/same-tick-order modes;
- configured and effective tick/transition/byte/deadline limits; and
- dependencies `command-dispatch`, `registered-inputs`, `simulation-timeline`, and
  `acknowledged-simulation-control`.

- [ ] **Step 2: Run the focused test and confirm the missing-version/type failure**

```bash
./gradlew :runtime-protocol:test --tests '*InputTimelineProtocolTest' --warning-mode=fail
```

Expected: compilation fails because `V2_6` and `RuntimeCommand.InputTimeline` do not exist.

- [ ] **Step 3: Add protocol 2.6 and the closed unions**

Add `ProtocolVersion.V2_6`, make it `CURRENT`, append it to the supported list/string, gate
`InputTimeline` at minor 6, and add its exact required-version message without changing prior
branches.

Use this command record:

```java
record InputTimeline(String timelineRequestId, int totalTicks,
        List<io.github.teemuki8.libgdx.agent.runtime.core.InputTimelineTransition> transitions,
        long timeoutNanos) implements RuntimeCommand {
    public InputTimeline {
        ProtocolJson.requireIdentifier(timelineRequestId, "timelineRequestId");
        requirePositive(totalTicks, "totalTicks");
        transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
        new io.github.teemuki8.libgdx.agent.runtime.core.InputTimelineSpec(
                totalTicks, transitions);
        requirePositive(timeoutNanos, "timeoutNanos");
    }
}
```

Add `RuntimeResponse.Result.InputTimeline(InputTimelineOperation operation)` with a non-null compact
constructor. Register exact Jackson subtype names `inputTimeline` for both command and result.

- [ ] **Step 4: Map execution and capability availability in the service**

The service mapping is transport-only:

```java
private static RuntimeResponse.Result inputTimeline(
        AgentRuntime runtime, RuntimeCommand.InputTimeline command) {
    if (!runtime.inputs().timelineAvailable()) {
        throw capabilityUnavailable(runtime);
    }
    return new RuntimeResponse.Result.InputTimeline(runtime.inputs().executeTimeline(
            new InputTimelineSpec(command.totalTicks(), command.transitions()),
            command.timelineRequestId(), Duration.ofNanos(command.timeoutNanos())));
}
```

Add `V2_6_TOOLS = List.of("runtime_input_timeline")` to per-version tools. Append this known closed
tool to the server-start union for protocol 2.6 even when a current session lacks its dependencies;
forced unavailable calls still return `CAPABILITY_UNAVAILABLE`. Absence of registered inputs leaves
the tool discoverable but the capability unavailable and the generated MCP transition schema
unsatisfiable.

Capability limits must report `configuredMaximumTransitions`, `effectiveMaximumTransitions`,
`configuredMaximumTicks`, `effectiveMaximumTicks`, `maximumEncodedEvidenceBytes`,
`retainedOperations`, and `maximumExecutionNanos` using the exact minima from Task 3.

- [ ] **Step 5: Run protocol tests and Javadocs**

```bash
.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh protocol
```

Expected: protocol tests and published-module Javadocs pass, including every exhaustive switch and
the unchanged protocol 1.x/2.0-2.5 fixtures.

- [ ] **Step 6: Commit protocol 2.6**

```bash
git add runtime-protocol
git commit -m "feat: expose input timelines in protocol 2.6"
```

---

### Task 6: Closed MCP Timeline Tool

**Files:**
- Create: `runtime-mcp/src/test/java/io/github/teemuki8/libgdx/agent/runtime/mcp/InputTimelineMcpTest.java`
- Modify: `runtime-mcp/src/main/java/io/github/teemuki8/libgdx/agent/runtime/mcp/RuntimeToolCatalog.java`
- Modify: `runtime-mcp/src/main/java/io/github/teemuki8/libgdx/agent/runtime/mcp/RuntimeToolHandler.java`
- Modify: `runtime-mcp/src/test/java/io/github/teemuki8/libgdx/agent/runtime/mcp/RuntimeMcpTest.java`

**Interfaces:**
- Consumes: protocol 2.6 command/result and the fixed server-start registered input catalog.
- Produces: `runtime_input_timeline` with exact registered-input parameter variants and strict natural-JSON mapping.

- [ ] **Step 1: Write failing catalog and live invocation tests**

Publish one paused fixed-step runtime with four inputs whose required parameter types are boolean,
integer, decimal, and string. Assert the catalog schema contains a closed transition branch for
each exact input ID and that this live request succeeds:

```java
Map<String, Object> arguments = Map.of(
        "sessionId", "mcp-timeline",
        "timelineRequestId", "mcp-timeline-1",
        "totalTicks", 4,
        "transitions", List.of(
                Map.of("transitionId", "bool", "timelineTick", 1,
                        "inputId", "boolean-input", "parameters", Map.of("value", true)),
                Map.of("transitionId", "integer", "timelineTick", 2,
                        "inputId", "integer-input", "parameters", Map.of("value", 7)),
                Map.of("transitionId", "decimal", "timelineTick", 3,
                        "inputId", "decimal-input", "parameters", Map.of("value", 0.5)),
                Map.of("transitionId", "string", "timelineTick", 4,
                        "inputId", "string-input", "parameters", Map.of("value", "stop"))),
        "timeoutNanos", Duration.ofSeconds(2).toNanos());
McpSchema.CallToolResult result = handler.handle(McpSchema.CallToolRequest
        .builder("runtime_input_timeline").arguments(arguments).build())
        .block(Duration.ofSeconds(2));
assertFalse(result.isError(), result::toString);
assertEquals(List.of(true, 7L, new BigDecimal("0.5"), "stop"), observed);
```

Validate the returned structured content contains protocol 2.6, `COMPLETED`, four completed ticks,
and four executed transitions. Add rejected calls for an unknown top-level field, unknown transition
field, wrong registered parameter type, nested list/object value, unknown input ID, duplicate child
ID, and out-of-order tick. Assert handler calls remain zero for every rejected request.

Construct a catalog with `runtime_input_timeline` supported and no input descriptors; assert the
tool is present and its transition item schema cannot validate a request.

- [ ] **Step 2: Run the focused test and confirm the unknown-tool failure**

```bash
./gradlew :runtime-mcp:test --tests '*InputTimelineMcpTest' --warning-mode=fail
```

Expected: the catalog does not yet contain `runtime_input_timeline`.

- [ ] **Step 3: Generate the closed registered-input-specific schema**

Add the tool only when the service advertises it. Its top-level required fields are exactly
`sessionId`, `timelineRequestId`, `totalTicks`, `transitions`, and `timeoutNanos`. Each transition
variant is the closed object:

```java
object(Map.of(
        "transitionId", string(),
        "timelineTick", integer(1, InputTimelineLimits.MAXIMUM_TICKS),
        "inputId", Map.of("type", "string", "const", descriptor.id()),
        "parameters", inputParameterObject(descriptor)),
        List.of("transitionId", "timelineTick", "inputId", "parameters"))
```

Use `oneOf` for mutually exclusive `inputId` constants. Do not use a generic natural-value object.
When the descriptor list is empty, use an always-false item schema such as
`Map.of("not", Map.of())` with `minItems: 1`. Apply the absolute transition `maxItems` and keep
`additionalProperties: false` at every object level.

- [ ] **Step 4: Parse exact transition keys and select protocol 2.6**

Add a handler helper that requires each map key set to equal
`Set.of("transitionId", "timelineTick", "inputId", "parameters")`, then delegates parameters to
the existing descriptor-aware `inputParameters(...)`:

```java
private List<InputTimelineTransition> inputTimelineTransitions(Object raw) {
    if (!(raw instanceof List<?> values)) {
        throw new IllegalArgumentException("input timeline transitions must be an array");
    }
    return values.stream().map(value -> {
        Map<String, Object> fields = stringMap(value, "input timeline transition");
        if (!fields.keySet().equals(Set.of(
                "transitionId", "timelineTick", "inputId", "parameters"))) {
            throw new IllegalArgumentException("input timeline transition is invalid");
        }
        String inputId = string(fields, "inputId");
        return new InputTimelineTransition(
                string(fields, "transitionId"),
                Math.toIntExact(number(fields, "timelineTick", -1)), inputId,
                inputParameters(inputId, fields.get("parameters")));
    }).toList();
}
```

Map `runtime_input_timeline` to `RuntimeCommand.InputTimeline` and `ProtocolVersion.V2_6`. Preserve
the existing boolean/integer/decimal/string/enum/entity mapping and reject nested types before
protocol dispatch.

- [ ] **Step 5: Run MCP tests and Javadocs**

```bash
.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh mcp
```

Expected: the schema and live invocations pass, unknown/nested values fail closed, existing MCP
tools remain unchanged, and Javadocs are warning-free.

- [ ] **Step 6: Commit MCP support**

```bash
git add runtime-mcp
git commit -m "feat: add closed MCP input timeline tool"
```

---

### Task 7: Native Box2D Fixture, Compiled Recipe, Transcript, and Public Documentation

**Files:**
- Modify: all fixture/example/test/transcript/document files listed under “Native fixture, compiled examples, transcript, and documentation”
- Modify: `docs/design-contract.md`
- Modify: `SECURITY.md`
- Modify: `README.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: the complete Java/protocol/MCP feature.
- Produces: actual-native and live same-JVM proof plus agent-visible documentation that matches unreleased protocol 2.6.

- [ ] **Step 1: Write the failing actual-native Box2D timeline test**

Extend the fixture application with a registered boolean `set-player-control` input whose `active`
value is application-owned state, reset/restored with the player scenario/checkpoint, and exposed as
an explicit `fixture.player-control.active` property. Keep the existing decimal `move-player`
handler.

The test timeline must contain:

```java
InputTimelineSpec timeline = new InputTimelineSpec(60, List.of(
        new InputTimelineTransition("control-on", 1, "set-player-control",
                RuntimeValues.object(RuntimeValues.field("active", RuntimeValues.bool(true)))),
        new InputTimelineTransition("velocity-two", 1, "move-player",
                RuntimeValues.object(RuntimeValues.field(
                        "velocityX", RuntimeValues.decimal("2")))),
        new InputTimelineTransition("velocity-four", 1, "move-player",
                RuntimeValues.object(RuntimeValues.field(
                        "velocityX", RuntimeValues.decimal("4")))),
        new InputTimelineTransition("control-off", 60, "set-player-control",
                RuntimeValues.object(RuntimeValues.field("active", RuntimeValues.bool(false))))));
InputTimelineResult result = runtime.inputs().executeTimeline(
        timeline, "native-input-timeline", Duration.ofSeconds(10)).result().orElseThrow();
```

Assert all 60 actual Box2D ticks complete, tick-1 evidence observes `active=true` and final evidence
observes `active=false`, the same-tick decimal updates leave velocity 4 rather than 2, the player
moves through idle ticks, all transition frame correlations exist, schema-1 recording contains four
normal input entries plus 60 tick entries, and replay returns `EQUAL`. This test must run against
actual desktop natives, not a mock or source-text check.

- [ ] **Step 2: Run the focused fixture test under Xvfb and confirm the missing API behavior**

```bash
xvfb-run -a ./gradlew :runtime-fixtures:test \
  --tests '*Box2dConformanceFixtureTest*inputTimeline*' --warning-mode=fail
```

Expected: the new fixture test fails until the semantic control and timeline workflow are wired.

- [ ] **Step 3: Update the compiled Java workflow and its test**

Replace the separate scheduled input plus `advanceFixed` pair inside the replay-ready portion of
`ControlledWorkflowExample#runWorkflow()` with one 60-tick timeline so existing long-run assertions
remain intact:

```java
InputTimelineSpec timeline = new InputTimelineSpec(60, List.of(
        new InputTimelineTransition("workflow-velocity", 1, "set-velocity",
                velocityParameters(2))));
runtime.inputs().executeTimeline(timeline, "workflow-input-timeline", TIMEOUT);
drainAll();
InputTimelineResult timelineResult = runtime.inputs().executeTimeline(
        timeline, "workflow-input-timeline", TIMEOUT).result().orElseThrow();
```

Expose `timelineResult.stopReason()`, completed ticks, first applied controlled tick, and transition
count in `WorkflowResult`. Update replay recording metadata to protocol `2.6` with
`RecordingCapabilityVersion("input-timelines", "2.6")`. Update `ControlledWorkflowExampleTest` to
assert `COMPLETED`, 60 ticks, one executed transition, recording input/tick counts, and replay
`EQUAL`.

- [ ] **Step 4: Replace the transcript input/advance pair with the closed timeline call**

Add this tested step to `controlled-workflow.json`:

```json
{
  "name": "input timeline",
  "tool": "runtime_input_timeline",
  "arguments": {
    "sessionId": "controlled-workflow-example",
    "timelineRequestId": "transcript-input-timeline",
    "totalTicks": 2,
    "transitions": [{
      "transitionId": "transcript-velocity",
      "timelineTick": 1,
      "inputId": "set-velocity",
      "parameters": {"velocityX": 2}
    }],
    "timeoutNanos": 5000000000
  },
  "dispatch": true,
  "expected": {
    "/type": "inputTimeline",
    "/operation/command/status/state": "SUCCEEDED",
    "/operation/result/stopReason": "COMPLETED",
    "/operation/result/bounds/completedTicks": 2,
    "/operation/result/transitions/0/state": "EXECUTED"
  }
}
```

Update `McpTranscriptTest`'s exact step-name list and representative envelope test to V2.6. Keep the
later entity/event/assertion/replay expectations identical so the transcript proves the timeline
produced the same observable trajectory.

- [ ] **Step 5: Add the cookbook recipe and tested drift assertions**

Add task-index row and heading `## Execute a deterministic input timeline`. Include:

- the exact compiled `InputTimelineSpec`/`executeTimeline` call;
- the exact MCP JSON from the transcript;
- local tick versus session controlled tick versus epoch-relative tick explanation;
- explicit boolean start/stop and decimal examples;
- same-tick list ordering and idle-tick behavior;
- polling with the identical parent request/spec/timeout;
- terminal requested/completed/executed/failed/not-executed bounds;
- timeout and partial mutation recovery by explicit reset/restore; and
- recording/replay behavior and redaction/incomplete caveats.

Update `AgentCookbookContractTest` to require the heading, `runtime.inputs().executeTimeline`,
`runtime_input_timeline`, `Protocol 2.6`, `NOT_EXECUTED`, and the transcript tool. Label protocol
2.5 replay and protocol 2.6 input timelines as `2.1.1-SNAPSHOT`/unreleased; keep released `2.1.0`
coverage through protocol 2.4.

- [ ] **Step 6: Update contract, security, tools, README, and changelog**

Add design-contract clause 42 for bounded input timeline execution and renumber the native fixture
clause to 43. State that discrete registered transitions execute in list order before exact local
ticks, persistent state is application-owned, no partial rollback occurs, and protocol 2.6 is
closed/additive.

In `SECURITY.md`, replace the stale statement that recording “provides no replay executor” with the
actual bounded replay-ready sidecar restriction, then add input timelines as closed registered
scalar sequences with no OS input, arbitrary object, script, expression, reflection, or hidden
loop. Update `agent-tools.md` tool table/footnote/protocol/capability/failure sections, README's
protocol range and fixture summary, and the Unreleased changelog.

- [ ] **Step 7: Run compiled examples and the real fixture gate**

```bash
./gradlew :runtime-examples:test --warning-mode=fail
.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh fixture
```

Expected: compiled Java recipe, machine-readable transcript, hidden same-JVM launcher, actual-native
Box2D test, and LWJGL3 smoke pass under Xvfb.

- [ ] **Step 8: Commit fixtures and documentation**

```bash
git add runtime-fixtures runtime-examples docs/design-contract.md docs/guides/agent-cookbook.md docs/guides/agent-tools.md SECURITY.md README.md CHANGELOG.md
git commit -m "docs: demonstrate deterministic input timelines"
```

---

### Task 8: Exact-Head Review and Full Qualification

**Files:**
- Inspect: every file changed since `047b168`
- Modify only if a focused regression or review finding requires it; commit each correction separately

**Interfaces:**
- Consumes: Tasks 1-7 complete vertical feature.
- Produces: reviewed exact head with focused, full, native, Javadoc, module-boundary, whitespace, and clean-worktree evidence.

- [ ] **Step 1: Inspect scope and accidental changes**

```bash
git status --short
git diff --stat 047b168..HEAD
git diff --check 047b168..HEAD
git log --oneline 047b168..HEAD
```

Expected: only input-timeline contract/core/protocol/MCP/fixture/example/documentation changes; no
generated output, dependency change, unrelated cleanup, trailing whitespace, or uncommitted file.

- [ ] **Step 2: Run focused vertical gates from the exact head**

```bash
.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh core
.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh protocol
.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh mcp
.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh fixture
```

Expected: every focused gate succeeds; the fixture gate reports isolated Xvfb execution on Linux.

- [ ] **Step 3: Perform requirement and security review**

Review the exact diff against
`docs/superpowers/specs/2026-08-10-input-timelines-design.md` and ADR 0020. Verify explicitly:

- no handler runs before full validation/reservation;
- same-tick transitions use list order;
- local tick 1 maps to the next controlled tick;
- deadline is checked after handlers, final tick callback, and capture;
- timeout/failure lists every unexecuted transition;
- parent/child retries cannot duplicate mutation;
- input parameters remain closed scalar values and redaction-safe;
- partial mutation is never described as rolled back;
- recording remains schema 1 and failed timeline replay is inconclusive;
- protocol 2.5 rejects the 2.6 command before dispatch;
- MCP rejects unknown/nested fields before mutation; and
- core has no non-JDK dependency or hidden thread/scheduler/sleep.

If a finding appears, first add the focused failing regression, run it red, implement the smallest
correction, run it green, and commit with a scoped `fix:` message.

- [ ] **Step 4: Run the clean full repository gate under Xvfb**

```bash
.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh full
```

Expected: `clean check javadoc` plus all actual-native fixture/example checks complete successfully
under the repository's Xvfb wrapper with `--warning-mode=fail` behavior.

- [ ] **Step 5: Verify the core module boundary and repository cleanliness**

```bash
jdeps --multi-release 25 --print-module-deps runtime-core/build/libs/runtime-core-*.jar
git diff --check
git status --short
git rev-parse HEAD
```

Expected: `jdeps` prints exactly `java.base`; both diff and status checks are clean; record the exact
verified head SHA.

- [ ] **Step 6: Request independent code review before integration**

Use the repository's requesting-code-review workflow on the exact verified head. Treat any Critical
or Important finding as a new test-first correction followed by the affected focused gate and the
full gate. Do not push or integrate merely because local verification and review pass.
