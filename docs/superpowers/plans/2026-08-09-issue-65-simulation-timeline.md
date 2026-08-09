# Simulation Timeline Implementation Plan

> **For agentic workers:** Execute each task test-first in this worktree. Do not delegate this plan;
> the repository owner requested sequential issue implementation.

**Goal:** Add bounded application-reported simulation ticks shared by normal, controlled, and
determinism execution and expose them through Java, protocol 2.1, MCP, fixtures, and documentation.

**Architecture:** A new core registry owns tick identity, epoch time, the shared callback/capture
boundary, and immutable retention. Existing control code delegates to it without changing legacy
`currentTick()` or old protocol shapes. Protocol 2.1 adds closed read-only state/history commands.

**Tech Stack:** Java 25, JUnit 5, Jackson closed unions, MCP Java SDK, Gradle 9, Jdeps.

## Global Constraints

- `runtime-core` stays JDK-only and application/capture-thread owned.
- No reflection, arbitrary traversal, hidden scheduling, thread, timer, sleep, or rendering owner.
- Public evidence is immutable, deterministic, explicitly bounded, and honest under failure.
- Protocols 1.0-1.13 and 2.0 retain exact command availability and serialized shapes.
- Every API change updates the agent cookbook or, until #73 creates it, the canonical agent guides.
- No artifact publication.

---

### Task 1: Core timeline model and boundary

**Files:**
- Create core `SimulationTimeline*`, `SimulationTick*`, and callback types.
- Modify `AgentRuntime`, `SimulationControlRegistry`, `SimulationControllerSpec`, and recording hooks.
- Test in `SimulationTimelineTest` and focused existing control/recording tests.

**Interfaces:**
- `AgentRuntime.simulation()` returns `SimulationTimelineRegistry`.
- `SimulationTimelineRegistry.register(SimulationTimelineSpec)` configures an optional fixed step.
- `SimulationTimelineRegistry.tick(long, SimulationTickCallback)` executes a normal tick.
- `SimulationTimelineRegistry.state()` and `ticks(SimulationTickQuery)` return bounded evidence.

- [ ] Write lifecycle, first-tick, mismatch, failure, epoch, eviction, overflow, disabled, close,
      ordering, and wrong-thread tests.
- [ ] Run the focused tests and observe failures caused by the absent API.
- [ ] Add the minimum validated immutable model and shared boundary.
- [ ] Delegate controlled and determinism execution through the boundary while preserving old
      control counters and responses.
- [ ] Run the core gate and keep all existing tests green.

### Task 2: Protocol 2.1 and capability discovery

**Files:**
- Modify `ProtocolVersion`, `RuntimeCommand`, `RuntimeResponse`, and `RuntimeProtocolService`.
- Test in `RuntimeProtocolTest` and frozen JSON fixtures.

**Interfaces:**
- `RuntimeCommand.Simulation` reads current state.
- `RuntimeCommand.SimulationTicks` reads one bounded epoch-relative range.
- `RuntimeResponse.Result.Simulation` and `SimulationTicks` retain the core records directly.

- [ ] Write failing exact-version, unknown-field, state, history, eviction, and capability tests.
- [ ] Add exact protocol 2.1 support and keep 2.0 unaware of the new commands.
- [ ] Add typed service dispatch and bounded capability metadata.
- [ ] Run the protocol gate and compare frozen 1.x/2.0 golden output unchanged.

### Task 3: MCP tools and real fixture slice

**Files:**
- Modify `RuntimeToolCatalog`, `RuntimeToolHandler`, and MCP tests.
- Modify fixture application/tests to execute and inspect an application-reported tick.

**Interfaces:**
- `runtime_simulation` has a closed session-only input schema.
- `runtime_simulation_ticks` requires session, epoch, inclusive tick range, and bounded limit.

- [ ] Write failing catalog/handler and fixture tests.
- [ ] Add both closed MCP tools using protocol 2.1.
- [ ] Prove Java tick to protocol/MCP tick-to-frame correlation in the fixture.
- [ ] Run MCP and fixture gates under Xvfb.

### Task 4: Public contract, cookbook precursor, and verification

**Files:**
- Modify `docs/design-contract.md`, getting-started, agent-tools, instrumentation guidance,
  README/release compatibility notes where needed.
- Add the accepted ADR before production implementation.

- [ ] Document normal and controlled integration, explicit delta acknowledgement, mismatch output,
      bounds, lifecycle, and rendering independence with compilable snippets.
- [ ] Run `git diff --check` and inspect every changed public record's validation/Javadocs.
- [ ] Run the repository `check` gate, then the full Xvfb gate because the public vertical slice and
      real fixture changed.
- [ ] Review the final issue checklist and PR range, commit, push, and open a ready scoped PR that
      fixes #65.
