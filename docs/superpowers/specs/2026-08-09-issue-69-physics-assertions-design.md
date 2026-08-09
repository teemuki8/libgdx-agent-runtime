# Physics-Oriented Assertions over Simulation-Tick Evidence

## Context

The existing `RuntimeAssertion` API and `runtime_assert` command evaluate a frozen, frame-scoped
closed union introduced in protocol 1.7. Physics checks need an execution epoch plus an inclusive
simulation-tick range, exact tick-to-frame correlation, approximate vectors and angles, structured
event matching, and whole-range predicates. Reinterpreting a runtime-frame range as a tick range
would hide missing, failed, or evicted simulation attempts. Extending the old tagged union would
also change an earlier exact protocol schema.

Issue #69 therefore adds a separate generic simulation assertion surface. Box2D contributes only
factories that construct this JDK-only immutable model from application-supplied stable IDs. The
evaluator reads completed runtime snapshots and timeline evidence; it never reads a Box2D object.

## Decision

Add `SimulationAssertion`, `SimulationAssertionSpec`, `SimulationAssertionScope`,
`SimulationAssertionEvidence`, and `SimulationAssertionResult` to `runtime-core`. Add
`AssertionEvaluator.evaluateSimulation(...)` beside the existing frame evaluator. Protocol 2.3
adds `RuntimeCommand.SimulationAssert`; MCP adds `runtime_simulation_assert`. The existing
`RuntimeAssertion`, `RuntimeCommand.Assert`, protocol 1.7 JSON, MCP `runtime_assert`, and wait
command remain byte-for-byte schema compatible.

Rejected alternatives:

- Extending `RuntimeAssertion` would expose new tags through the frozen frame-scoped command and
  make version gating depend on assertion internals.
- A Box2D-specific evaluator would duplicate tick completeness logic and put reusable vector/event
  behavior in the adapter.
- Executable predicates, property paths, expressions, scripts, reflection, and native callbacks
  remain prohibited.

## Simulation scope and loading

`SimulationAssertionScope` contains one `ExecutionEpochId`, inclusive positive `fromEpochTick` and
`toEpochTick`, and a positive `evidenceLimit`. One assertion spans at most 1,000 ticks and returns
at most 100 evidence items. The evaluator pages through `SimulationTimelineRegistry.ticks(...)`
using the configured page limit and requires the exact requested epoch-relative tick sequence.

For each tick, the evaluator retains only the immutable `SimulationTick`, its resulting
`FrameSnapshot`, and a completeness flag. A tick is complete only when all of these hold:

- timeline range evidence is complete and the tick is present at its requested epoch position;
- `SimulationTick.outcome()` is `COMPLETED` and mutation outcome is known completed;
- the tick has a resulting runtime frame;
- that frame is retained and belongs to the requested execution epoch;
- the frame, entities, events, and decisions have no diagnostics, truncations, or incomplete
  decisions relevant under the existing core rules;
- every `SimulationEvidenceRequirement` relevant to the assertion evaluates to boolean `true`.

An evidence requirement contains an entity ID and one top-level boolean property name. It is not a
property path. Box2D contact factories require `box2d.contacts.<worldId>.complete`. Missing, false,
non-boolean, or truncated requirement evidence marks that tick incomplete; it never becomes an
ordinary assertion failure.

Timeline eviction, pagination that cannot be resolved within the hard bound, not-yet-executed
ticks, missing frame mappings, failed or unknown-outcome ticks, wrong epochs, and capture
truncation therefore cannot support a negative or whole-range PASS.

## Closed assertion model

`SimulationAssertion` is a sealed, data-only union:

1. `EntityExists(entityId)` checks the final tick.
2. `PropertyEquals(entityId, property, expected)` checks the final tick.
3. `ScalarApproximatelyEquals(entityId, property, expected, absoluteTolerance)` checks the final
   numeric scalar with an inclusive non-negative finite tolerance.
4. `VectorApproximatelyEquals(entityId, property, expected, absoluteTolerance, toleranceMode)`
   uses `COMPONENT` or `EUCLIDEAN`; component and squared-distance boundaries are inclusive.
5. `VectorInArea(entityId, property, area, relation, extent)` uses a closed axis-aligned area,
   `INSIDE` or its strict complement `OUTSIDE`, and `FINAL` or `EVERY_TICK` extent.
6. `VectorMagnitudeAtMost(entityId, property, maximum, extent)` compares squared magnitude to an
   inclusive non-negative maximum at the final tick or every tick.
7. `VectorDistanceApproximatelyEquals(leftEntityId, leftProperty, rightEntityId, rightProperty,
   expectedDistance, absoluteTolerance)` checks the final squared distance against the inclusive
   interval `max(0, expected-tolerance)` through `expected+tolerance`.
8. `WrappedAngleApproximatelyEquals(entityId, property, expected, period, absoluteTolerance)` uses
   the shortest deterministic decimal remainder distance. Period is positive; tolerance is
   non-negative and no greater than half the period.
9. `EventCount(selector, expectation, exactCount)` matches event type plus optional exact subject,
   source, and bounded attribute selectors. Expectations are `AT_LEAST_ONE`, `NONE`, and `EXACT`.
10. `ObjectListContains(entityId, property, selector, extent)` checks whether a top-level list
    property contains an object matching the bounded recursive subset selector at the final tick or
    every tick. It enables reusable structured-state assertions without property paths.
11. `AllOf(terms)` combines two through eight non-composite assertions. Nested composites are
    rejected, keeping evaluation depth and serialized size fixed.

`SimulationAssertionSpec` contains one assertion plus at most eight
`SimulationEvidenceRequirement` values. Assertion terms, selectors, strings, lists, nesting, and
returned evidence are validated before copying. Selector objects contain at most 32 total nodes,
at most 16 fields per object, at most four levels, and strings no longer than 1,024 characters;
configured `RuntimeLimits` may impose stricter evaluation-time bounds.

The object selector is a closed `RuntimeValue.ObjectValue`. A scalar selector requires exact
canonical equality. An object selector requires the observed object to contain every selected
field recursively; unselected observed fields are ignored. Selector lists are rejected. This is
bounded structured matching, not arbitrary traversal or a caller-selected path.

## Result semantics

`SimulationAssertionEvidence` identifies optional `SimulationTickId`, execution epoch, epoch tick,
optional runtime frame, evidence kind, optional entity/property, and optional observed value.
Missing IDs explicitly represent a tick or correlation that could not be resolved. Evidence is ordered by
epoch tick and stable entity/property order, then capped at the requested limit. The first concrete
violation or missing tick is retained when available.

`SimulationAssertionResult` reuses `AssertionStatus` and contains the assertion type, exact
simulation scope, expected and observed values when meaningful, bounded evidence,
`evidenceIncomplete`, and a bounded safe message.

Semantics distinguish decisive observations from absence:

- Final assertions inspect only the final requested tick. A complete mismatch is `FAIL`; an
  incomplete or missing final tick is `INCONCLUSIVE`.
- `EVERY_TICK` assertions return `FAIL` for the first complete violating tick even if another tick
  is incomplete. They return `PASS` only when every requested tick is complete and satisfies the
  predicate; otherwise `INCONCLUSIVE`.
- `AT_LEAST_ONE` returns `PASS` on the first matching retained event. With no match it returns
  `FAIL` only for a complete range, otherwise `INCONCLUSIVE`.
- `NONE` returns `FAIL` on the first match and `PASS` only for a complete range.
- `EXACT` returns `FAIL` immediately after more than the expected count. Otherwise it needs a
  complete range to return `PASS` or a lower-count `FAIL`.
- `AllOf` returns `FAIL` when any term has a decisive `FAIL`, `INCONCLUSIVE` when none fails and any
  term is inconclusive, and `PASS` only when every term passes.

No result explains gameplay causality.

## Box2D factories

`Box2dAssertions` in `runtime-box2d` exposes static factories returning only
`SimulationAssertionSpec`. Factories prefix stable body IDs with `box2d.body.`, use the documented
body properties, and canonicalize contact endpoints by fixture ID plus child index exactly as the
contact adapter does. They provide:

- body existence, position, velocity, awake, sleeping, stopped, final area, whole-range bounds,
  distance, wrapped angle, and whole-range speed checks;
- contact occurred and did not occur using exact `box2d.contact.begin` event selectors;
- contact remained active using `box2d.contacts.<worldId>.activeContacts` at every tick;
- the contact completeness requirement for every contact assertion.

`bodyStopped` compiles to `AllOf(VectorMagnitudeAtMost(linearVelocity, ...),
ScalarApproximatelyEquals(angularVelocity, 0, ...))`. Contact selectors use stable body, fixture,
and child-index testimony and do not inspect native fixtures or `userData`.

## Protocol and MCP

Protocol 2.2 adds the closed command:

```text
simulationAssert:
  executionEpochId, fromEpochTick, toEpochTick, evidenceLimit,
  assertion, evidenceRequirements
```

The tagged assertion union mirrors the Java records. Every object uses
`additionalProperties: false`; unknown tags and fields fail before evaluation. Protocol runtime
values use the existing tagged `valueType` mapping; MCP inputs use bounded natural JSON values.
The response is a distinct `simulationAssertion` result carrying `SimulationAssertionResult`.

MCP exposes `runtime_simulation_assert` with the same schema. Capability discovery reports
`simulation-assertions`, protocol 2.3, the tick/evidence/term/requirement bounds, and its dependency
on the simulation timeline and completed frames. Existing commands and tools remain unchanged.

## Tests and fixture

Core tests begin RED against the wished-for API and cover constructor bounds, decimal boundaries,
component/Euclidean vector behavior, area boundaries, magnitude/distance, angle wrapping, exact
event selectors, recursive object selectors, composites, ordering, and defensive copies. Timeline
tests deliberately create eviction, not-yet-executed, failed callback, missing correlation,
truncated frame, false completeness requirement, and mixed incomplete/decisive-violation ranges.

Box2D tests prove every factory emits the documented immutable generic model without retaining or
reading native objects. The real native Box2D fixture advances exact ticks and exercises
contact occurrence through Java, protocol JSON, and MCP. A deliberately missing-tick negative
contact range returns `INCONCLUSIVE`, never `PASS`; focused core tests cover other loss modes and
the Box2D factory tests cover the remaining body/contact mappings.

The cookbook documents exact Java factories, JSON/MCP examples, schemas, boundary semantics,
incompleteness rules, and the requirement to update recipes whenever these public APIs change.

## Compatibility and ownership

`runtime-core` remains JDK-only. `runtime-box2d` depends on core and libGDX Box2D; protocol and MCP
depend only in their existing directions. The runtime creates no thread, scheduler, native object,
or game loop. Application code remains authoritative for input, ticks, mutation, listener
installation, rendering, and disposal. No artifact publication is part of this work.
