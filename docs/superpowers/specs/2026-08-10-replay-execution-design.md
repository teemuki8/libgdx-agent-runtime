# Replay Execution Design

**Status:** Approved
**Date:** 2026-08-10
**Scope:** Bounded deterministic execution of replay-ready recordings

## Goal

Make an explicitly replay-ready recording executable through registered application capabilities.
The runtime must recreate or restore a known starting state, apply the recorded semantic inputs in
stable order, advance exact fixed simulation ticks, and return `EQUAL`, `DIVERGED`, or
`INCONCLUSIVE` evidence at the earliest observable mismatch.

This design is the first independently deliverable part of the broader deterministic execution,
input timeline, performance evidence, failure localization, and integration roadmap. It does not
implement the other roadmap tracks.

## Existing Gap

Recording schema 1 retains bounded input outcomes, action outcomes, controlled ticks, and completed
frame references. It does not retain the selected immutable state needed to compare a later run.
Its scenario and checkpoint IDs and `replayGuaranteed` flag are application testimony, not proof
that recording began from that origin. Re-querying the referenced frames would also make replay
depend on shorter-lived frame retention.

The existing simulation determinism path already owns the correct exact-tick execution and stable
comparison semantics, but it compares two newly executed scenario runs. It cannot use a retained
recording as the reference run and cannot restore a checkpoint origin.

## Decision

Add a `ReplayRegistry` to `runtime-core`. A replay-ready capture is an additive path that starts a
normal recording and retains a bounded internal replay sidecar. The sidecar owns the explicit
origin, comparison contract, configured fixed step, normalized baseline evidence, exact tick
evidence, and semantic input script required to execute that recording later. It is retained and
evicted with the recording but is not added to the frozen schema-1 recording manifest.

Replay-ready capture supports exactly one origin:

- a registered deterministic scenario reset, receiving the recorded random-seed and configuration
  testimony through `ScenarioResetContext`; or
- a retained opaque application checkpoint, restored through its registered provider.

For a scenario origin, replay reapplies the recorded seed and configuration to the reset callback.
For a checkpoint origin, those fields remain preserved testimony about the state already embodied
by the opaque checkpoint; the runtime does not invent a separate configuration mutator around
checkpoint restore. Baseline comparison detects any resulting observable mismatch.

The replay API never serializes checkpoint handles or application objects. Scenario callbacks,
checkpoint callbacks, input handlers, pause/resume callbacks, and exact-tick callbacks remain
application-owned.

Ordinary recordings remain valid and unchanged. Attempting to replay one returns an
`INCONCLUSIVE` result explaining that replay evidence was not captured. The existing
`replayGuaranteed` flag remains application testimony and does not by itself create executable
evidence. Schema-1 `reproductionEvidenceComplete` continues to describe completeness of that
manifest's recorded items; replay-sidecar completeness is an additional, stricter condition.

## Component Boundaries

- `ReplayRegistry` owns replay capture/execution request retention and the immutable sidecars. It
  does not replace ordinary recording storage.
- `RecordingRegistry` remains authoritative for recording start/stop/get/eviction. Narrow
  package-private staged start, stop-notification, and eviction-notification seams let replay
  capture share that lifecycle without nested command dispatch or duplicated manifests.
- `ScenarioRegistry` and `CheckpointRegistry` expose package-private replay-origin operations that
  invoke only their already registered callbacks and produce normal epoch baselines. They add no
  new public arbitrary mutation surface.
- `InputRegistry`, `ActionRegistry`, and `SimulationTimelineRegistry` notify an active replay
  capture of the explicit facts they already own. The replay registry never discovers facts by
  traversal.
- A package-private observable-evidence component owns normalized selection, byte accounting, and
  first-difference comparison for both determinism and replay.
- `runtime-protocol` and `runtime-mcp` only map closed requests and immutable results onto the core
  registry; neither transport executes simulation logic.

## Public Core Model

`AgentRuntime.replays()` exposes the new registry. The public request model is a
`ReplayCaptureSpec` containing:

- the existing `RecordingSpec`;
- a `DeterminismProfile` selecting entity, property, event, decision, and optional UI evidence;
- ordered `SimulationConfigurationRequirement` values checked at the recreated baseline;
- ordered `SimulationEvidenceRequirement` values checked after every replayed tick; and
- selected `EventType` values when event comparison is enabled.

The embedded `RecordingSpec` must name exactly one scenario or checkpoint. Replay-ready capture
requires `replayGuaranteed == true`; this remains an explicit application claim while runtime-owned
evidence independently determines whether replay is complete. Scenario and checkpoint identifiers,
optional random seed, scalar configuration, protocol version, and capability versions continue to
be preserved in ordinary recording metadata. The replay sidecar additionally freezes the exact
registered `fixedStepNanos` used by capture.

The registry provides two mutating operations:

```java
ReplayCaptureOperation start(
        ReplayCaptureSpec spec, String requestId, Duration timeout);

ReplayOperation execute(
        String recordingId, String requestId, Duration timeout);
```

Both use the existing application command dispatcher and at-most-once request-ID semantics.
`RecordingRegistry.stop(...)` remains the single stop operation for ordinary and replay-ready
recordings. Stopping freezes the replay sidecar before publishing the retained recording.

`ReplayCaptureOperation` reports command status plus the recreated origin epoch/frame when the
start command succeeds. `ReplayOperation` reports command status and an optional terminal
`ReplayResult`.

`ReplayResult` contains:

- `DeterminismStatus` (`EQUAL`, `DIVERGED`, or `INCONCLUSIVE`);
- a bounded deterministic message;
- the recording ID and optional comparison profile, absent only when an ordinary or evicted
  recording has no retained replay sidecar from which to report registered selectors;
- optional first `ReplayDivergence` evidence;
- `ReplayBounds` with requested/completed ticks, inputs, entities, facts, encoded evidence bytes,
  and execution deadline; and
- optional structured `ApplicationFailureEvidence`.

`ReplayDivergence` identifies `BASELINE` or `SIMULATION_TICK`. A baseline divergence has no
simulation tick ID and is reported before tick 1. A tick divergence contains the positive
epoch-relative tick, reference and replay simulation tick IDs, reference and replay epoch/frame
IDs, and the first stable `DeterminismDifference`. It reports correlation, not inferred causality.

All public records validate, defensively copy, and deterministically order their inputs. Public
strings and nested values use existing identifier, diagnostic, and `RuntimeValue` bounds.

## Replay-Ready Capture Lifecycle

The caller first pauses the application through the registered simulation controller. Replay
capture does not silently change and retain pause state on behalf of a multi-command recording
workflow.

`ReplayRegistry.start(...)` validates every non-mutating prerequisite before dispatch:

1. The runtime is running and has no active recording or replay execution in progress.
2. Application command dispatch, an acknowledged simulation controller, and a configured fixed
   simulation step are available.
3. The registered input catalog, comparison selectors, requirements, and all replay limits are
   valid.
4. The origin is exactly one deterministic scenario or one currently retained checkpoint.
5. No ordinary input is queued or scheduled.

On the capture thread, the command confirms the application is still paused and then performs the
selected reset or restore. The successful callback starts its normal `SCENARIO_RESET` or
`CHECKPOINT_RESTORE` epoch and zero-delta baseline. The registry confirms that the simulation
timeline is at epoch tick zero, captures selected baseline evidence, and activates both the normal
recording and replay sidecar as one staged state transition.

All failure-prone validation and bounded storage reservation occurs before origin mutation where
possible. A reset or restore callback failure uses existing structured application diagnostics and
does not activate a recording. A successful origin mutation followed by incomplete baseline
evidence activates the recording with an explicit incomplete reason; it never claims that the
origin was not changed.

While capture is active:

- successfully executed, non-redacted registered inputs are copied into the replay script using
  their resulting simulation-timeline epoch tick and original acceptance order, never the
  session-wide control counter;
- every completed paused, acknowledged tick using the frozen fixed step captures normalized
  selected evidence from its resulting frame;
- multiple inputs on one tick retain their original stable order;
- unrelated render/runtime frames are not treated as simulation ticks;
- ticks must be contiguous from epoch tick 1 through the final recorded tick;
- a semantic action, failed or redacted input, epoch change, resumed/running tick, non-fixed,
  unacknowledged, skipped, or incomplete tick, diagnostics/truncation that could hide a
  difference, missing selected fact, or bound exhaustion marks reproduction evidence incomplete;
  and
- no OS-level input, reflection, arbitrary object serialization, action re-execution, or mutation
  inference is introduced.

Actions deliberately are not replayed in this slice because their existing recording evidence does
not define an exact simulation-tick execution point. A replay-ready recording that observes one is
therefore incomplete rather than silently omitting a possible mutation.

The simulation timeline notifies replay capture about every completed or failed tick, including
normal running ticks. This prevents a resumed interval from disappearing merely because ordinary
recording tick entries are emitted only by controlled advancement.

Stopping freezes an immutable sidecar before the normal recording becomes queryable. Evicting the
recording evicts its sidecar and retains only bounded eviction testimony. Closing the runtime stops
active recording capture through the existing recording close path, releases mutable replay
operation state, and permits no new replay execution after close.

## Replay Execution

`ReplayRegistry.execute(...)` accepts only a stopped retained recording. Unknown IDs are invalid
queries. Known evicted IDs and retained recordings without a complete sidecar return
`INCONCLUSIVE`; they are evidence conditions, not fabricated success.

Before dispatch, execution also rejects another active recording, an in-progress replay, or an
ordinary queued/scheduled input. These lifecycle conflicts are not converted into misleading
evidence results.

Execution runs as one application-dispatched operation:

1. Validate the recorded input script against the currently registered closed input schemas.
2. Require an empty ordinary input queue and enter the existing exclusive deterministic-input
   mode.
3. Pause through the same state-preserving determinism seam used by repeated simulation checks.
4. Reset the recorded scenario with the exact seed/configuration testimony, or restore the exact
   retained checkpoint.
5. Capture and compare the recreated baseline. Stop immediately with a baseline divergence if it
   differs.
6. For each contiguous recorded positive epoch tick, apply that tick's inputs in recorded order
   immediately before simulation, advance exactly the frozen fixed step, validate tick/
   configuration/completeness evidence, and compare the resulting selected evidence.
7. Stop at the first difference. Otherwise return `EQUAL` only after every recorded tick has
   completed with complete evidence.
8. Restore the caller's prior pause state and leave deterministic-input mode in a `finally` path.

The replay executor uses neither the render loop nor wall-clock sleeping. It creates no thread,
timer, scheduler, or transport. It never retries unknown partial application mutation. Callback,
capture, timeout, eviction, evidence, and pause-state restoration failures produce
`INCONCLUSIVE`; cleanup failure is never hidden behind an earlier comparison result.

## Shared Evidence Comparison

Extract the private normalized frame-capture and first-difference logic from
`DeterminismRegistry` into one package-private JDK-only component. It accepts an explicit profile,
event selectors, UI lookup, and bounded counters, and produces immutable comparable evidence or a
typed incomplete reason.

Both simulation determinism and replay use this component. Existing determinism Java and wire
results remain unchanged. The extraction must preserve:

- stable entity/property/event/decision/UI ordering;
- selection and completeness checks;
- canonical encoded-size accounting;
- first entity lifecycle, property, event, decision, or UI difference; and
- the rule that diagnostics, truncation, eviction, or missing selected evidence prevent equality.

This is a targeted extraction, not a new public generic serializer or comparison framework.

## Bounds and Retention

Add `ReplayLimits` to `AgentRuntime.Builder`. It independently bounds:

- retained capture/execution operation results;
- replay inputs per recording;
- exact ticks per recording and execution;
- selected entities and facts per frame;
- total canonical replay-evidence bytes;
- execution duration; and
- result and diagnostic strings where an existing stricter bound does not already apply.

Replay artifact retention is additionally capped by `RecordingLimits.retainedRecordings`.
Recording duration, item, tick-span, and manifest byte limits continue to apply to the ordinary
manifest. Effective capability metadata exposes both sets of limits. Saturating observed counters
and explicit retained/limit values prevent overflow and make truncation visible.

The sidecar's canonical byte accounting covers its origin/profile metadata, input script,
baseline evidence, and tick evidence. It cannot hide memory behind a manifest byte count that
excludes replay data.

## Protocol and MCP

Protocol 2.5 adds closed command/result unions for:

- `replayRecordingStart`; and
- `replay`.

Earlier protocol versions and their serialized shapes remain frozen. Protocol 2.5 continues to use
the existing recording stop/get commands; schema-1 recording chunks do not gain sidecar fields.

The capability descriptor is `replay-execution` and advertises scenario-reset, checkpoint-restore,
exact-fixed-tick, first-divergence, and inconclusive-safe modes. It is available only when command
dispatch, an acknowledged simulation controller, a fixed simulation timeline, recording, and at
least one explicit origin provider are available. Tool discovery remains a server-start union; a
selected session without the required capabilities returns `CAPABILITY_UNAVAILABLE`.

MCP adds:

- `runtime_replay_recording_start`, with a closed origin kind/ID, optional seed, scalar
  configuration, profile, requirements, event selectors, request ID, and timeout; and
- `runtime_replay`, with recording ID, request ID, and timeout.

Unknown fields, invalid origin combinations, unsupported protocol versions, open polymorphic
values, and over-limit requests are rejected before command execution. MCP remains local stdio in
the application-owned launcher.

## Error Semantics

Invalid request shape, unknown never-seen recording IDs, conflicting request-ID reuse, invalid
lifecycle/thread use, or unavailable mandatory registrations are typed request failures.

The following are terminal `INCONCLUSIVE` replay results rather than guessed failures or equality:

- ordinary recording or missing replay sidecar;
- incomplete/truncated replay capture;
- evicted source recording, sidecar, checkpoint, tick, UI correlation, or operation evidence;
- redacted, failed, missing, schema-drifted, or over-limit input evidence;
- configuration or fixed-step mismatch;
- origin reset/restore, input, tick, capture, pause/resume, or application callback failure;
- timeout or replay execution bound exhaustion; and
- any diagnostic, truncation, unknown mutation outcome, or missing selected fact that could hide a
  difference.

`DIVERGED` requires two complete comparable evidence values and identifies the earliest baseline or
tick difference. `EQUAL` means only equality of the selected registered observables over the
recorded ticks. Neither status claims whole-program, cross-machine, or cross-platform determinism.

## Compatibility and Security

- `runtime-core` remains JDK-only.
- Module direction remains `runtime-mcp -> runtime-protocol -> runtime-core <- runtime-libgdx`.
- Existing recording, determinism, protocol 1.0-1.13, and protocol 2.0-2.4 contracts remain intact.
- Ordinary recordings do not become replayable by inference or by setting testimony alone.
- The runtime stores no arbitrary application state, checkpoint payload, stack trace, class name,
  script, expression, filesystem path, or network target.
- Every mutation stays on the capture thread through application-owned dispatch.
- Correlated input and difference evidence is not described as causal evidence.

## Documentation and ADR

Implementation adds an ADR describing replay-ready recording sidecars, explicit origins, exact-tick
execution, and the separation between application testimony and runtime evidence. It updates:

- `docs/design-contract.md`;
- `docs/guides/agent-tools.md`;
- `docs/guides/agent-cookbook.md` with a compiled Java recipe and tested MCP transcript;
- capability/version documentation; and
- release notes or compatibility documentation required by the repository at implementation time.

## Verification

Core tests must prove:

- scenario-origin and checkpoint-origin equal replay;
- baseline and positive-tick first divergence;
- stable same-tick input ordering;
- exact fixed-step and seed/configuration preservation;
- ordinary, redacted, failed, action-bearing, truncated, evicted, schema-drifted, and missing-evidence
  recordings return `INCONCLUSIVE`;
- origin, input, tick, pause/resume, and capture failures retain bounded structured evidence;
- request deduplication, conflicting reuse, timeout validation before retention, wrong-thread use,
  close behavior, hard limits, byte accounting, and eviction; and
- extracted comparison behavior remains identical for existing simulation determinism.

Protocol tests must prove exact 2.5 success shapes, closed JSON rejection, unsupported-version
behavior, prior-version shape stability, capability availability, and bounded errors. MCP tests must
prove catalog inclusion, closed schemas, handler mapping, polling, equal/diverged/inconclusive
results, and unavailable-session behavior.

The real LWJGL3/Box2D conformance fixture must run one public replay workflow under Xvfb, including
registered inputs and many exact ticks, and prove both successful replay and an intentionally
introduced divergence at a known tick while proving all earlier ticks matched, without relying on
screenshots or render cadence.

During implementation, run focused core/protocol/MCP gates while iterating, then
`.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh full` on Linux under `xvfb-run` before
completion.

## Explicit Non-Goals

This slice does not add stateful input ranges, OS input injection, semantic-action replay,
performance profiling, inferred failure causality, gameplay/ECS adapters, rendering ownership,
asset/prefab systems, networking, arbitrary scripting, reflection, or exported serialized game
state. Those remain separate roadmap work.
