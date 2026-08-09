# Tick-Aware Determinism for Explicit Physics Evidence

## Context

The existing `DeterminismRegistry` repeats deterministic scenario resets, advances a registered
controller, captures selected immutable frame evidence, and compares exact canonical values. Its
public result identifies a zero-based captured-frame position, however, and its legacy controller
path cannot prove the delta that application code actually executed. It also has no closed way to
repeat registered inputs or require fixed-step, solver, world, and contact-completeness testimony.

Issue #70 adds a simulation-specific surface to that registry. It does not add a Box2D execution
engine, read native objects, or change the frozen `DeterminismSpec`, `DeterminismResult`, protocol
1.13/2.0 schemas, or `runtime_determinism_check` tool.

## Decision

Add `SimulationDeterminismSpec` and a tick-aware result beside the legacy determinism records.
`DeterminismRegistry.checkSimulation(...)` remains the sole admission, execution, evidence-budget,
retention, and comparison engine. The new execution path uses the same registered scenario,
application command dispatch, controller, simulation timeline, immutable frames, canonical
comparison, limits, and sanitized application-failure evidence.

The specification contains:

- the existing `DeterminismSpec` for scenario, seed, closed application configuration, repeat and
  tick counts, requested delta, and snapshot profile;
- an ordered bounded `SimulationDeterminismInput` script keyed by positive epoch tick;
- exact `SimulationConfigurationRequirement` values checked against the current baseline before
  admission and every reset baseline before ticking;
- boolean `SimulationEvidenceRequirement` values required on every compared tick;
- an optional bounded allow-list of event types when events are selected.

Rejected alternatives:

- A determinism registry in `runtime-box2d` would duplicate execution and bounded evidence logic.
- Reinterpreting the legacy frame index would silently change a frozen result contract.
- Reusing ordinary queued `InputInjection` instances across resets would retain stale execution
  epochs and request correlations.
- Reading Box2D world settings at request time would cross the immutable-evidence boundary.
- Approximate comparison would weaken deterministic equality; tolerances remain assertions only.

## Input script

`SimulationDeterminismInput(epochTick, inputId, parameters)` names an already registered runtime
input. The list contains at most 256 entries, is stably ordered by epoch tick, and preserves caller
order for multiple inputs on the same tick. Every tick must be within the requested run.

Before command dispatch, the input registry validates every ID and closed parameter object using
the registered descriptor and configured `RuntimeValue` limits. Admission rejects outstanding
ordinary queued or scheduled inputs. While the comparison is executing, ordinary injection is
rejected so it cannot enter only one repeat. For each repeat, the same registered handlers execute
on the application capture thread after ordinary controlled-input dispatch and immediately before
the acknowledged simulation callback for the selected epoch tick. The script is request evidence;
it does not fabricate ordinary injection request IDs or recording entries.

Handler mutation remains application-owned. A handler failure is sanitized through the existing
application-failure evidence path and makes the result `INCONCLUSIVE`.

## Fixed-step and configuration admission

Simulation comparison requires all of the following before its command is queued:

- a registered simulation timeline with a fixed step exactly equal to `deltaNanos`;
- a controller registered with `acknowledgedTick(...)`, not the legacy `tick(...)` callback;
- paused-control support and command dispatch;
- every exact configuration requirement present and equal in the latest completed frame;
- no relevant diagnostics or truncations in that baseline;
- all dimensions, selectors, values, inputs, encoded configuration, operations, and duration within
  the existing determinism and runtime limits.

Every explicitly selected entity must be present and untruncated, and every selected property name
must resolve on at least one selected entity. A misspelled selector therefore fails admission or
becomes inconclusive after reset instead of comparing two empty selections as equal.

A request conflict is a typed `INVALID_QUERY` or `LIMIT_EXCEEDED` rejection before application
mutation. After each scenario reset, the registry checks the same requirements again against the
new epoch baseline. This is where application-owned Box2D recreation and adapter rebind are proven.
A missing registration, stale retained testimony, reset/rebind failure, or mismatch after reset is
`INCONCLUSIVE`; no controlled tick runs for that repeat.

## Tick execution and completeness

The reset baseline is configuration evidence, not simulation tick zero and not part of equality.
Each requested tick returns both its `SimulationTick` and correlated `FrameSnapshot`. A tick is
comparable only when:

- its epoch tick is the requested positive position;
- its source is paused controlled execution;
- its outcome is `COMPLETED` with `KNOWN_COMPLETED` mutation;
- configured, supplied, and application-reported deltas all equal the requested fixed step;
- its resulting frame exists, is retained, and belongs to the same execution epoch;
- selected frame/entity/event/decision evidence has no relevant diagnostic or truncation;
- every configured boolean evidence requirement is present and `true` in that frame.

Missing, failed, unacknowledged, mismatched, unknown, evicted, truncated, or incomplete evidence
returns `INCONCLUSIVE`, never `EQUAL`. Controlled execution does not use the running accumulator;
therefore catch-up drops cannot be hidden inside a successful result.

## Exact comparison and divergence

The registry reuses exact canonical entity/property/event/decision comparison. Frame IDs,
execution epochs, session tick IDs, native pointers, wrapper identity, and wall-clock values are
correlation evidence only and are excluded from equality.

`SimulationDeterminismDivergence` contains the first differing positive `epochTick`, both session
`SimulationTickId` values, both execution epochs, both runtime frame IDs, and the existing closed
`DeterminismDifference`. A property difference therefore retains a key such as
`box2d.body.player:position` plus bounded canonical left/right values. Runs are compared in repeat
order and ticks in ascending epoch order.

When `includeEvents` is false, event-type selectors must be empty. When it is true, an empty event
allow-list means all captured events as in the legacy profile; a non-empty allow-list compares only
those exact types in retained event order. This makes contact-event selection explicit without
changing `SnapshotComparisonScope`.

## Box2D integration

`Box2dDeterminism` is a data-only builder in `runtime-box2d`. It accepts stable application IDs and
explicit `WorldSettings`: fixed step, gravity, solver iterations, sleeping, warm-starting, and
continuous-physics flags. It never accepts a `World`, `Body`, `Fixture`, `Joint`, or contact.

The builder:

- creates exact requirements for `box2d.world.<id>` testimony;
- selects stable `box2d.body.*`, `box2d.fixture.*`, and `box2d.joint.*` entities and properties;
- optionally selects `box2d.contacts.<worldId>.activeContacts` and requires its `complete` property;
- optionally selects the four `box2d.contact.*` event types and requires contact completeness;
- keeps world settings as exact baseline/reset requirements without broadening the selected scope;
- compiles the result to the generic JDK-only `SimulationDeterminismSpec`.

Configuration requirements use the same canonical values published by the adapter, including
float-derived gravity components. The builder validates finite values and stable IDs before
constructing evidence selectors.

## Result and retention

`SimulationDeterminismResult` uses `EQUAL`, `DIVERGED`, or `INCONCLUSIVE`, the simulation profile,
an optional tick-aware divergence, existing `DeterminismBounds`, and optional sanitized
`ApplicationFailureEvidence`. `DIVERGED` requires a divergence; the other statuses prohibit one.
`SimulationDeterminismOperation` mirrors the existing at-most-once command lookup.

Legacy and simulation operations share the configured `retainedOperations` capacity and one
oldest-first eviction order. Polling an evicted simulation request returns an `INCONCLUSIVE`
result with explicit eviction evidence. Request IDs cannot cross-bind between legacy and
simulation specifications.

## Protocol and MCP

Protocol 2.4 adds a distinct `simulationDeterminismCheck` command and
`simulationDeterminism` result. It serializes the generic closed specification and tick-aware
evidence. Protocol 1.13 through 2.2 determinism shapes remain unchanged. Every JSON object rejects
unknown fields and versions before execution.

MCP adds `runtime_simulation_determinism_check` with bounded natural JSON and reserved closed
enum/vector tags for exact configuration testimony.
Capability discovery reports `simulation-determinism`, protocol 2.4, hard input/configuration/
requirement/event limits, and dependencies on scenario reset, controlled acknowledged ticks, the
simulation timeline, and completed immutable frames.

## Claim boundary

`EQUAL` means only that selected observable evidence was equal for the repeated runs under the
same application, JVM, libGDX/Box2D native build, platform, scenario, acknowledged configuration,
fixed step, solver settings, and controlled input script. It is not proof of whole-program,
cross-platform, rendering, unregistered-state, future replay, or causal determinism.
Runtime-owned correlation attributes (`executionEpochId`, `simulationTickId`, and
`runtimeFrameId`) are excluded from selected event comparison under the existing
`EXCLUDE_RUNTIME_IDENTIFIERS` rule; `epochTick` and semantic event attributes remain comparable.

## Verification and documentation

Focused tests cover equal and divergent runs, actual tick correlation, input order, pre-admission
step/configuration rejection, reset and callback failures, incomplete contacts, capture
truncation, result eviction, protocol closure, and MCP closure. Box2D tests use immutable
Box2D-shaped facts; native repeated-scenario qualification remains in the later fixture issue.

The ADR, design contract, getting-started material, agent tool guide, and cookbook document exact
schemas and the claim boundary. Every later API change must update the cookbook in the same PR.
No publication is authorized.
