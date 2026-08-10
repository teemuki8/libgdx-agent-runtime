# Deterministic Input Timelines Design

**Status:** Approved
**Date:** 2026-08-10
**Scope:** Bounded exact-tick execution of explicit registered input transitions

## Goal

Let an agent submit one bounded sequence of explicit application-registered input transitions and
execute it as one deterministic operation. The runtime validates the whole sequence before
mutation, applies each transition immediately before its requested local tick, advances the
application through exact fixed simulation ticks, and returns immutable evidence for what did and
did not execute.

The application continues to own input meaning and ongoing control state. For example, it decides
what `move-left(active=true)`, `move-left(active=false)`, `fire(pressed=true)`, or
`steer(amount=0.5)` changes in the game. The runtime owns only bounded validation, exact timing,
stable ordering, application-thread dispatch, and evidence.

This is the second independently deliverable part of the deterministic execution, input timeline,
performance evidence, failure localization, and integration roadmap. It builds on replay execution
but does not implement the later roadmap tracks.

## Example Contract

A caller can request a 75-tick timeline with transitions such as:

1. tick 20: `move-left(active=true)`;
2. tick 20: `fire(pressed=true)`;
3. tick 47: `steer(amount=0.5)`; and
4. tick 75: `move-left(active=false)`.

The two tick-20 transitions execute in request-list order before simulation tick 20. Ticks without
transitions still advance by the registered fixed step. The operation completes only after tick 75
has completed, so the last transition affects tick 75. A held input is never inferred: callers must
send both start and stop transitions when that is the application's registered contract.

## Existing Gap

`InputRegistry.inject(...)` already validates closed scalar parameters, schedules one registered
input for a controlled tick, preserves same-tick acceptance order, and returns correlated
tick/frame evidence. `SimulationControlRegistry.advanceFixed(...)` already advances an exact
bounded number of application-owned ticks. A caller can combine those APIs manually, but that is a
multi-command workflow:

- an invalid later transition can be discovered after earlier transitions were scheduled;
- scheduling and advancement can be interleaved with unrelated commands;
- the caller must correlate many child requests, global controlled ticks, and frames;
- timeout and partial-failure evidence is spread across separate operations; and
- there is no single bounded result proving how much of the sequence executed.

`SimulationDeterminismInput` and replay scripts are internal comparison/reproduction inputs. They
do not provide a general public operation for executing one caller-supplied transition timeline.

## Decision

Add one exact-tick timeline operation to `InputRegistry`. The request contains a positive total
tick count and a non-empty list of explicit transitions. Each transition has a caller-selected
stable ID, a positive timeline-local tick, a registered input ID, and a closed immutable parameter
object. Transition ticks must be non-decreasing, must not exceed the total tick count, and same-tick
transitions retain list order.

The registry validates and reserves the complete request before application mutation. It then
submits one parent command through the existing application-owned dispatcher. On the capture
thread, that command requires the application to remain paused, freezes the configured fixed step,
maps timeline-local ticks onto the current session-wide controlled-tick counter, and advances
through the requested final tick.

The operation creates no thread, scheduler, timer, render-loop dependency, or wall-clock sleep. It
does not call the public input or control APIs recursively. Narrow package-private seams reuse the
existing input scheduling/evidence path and exact controlled-tick path inside the single dispatched
operation.

## Semantic Boundary

The runtime never owns a keyboard, mouse, controller, key-down set, button latch, analog curve, or
gameplay command state. It does not install global or OS input hooks and does not translate platform
events. Applications register semantic inputs and handlers exactly as they do today.

Timeline transitions are discrete facts:

- boolean start/stop behavior uses explicit `true` and `false` values;
- analog behavior uses explicit decimal values at explicit ticks;
- there is no interpolation, smoothing, implicit release, or inferred duration;
- entity IDs and enum values retain their existing closed validation; and
- application handlers decide whether a transition sets state, emits an edge, or performs another
  bounded semantic mutation.

This keeps the library architecture-neutral across input processors, ECS designs, physics engines,
scene graphs, and custom application loops.

## Public Core Model

`InputRegistry` gains:

```java
InputTimelineOperation executeTimeline(
        InputTimelineSpec spec, String requestId, Duration timeout);
```

`InputTimelineSpec` contains:

- `int totalTicks`; and
- an ordered `List<InputTimelineTransition>`.

`InputTimelineTransition` contains:

- `String transitionId`, unique within the timeline and reserved in the existing input-request ID
  namespace for the lifetime of retained evidence;
- `int timelineTick`, in the inclusive range `1..totalTicks`;
- `String inputId`; and
- `RuntimeValue.ObjectValue parameters`.

The parent `requestId` must differ from every transition ID. Public records validate, defensively
copy, and preserve deterministic list order. Parameters are validated through the selected
registered `InputDescriptor`; unknown, missing, extra, nested, or wrong-type values are rejected.
Only the existing scalar input types are accepted.

`InputTimelineOperation` contains the parent `CommandLookup` and an optional terminal
`InputTimelineResult`. A queued or executing operation has no speculative result. A retained
terminal operation always contains a result, including timeout or application failure.

`InputTimelineResult` contains:

- `InputTimelineStopReason`;
- a bounded deterministic diagnostic;
- the starting execution epoch, starting controlled tick, and frozen fixed-step nanoseconds;
- optional first and final resulting frame IDs;
- ordered `InputTimelineTransitionEvidence` for every requested transition;
- `InputTimelineBounds`; and
- optional bounded `ApplicationFailureEvidence` without a serialized stack trace.

`InputTimelineStopReason` is closed and distinguishes at least:

- `COMPLETED`;
- `TIMED_OUT`;
- `LIFECYCLE_CHANGED`;
- `INPUT_FAILED`;
- `TICK_FAILED`;
- `EVIDENCE_LIMIT`; and
- `CLEANUP_FAILED`.

`InputTimelineTransitionEvidence` identifies the transition ID, local tick, input ID, and one of
`EXECUTED`, `FAILED`, or `NOT_EXECUTED`. An attempted transition includes its normal
`InputInjection` evidence. An unattempted transition contains no fabricated actual tick or frame
and instead carries a bounded reason. Parameter visibility follows the registered
`InputRedactionPolicy`; the parent result never echoes the raw request parameters separately.

`InputTimelineBounds` reports requested/completed ticks, requested/executed/failed/not-executed
transitions, encoded evidence bytes, the relevant retained/limit values, and the effective
execution deadline. Counters saturate rather than overflow.

## Limits

Add `InputTimelineLimits` to `AgentRuntime.Builder`, independently bounding:

- retained parent operation results;
- transitions per timeline;
- exact ticks per timeline;
- canonical encoded result-evidence bytes; and
- execution duration.

Conservative development defaults are 32 retained operations, 4,096 transitions, 600 ticks,
1 MiB of result evidence, and 30 seconds. Supported public maxima follow the established replay
and determinism ceilings.

The effective request is also bounded by `InputLimits.queuedInputs`,
`InputLimits.retainedInjections`, `ControlLimits.ticksPerOperation`, registered parameter/string
bounds, runtime-value bounds, and `CommandDispatchLimits.maximumTimeoutNanos`. Capability metadata
reports the configured and effective ceilings so the default effective transition count is not
misrepresented when ordinary input retention is lower than 4,096.

Canonical size accounting covers parent metadata, every transition outcome, nested normal input
evidence, diagnostics, and structured failures. The registry reserves enough bounded retention for
the entire request before dispatch. It never begins mutation and later discovers that the declared
result cannot fit.

## Validation and Reservation

Under the input submission lock, `executeTimeline(...)` performs all non-mutating checks before
submitting the parent command:

1. The runtime accepts submissions and has application command dispatch.
2. The timeout is positive and within both dispatch and timeline limits.
3. The controller is registered, acknowledged, and known paused; a fixed step is configured.
4. No determinism, replay execution, or other input timeline owns exclusive input execution.
5. The ordinary queued/scheduled/executed input staging area is empty.
6. Total ticks, transition count, canonical request/result reservation, and all effective bounds
   pass.
7. Transition ticks are ordered and in range; transition IDs are unique and distinct from the
   parent ID.
8. Every registered input still has a handler and every parameter object matches its descriptor.
9. The parent request ID is either new or bound to the identical timeline signature. Every child
   transition ID is new in the input evidence namespace and does not collide with retained command
   correlation evidence.

After these checks, parent and child identities plus their bounded result capacity are reserved as
one state change. If parent submission fails before execution, the timeline becomes terminal
without applying a handler; all transitions are `NOT_EXECUTED`, and reservations remain bounded
pollable evidence until normal eviction.

Calling the operation again with the same parent request ID and identical specification polls the
same at-most-once operation. Reusing that ID with changed ticks, order, transition identity, input,
parameters, or timeout is rejected. A transition ID reserved by a timeline cannot be reused through
ordinary `inject(...)` while its evidence is retained.

## Execution Lifecycle

When the parent dispatcher callback begins, the registry rechecks all lifecycle facts that may
have changed while queued. It freezes:

- the current execution epoch;
- the current session-wide controlled tick;
- the configured fixed-step nanoseconds;
- the expected pause/controller state; and
- the absolute monotonic deadline, capped by both caller and timeline execution limits.

It then enters the existing exclusive input-execution mode and stages the already validated
transitions against `startingControlledTick + timelineTick`. Local tick 1 therefore means the next
controlled tick, regardless of the current epoch-relative simulation tick. Resulting
`SimulationTick` evidence continues to carry the authoritative epoch-relative tick.

For each local tick from 1 through `totalTicks`, execution:

1. checks the deadline and frozen lifecycle before attempting that tick;
2. applies all due transitions in list order through the normal registered input handlers;
3. checks the deadline, execution epoch, pause state, controller acknowledgement, and fixed step
   again before simulation;
4. advances exactly one acknowledged application-owned tick at the frozen fixed step;
5. captures the resulting frame and normal simulation-timeline evidence;
6. updates normal input evidence with its actual controlled tick and resulting frame;
7. records the completed controlled tick for an active recording; and
8. checks the deadline and lifecycle again before comparing the tick against the terminal bound or
   beginning another tick.

The post-callback deadline checks are required: a final handler, simulation callback, or capture
that consumes the budget must not report `COMPLETED` merely because no later loop iteration exists.
`COMPLETED` is possible only when all requested transitions and ticks finish with complete evidence
inside the deadline.

The exact-tick implementation is extracted as the smallest package-private helper from
`SimulationControlRegistry`; the public `advanceFixed(...)` contract and result remain unchanged.
The input registry similarly reuses its current scheduled evidence and handler path without nested
dispatcher submission.

## Failure and Partial Mutation

The runtime stops at the first failure. It does not roll back, retry, compensate, or infer what an
application callback mutated.

- If a transition handler fails, earlier transitions at that tick may already have mutated
  application state. The failed transition contains bounded structured failure evidence; later
  same-tick transitions and later ticks are `NOT_EXECUTED`.
- If the simulation callback or frame capture fails, transitions already applied for that tick
  remain attempted. The tick is not counted as completed unless authoritative completed-tick/frame
  evidence exists.
- If the epoch, pause state, acknowledgement, or configured fixed step changes, execution stops
  with `LIFECYCLE_CHANGED` before the next unsafe stage.
- If the deadline expires after a callback, the completed mutation and any authoritative frame are
  retained, but the parent result is `TIMED_OUT`, never `COMPLETED`.
- If cleanup of exclusive input mode fails, `CLEANUP_FAILED` supersedes a would-be successful
  result. An earlier structured application failure is retained when bounds permit.

The terminal result explicitly lists unexecuted transitions. Retrying the same request ID only
polls. Retrying under a new ID is a new mutation request and is never automatic; after an unknown or
partial application mutation, callers should restore a known scenario/checkpoint before deciding
to do so.

Pre-dispatch validation failures throw the existing typed invalid-request, invalid-lifecycle, or
limit error and apply no transition. Failures after the parent begins are represented in the
terminal bounded result whenever runtime invariants permit it. Raw exception messages and stack
traces are never serialized.

## Input, Recording, and Replay Evidence

Attempted timeline transitions use the existing `InputRegistry` evidence state. Each transition ID
has a logical bounded child status tied to the parent operation, so its `InputInjection` keeps a
self-consistent request ID, state, actual controlled tick, execution epoch, frame correlation,
redaction, diagnostic, and application failure. Child statuses are retained by the input registry;
they are not separately submitted application commands, and the parent timeline result is their
canonical aggregate polling surface.

The registry calls the existing recording and replay input hooks when a transition is staged and
when its terminal evidence changes. Consequently:

- ordinary recording schema 1 continues to store normal `RecordingInputEntry` values and needs no
  timeline-specific entry type;
- replay-ready capture sees successful, non-redacted inputs at the resulting authoritative
  epoch-relative tick in stable order;
- idle ticks in the timeline are retained as normal controlled-tick entries;
- a failed/redacted transition, failed/incomplete tick, epoch drift, timeout, or evidence bound
  makes replay evidence incomplete through the existing fail-closed rules; and
- replay execution later applies the recorded semantic inputs directly; it does not recursively
  invoke the original timeline parent command.

If an ordinary recording auto-stops during timeline execution, recording/replay freeze uses the
effective stop reason and the latest transition/tick evidence. It cannot claim complete
reproduction evidence for a suffix that was not retained.

## Protocol and MCP

Protocol 2.6 adds one closed `InputTimeline` command and one closed timeline result. Older protocol
versions reject the command with the exact required-version message. The request and response map
the core records without transport-owned execution logic.

MCP adds one `runtime_input_timeline` tool. Its top-level, transition, and parameter schemas are
closed. Like the existing simulation-determinism tool, its transition item uses generated
registered-input variants so `inputId` selects the exact descriptor parameter schema. This both
accepts normal boolean, integer, decimal, string, enum, and entity-ID parameters and rejects unknown
fields or nested values before dispatch. When no inputs are registered, the tool remains
discoverable but no executable transition variant is accepted.

Capability discovery exposes protocol 2.6, the `input-timelines` feature, the
`exact-fixed-tick` mode, configured/effective timeline limits, redaction behavior, same-tick list
ordering, and the requirements for paused acknowledged control plus application dispatch. MCP
remains local same-JVM stdio; no listener or cross-JVM live-inspection promise is introduced.

## Real Fixture and Cookbook

The real LWJGL3/Box2D fixture registers application-owned semantic controls that demonstrate:

- explicit boolean start and stop transitions;
- an explicit decimal analog transition;
- two simultaneous transitions whose observable effect proves request-list order;
- idle fixed ticks between transitions; and
- frame/tick evidence that can be captured and replayed.

The fixture must use the actual application callbacks and native Box2D state under Xvfb. A
source-text assertion or mock-only fixture is insufficient.

`docs/guides/agent-cookbook.md` gains a compiled Java recipe and tested MCP transcript showing
capability discovery, timeline execution, terminal bounds, per-transition evidence, recording, and
replay. Release/compatibility text labels protocol 2.6 as unreleased until a release actually ships.

## Test Strategy

Implementation begins with focused failing tests and covers:

- public-record null, order, tick, uniqueness, defensive-copy, and hard-bound validation;
- full preflight before mutation, including invalid late transitions and retention reservation;
- explicit boolean start/stop and decimal analog values;
- same-tick list order and idle-tick advancement;
- mapping from local ticks to session-wide controlled ticks and authoritative epoch-relative ticks;
- parent and transition at-most-once identity, polling, eviction, and changed-request rejection;
- pause, acknowledgement, fixed-step, epoch, ordinary-queue, replay, determinism, and concurrent
  timeline lifecycle conflicts;
- timeout before execution and after each handler, tick callback, capture, and final tick;
- handler, simulation, capture, lifecycle-drift, evidence-limit, and cleanup failures with explicit
  partial/non-executed evidence;
- parameter redaction and bounded structured application failures;
- recording schema-1 compatibility and replay-ready success/incomplete behavior;
- protocol 2.6 round trips, exact older-version rejection, and unknown-field rejection;
- live MCP schema validation and execution for boolean, integer, decimal, and string values, plus
  nested/unknown rejection; and
- the real LWJGL3/Box2D workflow under isolated Xvfb.

Focused core, protocol, MCP, fixture, cookbook, Javadoc, and full repository gates follow the
repository skill. The final Linux full gate uses `xvfb-run`, and `runtime-core` remains JDK-only.

## Architectural Record

Before production code, add an ADR recording the lasting choice: one application-dispatched,
exact-fixed-tick aggregate operation; application-owned persistent input state; logical child input
evidence without child command dispatch; hard limits; fail-stop partial mutation; and normal
recording/replay integration.

## Alternatives Rejected

### Schedule-only batch

Submitting a validated batch that merely schedules transitions for later manual
`advanceFixed(...)` calls would reduce request count but would not provide one bounded outcome. It
would retain lifecycle races, interleaving, and fragmented timeout/partial-failure evidence.

### Runtime-owned persistent controls

Having the runtime remember held keys, buttons, or analog state would make timelines superficially
shorter, but it would move application gameplay state into a third-party inspection library and
create implicit release/interpolation semantics. Explicit transitions keep ownership and evidence
clear.

### One dispatched command per transition

Reusing only the public `inject(...)` API would enqueue many application commands and could not
atomically validate, reserve, and execute the timeline. Logical child evidence preserves the
normal input shape without giving every transition a separate dispatcher lifecycle.

## Consequences

Agents gain one deterministic call for continuous multi-tick semantic input sequences with clear
partial-execution evidence. Applications keep full control of input semantics, simulation,
threading, rendering, assets, native state, and disposal. The implementation adds bounded aggregate
operation/evidence state and narrow internal reuse seams, but it does not add a loop owner, global
input system, rollback engine, interpolation layer, arbitrary serializer, reflection, filesystem,
networking, or background work.
