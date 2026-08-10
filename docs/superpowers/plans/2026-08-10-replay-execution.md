# Deterministic Replay Execution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add bounded deterministic replay execution that can capture a replay-ready recording from
an explicit scenario or checkpoint origin, execute the retained semantic input script through the
existing controlled-tick path, and report equal, first-divergence, or inconclusive evidence through
Java, protocol 2.5, MCP, and the real Box2D fixture.

**Architecture:** A new core `ReplayRegistry` owns a bounded replay sidecar correlated with an
ordinary `RecordingRegistry` recording. The sidecar stores one explicit origin, normalized baseline
and per-tick observable evidence, and accepted deterministic inputs. Replay restores the declared
origin, uses the existing input and fixed-tick machinery, and compares evidence with a package-private
comparator extracted from `DeterminismRegistry`. Existing schema-1 recordings, determinism checks,
application-owned dispatch, and local-stdio transport remain unchanged.

**Tech Stack:** Java 25, Gradle 9.6.1, JUnit 6, Jackson closed polymorphic JSON, MCP Java SDK,
libGDX/LWJGL3/Box2D 1.14.2, Xvfb on Linux.

## Global Constraints

- Preserve module direction: `runtime-mcp -> runtime-protocol -> runtime-core <- runtime-libgdx`;
  `runtime-fixtures` is test-only and unpublished. `runtime-core` remains JDK-only.
- Preserve the existing recording schema and public recording API. `replayGuaranteed=true` remains
  testimony unless the recording also has a complete retained replay sidecar.
- Accept exactly one application-registered origin: deterministic scenario reset or retained opaque
  checkpoint restore. Never reflect over application state, serialize arbitrary objects, infer reset
  logic, or add caller-selected filesystem/network access.
- Reuse the existing capture-thread command dispatch, deterministic input execution, controlled
  fixed-tick path, execution epochs, frame capture, and application-owned checkpoint/scenario code.
- Capture only contiguous paused, acknowledged fixed ticks beginning at epoch tick 1. Preserve
  accepted same-tick input order. Actions, redacted/failed inputs, running or unacknowledged ticks,
  epoch changes, gaps, diagnostics, truncation, timeout, and any exceeded bound make replay evidence
  incomplete rather than silently approximate.
- Compare the reset/restored baseline before tick 1, then compare each completed tick in order. Stop
  at the first difference. Report structural evidence only; never infer causality.
- Every public value is immutable, validates/copies inputs, uses stable ordering, has warning-free
  Javadocs, and carries explicit observed/retained/limit or terminal-inconclusive evidence.
- Protocol and MCP inputs remain versioned, closed, bounded, and reject unknown fields. Protocol 2.4
  must reject replay commands deterministically while all existing 2.4 payloads remain byte-shape
  compatible.
- Every changed public Java, protocol, MCP, or agent-visible behavior must update the tested recipe
  and transcript in `docs/guides/agent-cookbook.md` in the same branch.
- On Linux, the real LWJGL3 fixture and the full gate run under `xvfb-run`; never substitute the
  active desktop display.
- Do not push, open a pull request, stage/publish Maven artifacts, or otherwise distribute this work
  without a later explicit user authorization.

---

### Task 1: Immutable replay contracts and hard limits

**Files:**
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayLimits.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayCaptureSpec.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayPhase.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayDivergence.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayBounds.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayResult.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayCaptureOperation.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayOperation.java`
- Test: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayContractTest.java`

**Interfaces:**
- `ReplayLimits(int retainedOperations, int maximumInputs, int maximumTicks,
  int maximumEntitiesPerFrame, int maximumFactsPerFrame, int maximumEncodedEvidenceBytes,
  long maximumExecutionNanos)` plus `developmentDefaults()`.
- Defaults: 32 retained operations, 4,096 inputs, 600 ticks, 10,000 entities per frame,
  100,000 facts per frame, 1,048,576 encoded evidence bytes, and 30 seconds execution time.
- `ReplayCaptureSpec(RecordingSpec recording, DeterminismProfile profile,
  List<SimulationConfigurationRequirement> configurationRequirements,
  List<SimulationEvidenceRequirement> evidenceRequirements, List<EventType> eventTypes)`.
- `ReplayDivergence` identifies `BASELINE` or `SIMULATION_TICK`, optional positive epoch tick and
  tick IDs, both epoch/frame identities, and one existing `DeterminismDifference`.
- `ReplayBounds` reports requested/completed ticks, recorded inputs, observed entities/facts,
  encoded evidence bytes, and the effective execution deadline in nanoseconds.
- `ReplayResult` reports existing `DeterminismStatus`, a bounded message, recording/profile,
  optional first divergence, bounds, and optional `ApplicationFailureEvidence`.
- `ReplayCaptureOperation` and `ReplayOperation` mirror existing retained command-operation records:
  recording/request IDs, `CommandLookup`, and optional terminal evidence.

- [x] Write constructor tests for limit boundaries, mutable collection copying, duplicate/unsorted
  requirements, invalid origin/phase/status optionals, identifiers, and inconsistent counters.
- [x] Assert `ReplayCaptureSpec` rejects zero or two origins, `replayGuaranteed=false`, and
  requirements beyond existing deterministic-selection limits. Assert it copies and canonically
  sorts all lists without changing caller-owned values.
- [x] Assert baseline divergence forbids epoch tick/tick IDs, tick divergence requires a positive
  epoch tick and both tick IDs, `EQUAL` forbids divergence/failure, and `DIVERGED` requires exactly
  one divergence. Assert `INCONCLUSIVE` never fabricates a difference.
- [x] Run
  `./gradlew :runtime-core:test --tests '*ReplayContractTest' --warning-mode=fail` and observe the
  expected missing-type compilation failure.
- [x] Implement only the immutable records, closed enum, validation, stable copies, defaults, and
  hard maxima. Reuse existing ID, profile, status, difference, requirement, and failure types; do
  not introduce replay execution behavior yet.
- [x] Run the focused test green, then `git diff --check`.
- [x] Commit `feat: define bounded replay contracts`.

### Task 2: Shared observable-evidence comparator without determinism drift

**Files:**
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ObservableEvidenceComparator.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/DeterminismRegistry.java`
- Inspect (unchanged; already package-private and reusable):
  `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/DeterminismCanonicalSize.java`
- Create: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/ObservableEvidenceComparatorTest.java`
- Test: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/DeterminismRegistryTest.java`
- Test: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationDeterminismRegistryTest.java`

**Internal interface:**
- Package-private `ObservableEvidenceComparator` owns evidence normalization and comparison currently
  private to `DeterminismRegistry`.
- Nested package-private `Limits(maximumEntitiesPerFrame, maximumFactsPerFrame,
  maximumEncodedEvidenceBytes)`, mutable per-operation `Counters`, and immutable `FrameEvidence`.
- `capture(FrameSnapshot, DeterminismProfile, List<EventType>, Limits, Counters)` returns normalized
  evidence or an incomplete reason.
- `configurationProblem`, `evidenceProblem`, and `selectionProblem` retain current deterministic
  precondition semantics.
- `difference(FrameEvidence, FrameEvidence)` returns the existing first canonical
  `DeterminismDifference`.

- [x] Add characterization tests that build frames containing entities, numeric facts, events,
  decisions, UI correlations, missing requirements, diagnostics, and low limits. Assert volatile
  normalization, exact first-difference priority, exact counters, and exact incomplete reasons while
  retaining the existing determinism suites for the broader selection/truncation matrix.
- [x] Run
  `./gradlew :runtime-core:test --tests '*ObservableEvidenceComparatorTest' --warning-mode=fail` and
  observe missing comparator compilation failure.
- [x] Move the existing private capture, selection/configuration/evidence validation, comparable
  records, counters, and difference functions into the package-private comparator. Keep canonical
  byte accounting in `DeterminismCanonicalSize`; do not duplicate JSON serialization or add a public
  abstraction.
- [x] Change `DeterminismRegistry` to delegate to the comparator with its existing limits. Preserve
  messages, ordering, timeout semantics, retained operations, status, and every public record shape.
- [x] Run the focused comparator and both determinism suites green. Then run
  `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh core` and require no determinism-test
  changes beyond the new characterization coverage.
- [x] Commit `refactor: share deterministic evidence comparison`.

### Task 3: Replay-ready recording start and baseline capture

**Files:**
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayRegistry.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayCanonicalSize.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/AgentRuntime.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/RecordingRegistry.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ScenarioRegistry.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/CheckpointRegistry.java`
- Create: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayRegistryTest.java`
- Test: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/RecordingRegistryTest.java`
- Test: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/AgentRuntimeTest.java`

**Public interface:**
```java
public final class ReplayRegistry implements AutoCloseable {
    public ReplayCaptureOperation start(
            ReplayCaptureSpec spec, String requestId, Duration timeout);
    public ReplayOperation execute(String recordingId, String requestId, Duration timeout);
    public ReplayLimits limits();
}
```

**Package-private hooks:**
```java
void recordAction(ActionInvocation invocation, RuntimeValue.ObjectValue parameters);
void recordInput(InputInjection injection);
void recordTick(SimulationTick tick);
void freeze(String recordingId);
void recordingEvicted(String recordingId);
```

- [x] Add scenario-origin start tests. Require the application already be paused, dispatch on the
  capture thread, reuse the recording seed/configuration in `ScenarioResetContext`, begin a new
  scenario-reset execution epoch, capture frame 0 as replay baseline, and start exactly one ordinary
  recording with the caller's unchanged schema-1 `RecordingSpec`. Assert the sidecar freezes the
  currently registered fixed-step nanoseconds.
- [x] Add checkpoint-origin start tests. Restore the retained opaque checkpoint handle through its
  registered application provider, preserve seed/configuration as testimony only, begin one
  checkpoint-restore epoch, capture frame 0, and start the ordinary recording only after successful
  restoration and baseline validation.
- [x] Add start-path rejection tests for a running simulation, queued ordinary input, failed origin
  reset, and duplicate request polling. Assert structured failure state and no partially active
  recording. Prove that successful origin restoration followed by incomplete baseline evidence
  starts the ordinary recording with an incomplete sidecar; hardening coverage for every remaining
  lifecycle, bound, and request-id case belongs to Task 5.
- [x] Run
  `./gradlew :runtime-core:test --tests '*ReplayRegistryTest' --warning-mode=fail` and observe the
  expected missing-registry/accessor failure.
- [x] Add `ReplayLimits` to `AgentRuntime.Builder`, construct `ReplayRegistry` after
  `RecordingRegistry`, expose `AgentRuntime.replays()`, and close it through the existing capture-
  thread lifecycle. Do not add a scheduler or worker.
- [x] Add package-private direct scenario reset/checkpoint restore helpers used only while already
  inside replay's dispatched command. They must call the same application-owned providers and epoch
  transitions as the existing public commands without nesting command dispatch.
- [x] Add a package-private recording-start helper that preserves ordinary recording validation and
  publication. Store replay-only evidence in the sidecar, never in or by changing `Recording`.
- [x] Use `ReplayCanonicalSize` for saturating canonical byte accounting of capture metadata and
  baseline evidence. Task 4 extends the same accounting to the input script and tick evidence. Do
  not measure Java heap size or serialize arbitrary application values.
- [x] Run `ReplayRegistryTest`, `RecordingRegistryTest`, and `AgentRuntimeTest` green, then run
  `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh core`.
- [x] Commit `feat: start replay-ready recordings`.

### Task 4: Capture a contiguous semantic input and fixed-tick script

**Files:**
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayRegistry.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/RecordingRegistry.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ActionRegistry.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputRegistry.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationTimelineRegistry.java`
- Test: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayRegistryTest.java`
- Test: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/InputRegistryTest.java`
- Test: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/RecordingRegistryTest.java`

**Capture invariants:**
- A replay-ready sidecar accepts only paused, acknowledged, fixed-source ticks in the baseline epoch,
  contiguous from epoch tick 1.
- Accepted input requests are retained in first-observation acceptance order and reconciled to the
  resulting frame/tick; same-tick order is never sorted by ID.
- Every observed tick is reported to replay capture, including running, failed, unacknowledged, and
  mismatched-epoch ticks, so an invalid interval cannot disappear from evidence.
- Ordinary action invocation marks the sidecar incomplete; action replay is outside this slice.

- [x] Add tests for two inputs on one tick, inputs on separate ticks, ticks with no inputs, rejected
  inputs, redacted parameters, failed application handlers, and request reconciliation. Assert only
  successfully accepted explicit input commands enter the script and their stable acceptance order
  survives freeze.
- [x] Add capture tests for first-reason stability across an action plus running tick, failed input/
  tick, incomplete baseline, and tick/input hard bounds. The shared closed validation path also
  rejects wrong-source, unacknowledged, mismatched-epoch, noncontiguous, diagnostic/truncated,
  counter, and byte evidence; Task 5 exercises those terminal reasons through public replay results.
- [x] Add stop and eviction tests. `RecordingRegistry.stop()` must reconcile/freeze the sidecar before
  publishing the stopped recording. Recording retention eviction must also evict replay evidence and
  leave a distinguishable known-evicted lookup; an ordinary recording with no sidecar remains an
  ordinary recording.
- [x] Run the focused replay test and observe failures because lifecycle registries do not yet notify
  the replay sidecar.
- [x] Forward immutable action/input/tick evidence to `ReplayRegistry` after the owning registry has
  performed its normal validation. Keep the replay hook package-private and non-throwing with respect
  to the already-completed application operation; internal capture failure marks replay incomplete.
- [x] Update the sidecar only on the capture thread. Freeze immutable input/tick/evidence lists on
  stop, retain operations within `ReplayLimits.retainedOperations`, and surface explicit truncation/
  eviction evidence rather than retaining a partial replay as complete.
- [x] Run all focused tests green and `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh core`.
- [x] Commit `feat: capture deterministic replay scripts`.

### Task 5: Execute scenario and checkpoint replays

**Files:**
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayRegistry.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationControlRegistry.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/InputRegistry.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ScenarioRegistry.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/CheckpointRegistry.java`
- Test: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/ReplayRegistryTest.java`
- Test: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationDeterminismRegistryTest.java`

**Execution sequence:**
1. Resolve a stopped recording and complete sidecar; otherwise retain an `INCONCLUSIVE` result.
2. Validate no active recording/replay, no queued ordinary input, a paused application, and the
   caller timeout clamped to `ReplayLimits.maximumExecutionNanos`.
3. Enter the existing deterministic-input mode, restore/reset the explicit origin, and capture the
   new baseline.
4. Compare baseline. If equal, execute each recorded fixed delta with that tick's retained inputs via
   the existing controlled-tick helper and compare each resulting frame.
5. Stop at first difference or failure, restore input/pause state in `finally`, and retain one
   terminal operation.

- [x] Add a scenario replay that reproduces baseline and several ticks with deterministic inputs.
  Assert `EQUAL`, zero divergence, exact requested/completed/input/bounds counters, a new execution
  epoch, and no mutation of the source recording or its sidecar.
- [x] Add a checkpoint replay with the same assertions. Prove the checkpoint provider, rather than a
  scenario reset or hidden state copy, performs restoration.
- [x] Add baseline-divergence tests and per-tick divergence tests. Assert the first unequal tick is
  reported with exact reference/replay epoch, frame and tick IDs, prior ticks counted completed, and
  one existing structural `DeterminismDifference`; later ticks must not execute.
- [x] Add lifecycle coverage for running state, unknown/active/ordinary/evicted/incomplete
  recordings, duplicate request IDs, an active replay command, queued input, and replay-time input
  failure. Preserve the existing shared wrong-thread/close command gates; Task 9 rechecks stale
  origins and registration/evidence drift at the full public surface.
- [x] Add deadline tests before origin reset and after baseline using the runtime clock seam. The same
  per-tick deadline branch runs before every retained tick. Assert bounded `INCONCLUSIVE`, no extra
  tick, and deterministic cleanup.
- [x] Run the focused replay test and observe failures because `execute` is not implemented.
- [x] Execute through `InputRegistry.beginDeterminism/executeDeterminismInputs/endDeterminism` and
  `SimulationControlRegistry.tickForDeterminism`; rename package-private helpers only if needed to
  remove determinism-specific naming, without changing their behavior or visibility.
- [x] Use `ObservableEvidenceComparator` for baseline/tick capture and comparison. Keep checkpoint
  seed/configuration as recorded testimony only; baseline comparison is the proof that restored state
  matches. Do not separately mutate application configuration after checkpoint restore.
- [x] Store one terminal `ReplayOperation`; turn cleanup/application/evidence failures into
  `INCONCLUSIVE` with bounded `ApplicationFailureEvidence`. Restore the exact previous pause and input
  mode even after divergence or failure.
- [x] Run replay and determinism tests green, then
  `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh core`.
- [x] Commit `feat: execute deterministic recordings`.

### Task 6: Protocol 2.5 commands and closed JSON results

**Files:**
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/ProtocolVersion.java`
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeCommand.java`
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeResponse.java`
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeProtocolService.java`
- Create: `runtime-protocol/src/test/java/io/github/teemuki8/libgdx/agent/runtime/protocol/ReplayProtocolTest.java`
- Test: `runtime-protocol/src/test/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeProtocolTest.java`

**Commands:**
```java
record ReplayRecordingStart(
        String recordingId,
        String replayRequestId,
        String scenarioId,
        String checkpointId,
        Long randomSeed,
        RuntimeValue.ObjectValue configuration,
        DeterminismProfile profile,
        List<SimulationConfigurationRequirement> configurationRequirements,
        List<SimulationEvidenceRequirement> evidenceRequirements,
        List<EventType> eventTypes,
        long timeoutNanos) implements RuntimeCommand {}

record Replay(
        String recordingId,
        String replayRequestId,
        long timeoutNanos) implements RuntimeCommand {}
```

**Results and compatibility:**
- The closed JSON command discriminators are exactly `replayRecordingStart` and `replay`.
- Add `RuntimeResponse.Result.ReplayCapture(ReplayCaptureOperation operation,
  Optional<ApplicationFailureEvidence> applicationFailure)` and
  `RuntimeResponse.Result.Replay(ReplayOperation operation,
  Optional<ApplicationFailureEvidence> applicationFailure)`.
- Add `ProtocolVersion.V2_5`, make it current, and advertise capability `replay-execution` only to
  negotiated minor version 5 or newer.

- [x] Add exact JSON round-trip tests for scenario and checkpoint start requests, dispatched replay
  execution, and an equal result. Assert closed nested objects, invalid origin combinations,
  oversized selector lists, unknown fields, and absent optionals. Task 9 replays baseline/tick
  divergence and inconclusive payload fixtures through the complete public stack.
- [x] Add dispatched/pending/polled operation tests proving at-most-once request IDs, identical retry
  lookup, conflicting reuse rejection, and stable terminal replay evidence.
- [x] Add negotiation tests proving 2.5 advertises and accepts replay while 2.4 deterministically
  rejects both replay commands as unsupported. Re-run frozen 2.0-2.4 command/result fixtures and
  assert no existing discriminator or payload shape changed.
- [x] Run
  `./gradlew :runtime-protocol:test --tests '*ReplayProtocolTest' --warning-mode=fail` as the focused
  protocol gate.
- [x] Add the two closed command subtypes and two closed result subtypes. In
  `RuntimeProtocolService`, derive the unchanged schema-1 `RecordingSpec` with
  `replayGuaranteed=true`, version/capability metadata from the negotiated session, and exactly one
  origin before calling `runtime.replays()`.
- [x] Add the `replay-execution` descriptor modes for scenario reset, checkpoint restore, exact fixed
  tick, first divergence, and inconclusive-safe execution. Advertise replay/recording limits in its
  effective metadata, and expose the capability only when dispatch, acknowledged simulation control,
  a configured fixed step, recording, and at least one explicit origin provider are available.
- [x] Keep `scenarioId` and `checkpointId` mutually exclusive at validation; do not introduce a
  polymorphic arbitrary origin payload. Map all core typed errors through existing response failure
  conventions without stack traces.
- [x] Run replay and full protocol tests green, then
  `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh protocol`.
- [x] Commit `feat: expose replay protocol commands`.

### Task 7: Local stdio MCP replay tools

**Files:**
- Modify: `runtime-mcp/src/main/java/io/github/teemuki8/libgdx/agent/runtime/mcp/RuntimeMcpServer.java`
- Modify: `runtime-mcp/src/main/java/io/github/teemuki8/libgdx/agent/runtime/mcp/ConstrainedMcpJsonMapper.java`
- Create: `runtime-mcp/src/test/java/io/github/teemuki8/libgdx/agent/runtime/mcp/ReplayMcpTest.java`
- Test: `runtime-mcp/src/test/java/io/github/teemuki8/libgdx/agent/runtime/mcp/RuntimeMcpTest.java`

**Tools:**
- `runtime_replay_recording_start`: closed input fields `sessionId`, `recordingId`,
  `replayRequestId`, `originKind` (`scenario` or `checkpoint`), `originId`, optional `randomSeed`,
  `configuration`, `profile`, configuration/evidence requirements, event types, and timeout.
- `runtime_replay`: closed input fields `sessionId`, `recordingId`, `replayRequestId`, and timeout.

- [ ] Add tool-list tests proving the two tools appear only with capability `replay-execution` in a
  2.5 session and do not appear in 2.4.
- [ ] Add exact invocation/result tests for both origin kinds and all three terminal statuses. Assert
  the MCP schema has `additionalProperties:false` at every object layer, required fields are exact,
  bounds are enforced before dispatch, and unknown fields/origin kinds/IDs/profiles are rejected.
- [ ] Add application-failure and pending-command tests. Assert results use structured content and
  bounded diagnostics without serialized Java exception/stack content. Poll the existing command
  lookup path and assert it reaches the same retained terminal operation without re-execution.
- [ ] Add unavailable-session tests for missing replay prerequisites and for a 2.5 server whose
  selected session omitted `replay-execution`; assert `CAPABILITY_UNAVAILABLE` and no core dispatch.
- [ ] Run `./gradlew :runtime-mcp:test --tests '*ReplayMcpTest' --warning-mode=fail` and observe the
  missing-tool failure.
- [ ] Register the two tools through the existing local same-JVM stdio server. Map `originKind` plus
  `originId` to the protocol command's exclusive scenario/checkpoint fields. Do not add a listener,
  transport, background thread, or promise of cross-JVM live inspection.
- [ ] Extend constrained JSON mapping only for the new closed replay records and preserve current
  numeric/string/depth/collection bounds.
- [ ] Run replay and full MCP tests green, then
  `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh mcp`.
- [ ] Commit `feat: add bounded replay MCP tools`.

### Task 8: Real Box2D and hidden LWJGL3 replay qualification

**Files:**
- Modify: `runtime-fixtures/src/main/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/Box2dConformanceSimulation.java`
- Modify: `runtime-fixtures/src/main/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/Box2dConformanceApplication.java`
- Modify: `runtime-fixtures/src/main/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/McpFixtureApplication.java`
- Modify: `runtime-fixtures/src/test/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/Box2dConformanceFixtureTest.java`
- Modify: `runtime-fixtures/src/test/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/FixtureProtocolAndMcpTest.java`
- Modify: `runtime-fixtures/src/test/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/Lwjgl3FixtureSmokeTest.java`

**Fixture proof:**
- Use the actual Box2D world, explicit stable body/fixture IDs, registered deterministic input, real
  fixed-step callback, real frame capture, and application-owned reset/checkpoint providers.
- Add one fixture-owned test seam that changes how an already registered semantic input is applied;
  it may be enabled only by the test/application and must not enter runtime production code.

- [ ] Add Java fixture tests that capture and replay a scenario-origin trajectory through at least
  120 actual Box2D ticks with an input at a known tick. Assert `EQUAL`, exact tick/frame/epoch correlation,
  and identical structured body/contact evidence.
- [ ] Add a checkpoint-origin Java fixture test with the same real native path and exact equality.
- [ ] Enable the fixture's input-effect seam only for replay and assert first divergence at the known
  input tick, all prior ticks completed equal, later ticks unexecuted, and the difference points to
  observable evidence rather than a claimed cause.
- [ ] Add protocol and MCP fixture tests for replay-ready start, stopped recording, replay, and exact
  structured terminal result through the real simulation.
- [ ] Extend the hidden LWJGL3 smoke application to execute the equal replay path on the render thread
  and fail its existing completion signal on any non-equal result or native/application exception.
- [ ] Run the focused fixture tests under Xvfb and initially observe missing fixture wiring:
  `xvfb-run -a ./gradlew :runtime-fixtures:test --tests '*Box2dConformanceFixtureTest' --tests
  '*FixtureProtocolAndMcpTest' --tests '*Lwjgl3FixtureSmokeTest' --warning-mode=fail`.
- [ ] Wire the minimum fixture-owned reset/checkpoint/input behavior. Preserve application ownership
  of `World.step`, listener installation, render loop, assets, and disposal; runtime code must not
  access native objects directly.
- [ ] Run `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh fixture` green under Xvfb.
- [ ] Commit `test: qualify replay with real Box2D`.

### Task 9: Tested example, cookbook, ADR, and compatibility documentation

**Files:**
- Modify: `runtime-examples/src/main/java/io/github/teemuki8/libgdx/agent/runtime/examples/ControlledWorkflowExample.java`
- Modify: `runtime-examples/src/test/java/io/github/teemuki8/libgdx/agent/runtime/examples/ControlledWorkflowExampleTest.java`
- Modify: `runtime-examples/src/main/resources/transcripts/controlled-workflow.json`
- Modify: `runtime-examples/src/test/java/io/github/teemuki8/libgdx/agent/runtime/examples/McpTranscriptTest.java`
- Modify: `runtime-examples/src/test/java/io/github/teemuki8/libgdx/agent/runtime/examples/AgentCookbookContractTest.java`
- Create: `docs/adr/0019-bounded-replay-execution.md`
- Modify: `docs/design-contract.md`
- Modify: `docs/guides/agent-tools.md`
- Modify: `docs/guides/agent-cookbook.md`
- Modify: `README.md`
- Modify: `docs/roadmap.md`
- Modify: `CHANGELOG.md`

**Documentation contract:**
- The cookbook recipe is executable and shows pause, replay-ready start from a scenario, fixed ticks
  with registered inputs, stop, replay, `EQUAL`, and a separate first-divergence result.
- The transcript negotiates protocol 2.5 and shows both MCP tools with exact closed request/result
  shapes and limits.
- ADR 0019 records why replay is a sidecar, why origin is explicit, why actions are excluded, why
  checkpoint configuration is testimony, and why structural difference is not causal attribution.

- [ ] Add/extend example tests first. Assert the Java recipe compiles/runs and the committed JSON
  transcript exactly matches generated 2.5 MCP traffic. Run
  `./gradlew :runtime-examples:test --tests '*ControlledWorkflowExampleTest' --tests
  '*McpTranscriptTest' --tests '*AgentCookbookContractTest' --warning-mode=fail` and observe the
  missing recipe/transcript expectations.
- [ ] Extend `ControlledWorkflowExample` with the smallest deterministic replay-ready workflow and
  regenerate/update the checked transcript using the repository's existing deterministic mechanism;
  do not handwave values that the contract test can derive.
- [ ] Add cookbook Java, protocol, and MCP recipes covering both origins, fixed-tick/input capture,
  equal/diverged/inconclusive results, baseline versus tick divergence, timeout, truncation,
  eviction, unsupported 2.4 behavior, and application failure. State explicitly that ordinary
  `replayGuaranteed` recordings are not executable.
- [ ] Add ADR 0019 and update the design contract/security boundary. Document application ownership,
  capture-thread requirements, no reflection/causality, bounded sidecar retention, and local stdio.
- [ ] Update README compatibility/capability tables from protocol 2.4 to 2.5. Move deterministic
  replay from roadmap research to delivered additive capability, add a concise `[Unreleased]`
  changelog entry, and leave frozen `docs/releases/2.1.0.md` unchanged.
- [ ] Run the focused example/docs tests green, Javadocs for all changed public APIs, and
  `git diff --check`.
- [ ] Commit `docs: document bounded replay execution`.

### Task 10: Compatibility audit, independent review, and full verification

- [ ] Review `main..HEAD` against the approved design at
  `docs/superpowers/specs/2026-08-10-replay-execution-design.md` and every checklist item in this
  plan. Confirm there is exactly one origin, one sidecar per replay-ready recording, baseline-first
  comparison, contiguous ticks, stable input order, first-divergence stop, and explicit incomplete
  evidence for every invalid condition.
- [ ] Inspect all new public records/methods for defensive copies, validation, stable ordering,
  warning-free Javadocs, hard bounds, closed enums, and no leaked mutable/provider/native values.
- [ ] Inspect module dependencies and implementation for reflection, arbitrary serialization,
  filesystem/network/listener/thread additions, inferred causality, checkpoint state inspection,
  action replay, or changes to the schema-1 `Recording` shape. Remove any such scope drift.
- [ ] Run all focused new suites once more:
  `./gradlew :runtime-core:test --tests '*Replay*' :runtime-protocol:test --tests '*Replay*'
  :runtime-mcp:test --tests '*Replay*' :runtime-examples:test --tests '*ControlledWorkflow*' --tests
  '*McpTranscript*' --tests '*AgentCookbookContract*' --warning-mode=fail`.
- [ ] Run `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh full` and require its
  Xvfb-backed `clean check javadoc --warning-mode=fail` gate to pass from a fresh build.
- [ ] Run
  `jdeps --multi-release 25 --print-module-deps runtime-core/build/libs/runtime-core-*.jar` and
  require exactly `java.base`.
- [ ] Run `git diff --check`, inspect `git status --short`, and confirm the branch contains only the
  approved replay slice plus its design/plan/docs, with no generated junk, credentials, publish
  task, or unrelated user change.
- [ ] Perform a fresh exact-head review after the final fix. Any Critical or Important finding gets
  a focused failing regression first, the smallest fix, the relevant focused gate, and another full
  gate before completion.
- [ ] Commit any review-only corrections with a narrowly descriptive message. Do not push, open a
  pull request, tag, stage, or publish; report the verified local branch/head and wait for explicit
  distribution authorization.
