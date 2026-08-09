# Explicit Box2D Inspection Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a separately publishable explicit bounded Box2D inspection adapter whose selected
physics facts flow through existing runtime entities.

**Architecture:** `runtime-box2d` depends on core and gdx-box2d, owns weak native registrations,
and declares closed entity property providers. Core, protocol, and MCP remain unchanged except that
their existing entity paths naturally carry adapter evidence.

**Tech Stack:** Java 25, libGDX/Box2D 1.14.2, Gradle 9.6.1, JUnit 6, LWJGL3 desktop natives.

## Global Constraints

- Core stays JDK-only; protocol and MCP never depend on Box2D.
- No reflection, object graph traversal, native-derived ID, disposal ownership, worker, or loop.
- Registration and live capture are application-thread owned; completed evidence is immutable.
- Every ID, collection, vertex list, property set, diagnostic list, and native reference is bounded.
- No Maven Central staging or publication.

---

### Task 1: Module and immutable public configuration

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `runtime-box2d/build.gradle.kts`
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dAdapterLimits.java`
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dUnitTransform.java`
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dWorldSpec.java`
- Test: `runtime-box2d/src/test/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dConfigurationTest.java`

**Interfaces:**
- `Box2dAdapterLimits.developmentDefaults()` and seven positive bounded fields.
- `Box2dUnitTransform(double renderUnitsPerMeter)` with scalar and vector conversions.
- `Box2dWorldSpec(boolean sleepingAllowed, boolean warmStarting, boolean continuousPhysics,
  int velocityIterations, int positionIterations, OptionalDouble inverseStep,
  Box2dUnitTransform unitTransform)`.

- [ ] Write constructor/conversion tests for finite scale, overflow, invalid limits/iterations, and
  defensive optional handling; run the focused test and observe missing-type compilation failure.
- [ ] Add the module/dependency aliases and minimal immutable records; run the focused test green.
- [ ] Commit `feat: add Box2D adapter configuration`.

### Task 2: Explicit bounded registrations and entity mapping

**Files:**
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dRegistration.java`
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dInspection.java`
- Test: `runtime-box2d/src/test/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dRegistrationTest.java`

**Interfaces:**
- `registerWorld(String, World, Box2dWorldSpec)`
- `registerBody(String, String worldId, Body)`
- `registerFixture(String, String bodyId, Fixture)`
- `registerJoint(String, String worldId, Joint)`
- `Box2dRegistration<T>.id()`, `.runtimeEntityId()`, `.rebind(T)`, `.close()`.

- [ ] Write actual-native tests for deterministic mappings, only-selected exposure, duplicate ID and
  native object rejection, missing/conflicting relationships, every count limit, wrong-thread use,
  close, idempotent unregister, rebind, and world recreation ordering; observe red.
- [ ] Implement one owner-thread registry with weak native references, preflight validation, stable
  kind maps, rollback-safe core entity registration, and idempotent handles; run tests green.
- [ ] Commit `feat: add explicit Box2D registrations`.

### Task 3: World and body closed schemas

**Files:**
- Modify: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dInspection.java`
- Test: `runtime-box2d/src/test/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dWorldBodyInspectionTest.java`

**Interfaces:**
- Runtime types `box2d.world` and `box2d.body` with the exact properties from issue #67.

- [ ] Write snapshot tests with actual worlds/static/dynamic bodies and assert exact property names,
  values, application/runtime IDs, selected/total counts, fixed-step testimony, and stable order.
- [ ] Observe missing properties, then implement capture-thread copying using only explicit getters
  and registered relationships; run focused tests green.
- [ ] Add provider-failure tests for destroyed/unrebound native objects and prove structured core
  diagnostics contain no native message or stack trace.
- [ ] Commit `feat: inspect registered Box2D worlds and bodies`.

### Task 4: Fixture shapes and bounded geometry

**Files:**
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dShapeValues.java`
- Modify: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dInspection.java`
- Test: `runtime-box2d/src/test/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dFixtureInspectionTest.java`

**Interfaces:**
- Closed `geometry` objects for circle, polygon, edge, and chain; vertex-bearing objects include
  `observedVertices`, `retainedVertices`, `vertexLimit`, and `truncated`.

- [ ] Write actual shape tests for materials, sensor/filter bits, local geometry, vertex order,
  unsigned category/mask values, signed group, truncation, and immutable snapshots; observe red.
- [ ] Implement shape copying without retaining `Shape`, `Vector2`, or native arrays; run green.
- [ ] Commit `feat: inspect bounded Box2D fixture geometry`.

### Task 5: Generic and type-specific joint schema

**Files:**
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dJointValues.java`
- Modify: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dInspection.java`
- Test: `runtime-box2d/src/test/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dJointInspectionTest.java`

**Interfaces:**
- Generic joint properties plus closed `detail` for distance, revolute, prismatic, or `generic`.
- Reaction force/torque are null without explicit inverse step and copied vectors when present.

- [ ] Write actual joint tests covering both bodies, anchors, active/collide-connected, three detail
  types, generic fallback, and reaction gating; observe red.
- [ ] Implement closed instanceof dispatch over Box2D joint classes and run green.
- [ ] Commit `feat: inspect registered Box2D joints`.

### Task 6: Build qualification, fixture, and agent documentation

**Files:**
- Modify: `runtime-fixtures/build.gradle.kts`
- Modify: `runtime-fixtures/gradle.lockfile`
- Create: `runtime-box2d/gradle.lockfile`
- Modify: `gradle/verification-metadata.xml`
- Modify: `docs/dependency-review.md`
- Modify: `docs/design-contract.md`
- Modify: `docs/guides/getting-started.md`
- Modify: `docs/guides/agent-cookbook.md`
- Modify: `README.md`
- Test: `runtime-fixtures/src/test/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/Box2dInspectionFixtureTest.java`

- [ ] Add a fixture test proving actual Box2D state crosses Java and protocol/MCP entity inspection
  without new transport types; observe red before fixture wiring.
- [ ] Add adapter/native fixture dependencies, lock all configurations, and generate verification
  metadata only for newly resolved components after reviewing their provenance/licenses.
- [ ] Document the canonical explicit registration/rebind/unit workflow and update the cookbook for
  every public API added in Tasks 1-5.
- [ ] Run adapter, fixture, publication archive, dependency verification, and core `jdeps` checks.
- [ ] Commit `docs: qualify Box2D inspection adapter`.

### Task 7: Final review and publication-free PR

- [ ] Run `git diff --check` and independent review against issue #67; fix every Critical/Important
  finding with a failing regression first.
- [ ] Run `xvfb-run -a ./gradlew clean check javadoc --warning-mode=fail` and verify core with
  `jdeps --multi-release 25 --print-module-deps runtime-core/build/libs/*.jar`.
- [ ] Confirm no publish/staging task ran, commit final fixes, push `issue-67-box2d-adapter`, and open
  a draft PR against `issue-65-simulation-timeline` with `Fixes #67`.
- [ ] Monitor Linux, Windows, and macOS CI for the exact PR head.
