# Issue 66 fixed-step helper design

- Status: Approved by the issue contract and the instruction to continue
- Date: 2026-08-09
- Depends on: issue #65 / PR #74

## Purpose

Provide one application-owned fixed-step accumulator that makes deterministic simulation easier
than stepping from render delta. It must expose every clamp, drop, tick, and failure as bounded
typed evidence while leaving rendering, scheduling, mutation, and disposal with the application.

## Considered approaches

1. Put the complete helper in `runtime-libgdx`. This is initially small, but protocol code cannot
   inspect update/drop reports without reversing module dependencies or creating a parallel bridge.
2. Put generic integer-nanosecond accumulation and reports in `runtime-core`, then add a thin
   libGDX float-seconds facade. This keeps core JDK-only, makes reports directly inspectable, and
   lets later adapters reuse the fixed-step contract. This is the selected approach.
3. Add only documentation around a copied accumulator loop. This preserves no canonical behavior
   and cannot diagnose clamping, catch-up drops, or failure boundaries, so it does not satisfy the
   roadmap goal.

## Public model

`FixedStepSimulationRegistry`, owned by `AgentRuntime`, accepts one immutable
`FixedStepSimulationConfiguration` before runtime start. Configuration contains:

- positive `fixedStepNanos`
- `maximumRenderDeltaNanos` at least one step
- `maximumAccumulatedTimeNanos` at least one step
- positive bounded `maximumCatchUpTicks`
- positive bounded `retainedUpdateReports`
- closed `FixedStepDropPolicy.DROP_WHOLE_TICKS_KEEP_REMAINDER`
- `acknowledgementRequired`

Configuration rejects addition/product overflow risks. Registration has acknowledged and legacy
unacknowledged callback forms; the latter is rejected when acknowledgement is required. The
registry registers the same fixed step with the #65 timeline and installs the existing
application-owned pause/resume/control callbacks.

`FixedStepUpdateReport` is immutable and reports update sequence, supplied/accepted/clamped or
paused render time, accumulator-limit and catch-up drops, dropped whole ticks, attempted/completed
ticks, remainder, interpolation alpha, first/final simulation tick and runtime frame, and a bounded
ordered list of closed diagnostics. `FixedStepUpdatePage` exposes stable pagination and eviction.
`FixedStepSimulationState` exposes configuration, accumulator remainder, interpolation alpha,
paused state, latest update sequence, and effective retention bounds.

## Update algorithm

`update(long renderDeltaNanos)` runs on the capture thread after runtime start:

1. reject reentrancy, negative input, nested frame use, and closed/unstarted lifecycle
2. when paused, freeze the accumulator and report all supplied render time as paused/ignored
3. clamp render delta to the configured maximum
4. add accepted time using overflow-safe bounded integer arithmetic
5. execute whole fixed steps through `runtime.simulation().tick(...)`
6. consume a step before invoking the callback so a partially mutating failure is never retried
7. stop after `maximumCatchUpTicks`, drop remaining whole steps, and retain only the remainder
8. calculate interpolation alpha from the remainder without invoking application code
9. retain the report and rethrow any callback/capture failure

On callback/capture failure, remaining whole time is explicitly dropped and only the sub-step
remainder is retained. Disabled runtime mode still calls application simulation and updates the
operational accumulator, but retains no runtime report history.

`clearAccumulator()` and `restoreAccumulator(long)` are explicit capture-thread hooks for scenario
reset and checkpoint restoration. Restored state must be a sub-step remainder; neither operation
creates a simulation tick.

## libGDX facade

`LibGdxFixedStepSimulation` delegates to the core registry. It converts a finite non-negative
`float` render delta to nanoseconds deterministically and exposes one canonical `float`
`fixedStepSeconds` calculated at registration. Its callback receives both the exact integer step
and the canonical float value intended for APIs such as Box2D. Rendering remains a separate
application call.

## Exact controlled stepping

`SimulationControlRegistry.advanceFixed(...)` derives the delta solely from the registered fixed
step and reuses existing bounded application dispatch, at-most-once request evidence, scheduled
input ordering, timeout, cancellation, and frame correlation. It bypasses and does not consume the
normal render accumulator. Existing `runtime_advance` remains unchanged.

Protocol 2.2 adds closed commands and MCP tools for fixed-step state, bounded update reports, and
fixed-step advance. Protocols 1.0-1.13, 2.0, and 2.1 retain their exact unions and behavior.

## Verification contract

Tests must prove integer accumulation, float conversion, pause/remainder preservation, exact
controlled stepping, clamping and both drop paths, mismatch and callback failure evidence,
reentrancy, lifecycle/thread checks, disabled behavior, bounded eviction/order, closed 2.2 JSON/MCP
schemas, fixture/Java use, and cookbook parity. Linux qualification runs under Xvfb.

