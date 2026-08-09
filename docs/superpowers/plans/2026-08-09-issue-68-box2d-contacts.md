# Bounded Box2D Contact Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture bounded immutable Box2D callbacks and active contacts for each authoritative
simulation tick, with structured runtime entity/event evidence and explicit listener ownership.

**Architecture:** Core exposes only a transient immutable context for the currently executing
simulation tick. `runtime-box2d` owns explicit listener composition, immediate native-value copies,
bounded staging/history, and conversion to existing runtime entities/events. The application owns
listener installation, `World.step`, the simulation loop, and every native object's disposal.

**Tech Stack:** Java 25, libGDX/Box2D 1.14.2, Gradle 9.6.1, JUnit 6, actual desktop Box2D natives.

## Global Constraints

- Preserve `runtime-box2d -> runtime-core`; core remains JDK-only and protocol/MCP stay Box2D-free.
- Never install a listener, step/dispose a world outside the supplied application callback, create a
  thread/timer, reflect over objects, or derive IDs from pointers, identity, order, or `userData`.
- Copy every callback/native-backed value before returning from the callback.
- Bound records, active contacts, points, impulses, old-manifold values, diagnostics, history,
  strings, and query pages before unbounded allocation.
- Every public API change updates `docs/guides/agent-cookbook.md` in the same PR.
- No Maven Central staging or publication task may run.

---

### Task 1: Transient active simulation tick context

**Files:**
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/ActiveSimulationTick.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/AgentRuntime.java`
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationTimelineRegistry.java`
- Test: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationTimelineTest.java`

**Interfaces:**
- Produces: `Optional<ActiveSimulationTick> SimulationTimelineRegistry.activeTick()`.
- `ActiveSimulationTick` fields: `SimulationTickId simulationTickId`,
  `ExecutionEpochId executionEpochId`, `long epochTick`, `long suppliedDeltaNanos`,
  `SimulationTickSource source`, and `FrameId runtimeFrameId`.

- [ ] Add tests that call `activeTick()` before, inside, and after both running and controlled tick
  callbacks. Assert the exact ID/epoch/tick/delta/source/frame inside, empty outside, empty on another
  thread, and cleanup after callback and capture failure.
- [ ] Run
  `./gradlew :runtime-core:test --tests '*SimulationTimelineTest' --warning-mode=fail` and observe
  compilation failure because `ActiveSimulationTick` and `activeTick()` do not exist.
- [ ] Add the validated immutable record. In `SimulationTimelineRegistry.execute`, set the context
  inside the `runtime.frame` callback after obtaining the actual open frame ID, leave it present
  through entity capture, and clear it in the outer `finally`. Add only package-private capture-thread
  helpers to `AgentRuntime`; do not add a listener registry.
- [ ] Run the focused test green, then
  `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh core`.
- [ ] Commit `feat: expose active simulation tick context`.

### Task 2: Immutable contact contracts and bounds

**Files:**
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dContactLimits.java`
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dContactPolicy.java`
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dContactRecord.java`
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dContactTick.java`
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dContactTickPage.java`
- Test: `runtime-box2d/src/test/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dContactContractTest.java`

**Interfaces:**
- `Box2dContactLimits(int callbackRecordsPerTick, int activeContactsPerTick,
  int pointsPerContact, int impulsesPerContact, int oldManifoldPointsPerContact,
  int diagnosticsPerTick, int retainedContactTicks, int queryPageSize)` plus
  `developmentDefaults()`.
- `Box2dContactPolicy(boolean begin, boolean end, boolean preSolve, boolean postSolve)` plus
  `developmentDefaults()` returning `true, true, false, true`.
- `Box2dContactRecord` owns nested closed enums `Phase` and `Availability` plus nested immutable
  `Endpoint`, `Key`, `Impulse`, and `OldManifoldPoint` records. Its lists use `List.copyOf`, optionals
  are non-null, floats have already become finite `BigDecimal`, and record order is canonical.
- `Box2dContactTick` contains the active-tick correlation, immutable sorted records/active contacts,
  observed/retained/limit values, unmapped count, diagnostics, truncations, and `complete`.
- `Box2dContactTickPage` contains the bounded query result, `hasMore`, range status, and oldest/newest
  retained tick IDs.

- [ ] Add constructor tests for zero/negative/excessive limits, nulls, non-finite decimals, duplicate
  or unsorted keys, mutable input lists, invalid counters, inconsistent truncation/completeness, and
  the exact default policy.
- [ ] Run `./gradlew :runtime-box2d:test --tests '*Box2dContactContractTest' --warning-mode=fail` and
  observe missing-type compilation failure.
- [ ] Implement only immutable validation/copying and closed enums. Reuse core `Truncation`,
  `SimulationTickId`, `ExecutionEpochId`, and `FrameId`; do not retain any libGDX type in these values.
- [ ] Run the focused test green and run `git diff --check`.
- [ ] Commit `feat: define bounded Box2D contact evidence`.

### Task 3: Explicit listener composition and immediate callback copying

**Files:**
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dContacts.java`
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dContactCopies.java`
- Modify: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dInspection.java`
- Modify: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dRegistration.java`
- Test: `runtime-box2d/src/test/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dContactsTest.java`

**Interfaces:**
- `Box2dContacts Box2dInspection.registerContacts(String worldId, Box2dContactLimits limits,
  Box2dContactPolicy policy)`; one live contact registration per registered world.
- `ContactListener Box2dContacts.listener()` returns the adapter listener but does not install it.
- `ContactListener Box2dContacts.compose(ContactListener applicationListener)` creates at most one
  evidence-first/application-second composition whose application reference is cleared on close.
- `void Box2dContacts.captureStep(Runnable worldStep)` requires the owner thread and active tick,
  stages exactly one world step, finalizes in all paths, and rethrows the original failure.
- `Box2dContactTickPage Box2dContacts.ticks(long fromTick, long toTick, int limit)` is safe for
  completed concurrent reads.

- [ ] Add actual-native tests proving registration does not change the world's listener, direct and
  composed listeners receive begin/end/pre/post, the application listener runs second, its exception
  is rethrown without storing its message, callbacks immediately survive native object mutation,
  unregistered fixtures expose no partial identity, wrong-thread/outside-tick/double-step/closed use
  is deterministic, and close is idempotent.
- [ ] Add actual-native orientation tests with application fixture IDs opposite native A/B. Assert
  canonical endpoint order, child indices, negated reversed normal/tangent impulse, unchanged points
  and normal impulse, sensors, and absence of begin/end manifold data.
- [ ] Run `./gradlew :runtime-box2d:test --tests '*Box2dContactsTest' --warning-mode=fail` and observe
  missing registration/listener behavior.
- [ ] Implement identity lookup only against the existing explicit fixture/body maps. Copy current
  world manifold, impulse arrays, and enabled/touching/sensor values inside callbacks. For pre-solve,
  copy only old type, point IDs, and old impulse scalars. Record no native-backed object in a field or
  lambda.
- [ ] Retain only the configured callback prefix, sort it at finalization by phase/key/occurrence,
  maintain the bounded ordered active set, saturate observed counters, and retain a bounded tick deque.
  Direct callbacks outside capture copy no endpoint detail and only advance a bounded diagnostic.
- [ ] Run the focused test green and
  `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh libgdx` followed by the adapter tests.
- [ ] Commit `feat: capture explicit Box2D contact callbacks`.

### Task 4: Runtime entity, events, lifecycle reset, and truncation evidence

**Files:**
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dContactValues.java`
- Modify: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dContacts.java`
- Modify: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dInspection.java`
- Test: `runtime-box2d/src/test/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dContactEvidenceTest.java`

**Interfaces:**
- Runtime entity ID `box2d.contacts.<worldId>` and type `box2d.contacts`.
- Event types `box2d.contact.begin`, `.end`, `.preSolve`, and `.postSolve`; canonical body A is
  subject and body B is source.
- Runtime records and active contacts expose per-endpoint sensor flags plus a combined `sensor`
  boolean derived from the immutable endpoint copies.
- Exact entity properties: `worldId`, `runtimeEntityId`, `policy`, `limits`, `latestTick`,
  `records`, `activeContacts`, `callbackCounts`, `activeCounts`, `unmappedContacts`, `diagnostics`,
  `truncations`, and `complete`.

- [ ] Add snapshot tests asserting the exact entity property set and every nested field, exact event
  types/subject/source/attributes/frame correlation, stable order, default pre-solve omission, and
  explicit null availability instead of zero substitutes.
- [ ] Add low-limit tests for callback, active, point, impulse, old-manifold, diagnostic, and history
  bounds. Assert observed/retained/limit and `complete=false`; assert negative evidence cannot be read
  as an empty exact active set.
- [ ] Add epoch-reset, world-rebind, fixture-rebind/unregister, endpoint mutation rejection during an
  open frame, callback-after-close, and missing-correlation tests. Assert the next baseline has no
  leaked active contact and carries the appropriate closed diagnostic code.
- [ ] Run `./gradlew :runtime-box2d:test --tests '*Box2dContactEvidenceTest' --warning-mode=fail` and
  observe missing entity/event/reset behavior.
- [ ] Convert finalized immutable records to `RuntimeValue` using closed exact object/list schemas.
  Emit events only during `captureStep` finalization while the active runtime frame is open. Reset
  contacts when provider capture observes a new epoch and notify contact captures before world or
  fixture rebind/removal.
- [ ] Run all `runtime-box2d` tests green, then
  `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh check`.
- [ ] Commit `feat: publish structured Box2D contact evidence`.

### Task 5: Actual fixture and agent-facing cookbook

**Files:**
- Modify: `runtime-fixtures/src/main/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/FixtureApplication.java`
- Modify: `runtime-fixtures/src/test/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/Box2dInspectionFixtureTest.java`
- Modify: `runtime-fixtures/src/test/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/FixtureProtocolAndMcpTest.java`
- Modify: `docs/design-contract.md`
- Create: `docs/adr/0015-explicit-box2d-contact-evidence.md`
- Modify: `docs/guides/getting-started.md`
- Modify: `docs/guides/agent-cookbook.md`
- Modify: `README.md`

**Interfaces:**
- Canonical loop:
  `runtime.simulation().tick(fixedStepNanos, dt -> { contacts.captureStep(() -> world.step(seconds,
  velocityIterations, positionIterations)); return dt; });`
- Protocol/MCP reuse `runtime_entities`, `runtime_entity_history`, and `runtime_events`; no new Box2D
  command or transport dependency.

- [ ] Extend the actual Box2D fixture with a deterministic collision and assert Java contact entity,
  event, active-set, and tick/frame correlation. Add protocol JSON and MCP transcript assertions for
  the same exact closed schemas. Run the focused fixture tests and observe the missing evidence.
- [ ] Wire the fixture's explicit listener and captured world step; run
  `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh fixture` green under Xvfb.
- [ ] Add ADR 0015 and update the design contract. Document installation/composition order,
  default phases, phase availability, deterministic scope, reset/rebind/close behavior, and no
  inferred causality.
- [ ] Add cookbook recipes for direct listener installation, application-listener composition,
  canonical fixed-step capture, exact entity/event keys, protocol/MCP queries, truncation and
  unmapped diagnostics, and failure output. Update every API introduced in Tasks 1-4.
- [ ] Run cookbook/example tests and `git diff --check`.
- [ ] Commit `docs: qualify Box2D contact evidence`.

### Task 6: Independent review, full verification, and stacked draft PR

- [ ] Review the complete `origin/issue-67-box2d-adapter..HEAD` patch against every issue #68
  acceptance criterion. Fix each Critical/Important finding with a focused failing regression first.
- [ ] Run `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh full` and require Xvfb-backed
  `clean check javadoc --warning-mode=fail` success.
- [ ] Run
  `jdeps --multi-release 25 --print-module-deps runtime-core/build/libs/runtime-core-*.jar` and
  require exactly `java.base`.
- [ ] Confirm `git diff --check`, clean status, no publish/staging task, and an issue-only commit/file
  range against `origin/issue-67-box2d-adapter`.
- [ ] Push `issue-68-box2d-contacts` and open a draft PR against `issue-67-box2d-adapter` with
  `Fixes #68`, acceptance coverage, and exact local gates.
- [ ] Review the remote PR head, address actionable feedback, and wait for Linux, Windows, and macOS
  checks tied to that exact SHA.
