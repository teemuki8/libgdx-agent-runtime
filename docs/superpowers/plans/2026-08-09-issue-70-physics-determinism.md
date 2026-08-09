# Tick-Aware Physics Determinism Implementation Plan

**Goal:** Extend the existing determinism registry with exact simulation-tick correlation,
repeatable registered input scripts, explicit Box2D configuration testimony, protocol 2.3, MCP,
and agent-facing recipes while preserving every legacy shape.

## Global constraints

- Keep `runtime-core` JDK-only and `DeterminismRegistry` the sole engine.
- Start every behavior slice with a focused failing test.
- Keep all schemas closed, values bounded, evidence immutable, and ordering stable.
- Do not read native Box2D state outside ordinary registered frame capture.
- Update the cookbook for every public API change.
- Do not publish artifacts or merge roadmap PRs.

## Task 1: Define the generic simulation determinism contracts

- Add constructor-first RED tests for bounded inputs, configuration requirements, event filters,
  operations, results, divergence correlation, defensive copies, and invalid status combinations.
- Add `SimulationDeterminismInput`, `SimulationConfigurationRequirement`,
  `SimulationDeterminismSpec`, `SimulationDeterminismDivergence`,
  `SimulationDeterminismResult`, and `SimulationDeterminismOperation`.
- Run focused core tests and warning-free Javadocs; commit the data model.

## Task 2: Repeat registered input and return authoritative ticks

- Add RED tests proving same-tick input order, identical replay after reset, closed parameter
  validation, ordinary-input exclusion, acknowledged-controller requirement, and failure cleanup.
- Add an input-registry comparison lease and application-thread script execution.
- Return `SimulationTick` plus correlated `FrameSnapshot` from the internal determinism tick path.
- Preserve ordinary injection, control, recording, and legacy determinism behavior.

## Task 3: Execute and compare simulation evidence in the existing registry

- Add RED tests for exact equality, first divergent positive epoch tick and both correlations,
  pre-admission fixed-step/config rejection, post-reset mismatch, failed tick, truncation,
  boolean incompleteness, sanitized reset/callback failure, and shared result eviction.
- Implement `checkSimulation(...)` with shared admission, budgets, retention, capture, difference,
  and failure handling.
- Filter explicitly selected event types without changing the legacy profile.
- Run the focused core determinism suite and repository core gate.

## Task 4: Add the Box2D data-only builder

- Add RED tests for exact world requirement values, stable body/fixture/joint IDs and properties,
  active-contact selection/completeness, contact event filters, input compilation, bounds,
  finite values, and immutable output.
- Implement `Box2dDeterminism` without retaining native objects.
- Run adapter tests and warning-free Javadocs.

## Task 5: Add closed protocol 2.3 support

- Add RED round-trip and service tests for the new command/result and every nested record.
- Prove 2.2 rejection, unknown-field/tag rejection, natural/canonical value bounds, and unchanged
  legacy determinism JSON.
- Add the 2.3 capability and dispatch through `checkSimulation(...)`.
- Run the protocol gate.

## Task 6: Add the closed MCP tool

- Add RED catalog/handler tests for exact schema closure, bounds, polling, typed divergence, and
  invalid configuration before dispatch.
- Add `runtime_simulation_determinism_check` using the protocol/core constructors.
- Run the MCP gate.

## Task 7: Qualify docs, review, and verify

- Update the cookbook with exact Java, JSON, MCP, input-order, reset/rebind, incompleteness, and
  claim-boundary recipes; update README, design contract, getting started, and agent tools.
- Self-review public validation, bounds, lifecycle/thread ownership, failure sanitization,
  compatibility, and dependency direction. Subagent review is omitted because the active agent
  policy does not authorize delegation.
- Run focused module gates, then
  `xvfb-run -a ./gradlew clean check javadoc --warning-mode=fail`, `jdeps` for core, and
  `git diff --check`.
- Commit intentionally, push the isolated branch, and open a stacked draft PR against
  `issue-69-physics-assertions`; do not merge or publish.
