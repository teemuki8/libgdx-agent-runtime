# Simulation-Scoped Physics Assertions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add generic bounded assertions over exact simulation-tick evidence and Box2D convenience factories, with protocol 2.2, MCP, and real native fixture coverage.

**Architecture:** Keep the frozen frame assertion API unchanged. Add a separate JDK-only
`SimulationAssertion` model and evaluator that resolves epoch ticks through the simulation timeline
before reading immutable runtime frames. `runtime-box2d` constructs this generic model from stable
IDs; protocol/MCP add an additive 2.2 command and tool.

**Tech Stack:** Java 25 records and sealed interfaces, `BigDecimal`, JUnit 5, Jackson closed tagged
unions, MCP stdio tool schemas, libGDX Box2D natives, Gradle 9.6.1, Linux Xvfb.

## Global Constraints

- `runtime-core` remains JDK-only and creates no worker, scheduler, timer, or game loop.
- Existing `RuntimeAssertion`, `runtime_assert`, protocol 1.7, and wait schemas remain unchanged.
- All values are immutable, deterministic, defensively copied, and bounded before allocation.
- Evaluation reads only completed timeline/frame/contact evidence; it never reads native objects.
- Negative and temporal PASS requires complete relevant tick and adapter evidence.
- No expressions, scripts, reflection, arbitrary property paths, or inferred causality.
- Every public API change updates `docs/guides/agent-cookbook.md` in the same PR.
- No artifact publication is authorized.

---

### Task 1: Define the bounded simulation assertion model

**Files:**
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationAssertion.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationAssertionSpec.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationEvidenceRequirement.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationAssertionScope.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationAssertionEvidence.java`
- Create: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationAssertionResult.java`
- Test: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationAssertionContractTest.java`

**Interfaces:**
- Produces: `SimulationAssertion` records and enums from the design, including `Extent`,
  `VectorToleranceMode`, `AreaRelation`, `EventExpectation`, `Area`, `EventSelector`, and bounded
  non-nested `AllOf`.
- Produces: `SimulationAssertionSpec(SimulationAssertion assertion,
  List<SimulationEvidenceRequirement> evidenceRequirements)`.
- Produces: `SimulationAssertionScope(ExecutionEpochId executionEpochId, long fromEpochTick,
  long toEpochTick, int evidenceLimit)` with `MAX_TICKS=1000`, `MAX_EVIDENCE=100`.

- [ ] **Step 1: Write constructor and immutability tests first**

Add tests that construct every union member and then reject negative tolerances, zero periods,
angle tolerance above half-period, inverted areas, invalid identifiers, more than eight terms or
requirements, nested `AllOf`, selector lists, selector depth/node overflow, oversized result
evidence, inconsistent epoch/tick/frame evidence, and mutable input lists. Use literal expected
values, for example:

```java
assertThrows(IllegalArgumentException.class, () ->
        new SimulationAssertion.ScalarApproximatelyEquals(
                EntityId.of("ball"), "x", BigDecimal.ZERO, new BigDecimal("-0.01")));
assertEquals(List.of(first, second),
        new SimulationAssertion.AllOf(new ArrayList<>(List.of(first, second))).terms());
```

- [ ] **Step 2: Run RED contract test**

Run:
`./gradlew :runtime-core:test --tests '*SimulationAssertionContractTest' --warning-mode=fail`

Expected: compilation fails because the simulation assertion types do not exist.

- [ ] **Step 3: Implement only the validated immutable data model**

Use canonical `RuntimeValue.DecimalValue` values for every decimal. Validate selector trees with
hard limits before copying. Give each public type warning-free Javadocs. Do not add evaluation.

- [ ] **Step 4: Run GREEN contract test and core Javadocs**

Run:
`./gradlew :runtime-core:test --tests '*SimulationAssertionContractTest' :runtime-core:javadoc --warning-mode=fail`

- [ ] **Step 5: Commit Task 1**

Commit: `feat: define bounded simulation assertions`

### Task 2: Evaluate exact simulation-tick evidence

**Files:**
- Modify: `runtime-core/src/main/java/io/github/teemuki8/libgdx/agent/runtime/core/AssertionEvaluator.java`
- Test: `runtime-core/src/test/java/io/github/teemuki8/libgdx/agent/runtime/core/SimulationAssertionEvaluatorTest.java`

**Interfaces:**
- Consumes: Task 1 model.
- Produces: `public SimulationAssertionResult evaluateSimulation(
  SimulationAssertionSpec spec, SimulationAssertionScope scope)`.

- [ ] **Step 1: Write RED numeric/final-state tests**

Create real enabled `AgentRuntime` instances, register the simulation timeline, publish entity
vectors/scalars, execute acknowledged ticks, and assert inclusive literal boundaries for scalar,
component/Euclidean vector, area inside/outside, magnitude, distance, and wrapped angle. Mutating a
comparison from `<=` to `<` must break at least one test.

- [ ] **Step 2: Run RED numeric tests**

Run:
`./gradlew :runtime-core:test --tests '*SimulationAssertionEvaluatorTest' --warning-mode=fail`

Expected: compilation fails because `evaluateSimulation` is absent.

- [ ] **Step 3: Implement timeline loading and final primitives**

Page `SimulationTimelineRegistry.ticks` by its configured query limit, verify every requested epoch
tick, require completed/known mutation/frame correlation, and retain per-tick completeness. Compare
squared vector distances using `BigDecimal`; do not use `double`, `Math.sqrt`, or frame-range
inference.

- [ ] **Step 4: Run GREEN numeric tests**

Run the focused evaluator test and retain its successful output.

- [ ] **Step 5: Write RED temporal, selector, composite, and incomplete-evidence tests**

Add actual frame events and list properties. Cover exact subject/source/attribute matching,
recursive object subset matching, stable first-violation evidence, event NONE/AT_LEAST_ONE/EXACT,
`AllOf`, and these deliberate incomplete paths: evicted tick, not-yet tick, callback failure,
capture failure/missing frame, wrong epoch, frame truncation, and false/missing boolean evidence
requirement. Assert `INCONCLUSIVE` instead of PASS for every absence/temporal path, while a complete
earlier violation remains decisive FAIL.

- [ ] **Step 6: Run RED for the missing temporal branches, implement minimally, then GREEN**

Run the same focused test after each branch. Implement the switch branches and a bounded internal
outcome carrying decisive status; return only public immutable evidence.

- [ ] **Step 7: Run affected core suite and commit Task 2**

Run: `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh core`

Commit: `feat: evaluate exact simulation tick assertions`

### Task 3: Add Box2D data-only assertion factories

**Files:**
- Create: `runtime-box2d/src/main/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dAssertions.java`
- Test: `runtime-box2d/src/test/java/io/github/teemuki8/libgdx/agent/runtime/box2d/Box2dAssertionsTest.java`

**Interfaces:**
- Produces static `SimulationAssertionSpec` factories for all issue #69 body/contact operations.
- Contact methods consume `worldId`, both stable body/fixture IDs, and both child indices and add
  `SimulationEvidenceRequirement(EntityId.of("box2d.contacts." + worldId), "complete")`.

- [ ] **Step 1: Write RED factory tests without creating native objects**

Assert exact immutable records for body properties (`position`, `linearVelocity`,
`angularVelocity`, `awake`, `angleRadians`), canonical contact subject/source/key selectors,
contact completeness requirements, body-stopped `AllOf`, and EVERY_TICK extent for continuity and
speed. Also reject blank IDs, negative child indexes, and non-canonical numeric inputs.

- [ ] **Step 2: Run RED factory test**

Run:
`./gradlew :runtime-box2d:test --tests '*Box2dAssertionsTest' --warning-mode=fail`

Expected: compilation fails because `Box2dAssertions` is absent.

- [ ] **Step 3: Implement thin constructors only**

Do not accept or store `World`, `Body`, `Fixture`, `Contact`, or callbacks. Canonicalize endpoint
tuples with the same stable fixture-ID/child-index ordering documented by contact evidence.

- [ ] **Step 4: Run GREEN adapter tests/Javadocs and commit Task 3**

Run:
`./gradlew :runtime-box2d:test --tests '*Box2dAssertionsTest' :runtime-box2d:javadoc --warning-mode=fail`

Commit: `feat: add Box2D assertion factories`

### Task 4: Add closed protocol 2.2 simulation assertions

**Files:**
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/ProtocolVersion.java`
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeCommand.java`
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeResponse.java`
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/ProtocolJson.java`
- Modify: `runtime-protocol/src/main/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeProtocolService.java`
- Test: `runtime-protocol/src/test/java/io/github/teemuki8/libgdx/agent/runtime/protocol/RuntimeProtocolTest.java`

**Interfaces:**
- Produces: `ProtocolVersion.V2_2` and `CURRENT=V2_2`.
- Produces: `RuntimeCommand.SimulationAssert` and response result tag `simulationAssertion`.

- [ ] **Step 1: Write RED protocol tests**

Round-trip every assertion tag at 2.2, assert canonical JSON, invoke the real evaluator through the
service, and assert 2.1 rejection with the exact required-version message. Send unknown assertion
tags, unknown top-level/nested fields, oversized selectors/requirements, and malformed numeric
values and assert typed invalid-request failures before evaluation. Re-run frozen 1.7 assertion
goldens unchanged.

- [ ] **Step 2: Run RED protocol tests**

Run:
`./gradlew :runtime-protocol:test --tests '*RuntimeProtocolTest' --warning-mode=fail`

- [ ] **Step 3: Implement additive mixin, command, response, capability, and service dispatch**

Use a distinct Jackson mixin for `SimulationAssertion`. Do not add any new subtype to the old
`RuntimeAssertionMixin`. Validate all command fields in its compact constructor.

- [ ] **Step 4: Run GREEN protocol gate and commit Task 4**

Run: `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh protocol`

Commit: `feat: expose simulation assertions in protocol 2.2`

### Task 5: Add the closed MCP tool

**Files:**
- Modify: `runtime-mcp/src/main/java/io/github/teemuki8/libgdx/agent/runtime/mcp/RuntimeToolCatalog.java`
- Modify: `runtime-mcp/src/main/java/io/github/teemuki8/libgdx/agent/runtime/mcp/RuntimeToolHandler.java`
- Test: `runtime-mcp/src/test/java/io/github/teemuki8/libgdx/agent/runtime/mcp/RuntimeMcpTest.java`

**Interfaces:**
- Produces MCP tool `runtime_simulation_assert` with a closed schema matching protocol 2.2.

- [ ] **Step 1: Write RED catalog/handler tests**

Assert exact tool names, `additionalProperties:false` at every object level, all union tags,
selector and list bounds, capability metadata, successful actual evaluation, and rejection of
unknown tags/fields before dispatch. Confirm `runtime_assert` schema is unchanged.

- [ ] **Step 2: Run RED MCP tests**

Run: `./gradlew :runtime-mcp:test --tests '*RuntimeMcpTest' --warning-mode=fail`

- [ ] **Step 3: Implement schema and parsing with shared core constructors**

Parse only documented fields, reuse natural runtime-value conversion, and let core constructors
enforce semantic bounds. Do not add class-name input or reflection.

- [ ] **Step 4: Run GREEN MCP gate and commit Task 5**

Run: `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh mcp`

Commit: `feat: expose simulation assertions through MCP`

### Task 6: Prove the real Box2D vertical slice

**Files:**
- Modify: `runtime-fixtures/src/test/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/Box2dInspectionFixtureTest.java`
- Modify only if required: existing Box2D fixture application/simulation helpers under
  `runtime-fixtures/src/main/java/io/github/teemuki8/libgdx/agent/runtime/fixtures/`

**Interfaces:**
- Consumes all prior tasks through real native Box2D, protocol JSON, and MCP.

- [ ] **Step 1: Write the failing native acceptance tests**

Reset the existing fixture scenario, pause, advance exact ticks, and evaluate literal factory
expectations for body position/sleeping, whole-range speed, contact occurrence/absence, and active
contact continuity. Serialize at least one assertion through protocol and execute the equivalent
MCP tool. Deliberately lower contact retention/truncation evidence and assert `INCONCLUSIVE` for a
negative contact check.

- [ ] **Step 2: Run RED fixture class under Xvfb**

Run:
`xvfb-run -a ./gradlew :runtime-fixtures:test --tests '*Box2dInspectionFixtureTest' --warning-mode=fail`

- [ ] **Step 3: Add only missing fixture wiring and run GREEN**

Do not mock Box2D bodies or bypass the application-owned tick path. Keep rendering independent of
assertion correctness.

- [ ] **Step 4: Run fixture gate and commit Task 6**

Run: `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh fixture`

Commit: `test: prove physics assertions in native fixture`

### Task 7: Document, review, and publish the issue-only PR

**Files:**
- Create: `docs/adr/0016-simulation-scoped-declarative-assertions.md`
- Modify: `README.md`
- Modify: `docs/design-contract.md`
- Modify: `docs/guides/agent-cookbook.md`
- Modify: `docs/guides/agent-tools.md`
- Modify: `docs/guides/getting-started.md`
- Modify compatibility/release-coordinate docs only if the actual public coordinate set changes
  (it must not for this issue).

- [ ] **Step 1: Document exact API and schemas**

Add compilable Java recipes for every `Box2dAssertions` factory, protocol/MCP request/response
examples, final versus EVERY_TICK semantics, inclusive boundaries, selector matching, exact status
rules, and deliberate incomplete-evidence diagnostics. State that every future API change must
update the cookbook.

- [ ] **Step 2: Self-review docs and complete diff**

Search for stale protocol `CURRENT`, assertion-tag lists, exact schemas, artifact counts, and old
claims that all assertions are frame-scoped. Run `git diff --check` and review
`origin/issue-68-box2d-contacts..HEAD` for unrelated files.

- [ ] **Step 3: Obtain independent read-only review and fix findings test-first**

Require no Critical/Important findings on boundedness, incomplete PASS, protocol compatibility,
thread/lifecycle behavior, native-object isolation, and cookbook/API agreement.

- [ ] **Step 4: Run final verification**

Run:

```bash
.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh full
jdeps --multi-release 25 --print-module-deps runtime-core/build/libs/runtime-core-*.jar
git diff --check
```

Expected: full clean Xvfb build passes, `jdeps` prints only `java.base`, and the worktree is clean.

- [ ] **Step 5: Commit docs, push, and open the stacked draft PR**

Commit: `docs: explain simulation-scoped physics assertions`

Push `issue-69-physics-assertions`, open a draft PR with base `issue-68-box2d-contacts`, include
`Fixes #69`, verify the remote head equals the reviewed SHA, and wait for Ubuntu/macOS/Windows CI
on that exact head. Do not merge or publish artifacts.
