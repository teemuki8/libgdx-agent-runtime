# Agent cookbook

This cookbook contains task-oriented, versioned recipes for coding agents integrating or operating
the runtime. Every public Java API, protocol/MCP contract, dependency, or agent-visible behavior
change must update its affected recipe in the same pull request. Examples are exercised by the
repository fixture tests.

The simulation timeline/fixed-step APIs and protocols 2.1/2.2 described below are
development-version APIs until a release containing them is published. The current published
2.0.0 artifacts do not contain them.

## Fixed-step simulation ticks

Use this when game correctness depends on Box2D or another authoritative fixed-step simulation.
Rendering stays outside the tick callback. Use the canonical helper instead of copying accumulator
logic into each game.

```java
private static final long FIXED_STEP_NANOS = 16_666_667L;

runtime = LibGdxAgentRuntime.builder()
        .captureThread(Thread.currentThread())
        .commandDispatcher(Gdx.app::postRunnable)
        .build();
FixedStepSimulationConfiguration configuration =
        FixedStepSimulationConfiguration.developmentDefaults(FIXED_STEP_NANOS);
LibGdxFixedStepSimulation simulation = LibGdxFixedStepSimulation.acknowledged(
        runtime, configuration, tick -> {
            processGameInput();
            world.step(tick.fixedStepSeconds(), 6, 2);
            gameLogicAfterPhysics();
            return tick.fixedStepNanos(); // the delta actually executed
        });
registerInspectableState(runtime);
runtime.start();

// render(): the application still owns when update and render occur.
FixedStepUpdateReport report = simulation.update(Gdx.graphics.getDeltaTime());
renderGame(simulation.interpolationAlpha());
```

The immutable configuration bounds render delta, accumulated time, catch-up ticks, and retained
update reports. The helper uses integer nanoseconds for accumulation and calculates one canonical
float step for APIs such as Box2D. The callback return remains explicit application testimony; the
generic helper does not infer that application code used the supplied float.

Inspect `report.diagnostics()` on every non-empty diagnostic result. For example:

```text
suppliedRenderDeltaNanos: 500000000
acceptedRenderDeltaNanos: 250000005
clampedRenderTimeNanos: 249999995
ticksAttempted: 8
ticksCompleted: 8
catchUpDroppedTimeNanos: 116666669
droppedTicks: 7
diagnostics: [RENDER_DELTA_CLAMPED, CATCH_UP_TICKS_DROPPED]
```

Dropped time is intentional loss evidence, never successful simulation time. Interpolation alpha
comes only from the retained sub-step remainder and reading it never mutates authoritative state.

Success evidence has distinct identities and timing:

```text
simulationTickId: 1
executionEpochId: 0
epochTick: 1
configuredFixedStepNanos: 16666667
runtimeSuppliedDeltaNanos: 16666667
executedDeltaNanos: 16666667
epochSimulationTimeNanos: 16666667
resultingFrameId: 1
source: RUNNING
outcome: COMPLETED
```

Do not use `FrameSnapshot.deltaNanos` as proof that simulation executed that delta. A runtime frame
is observation evidence. The callback return is explicit application testimony. If it reports
`33333334` while the fixed step is `16666667`, the tick outcome is `DELTA_MISMATCH` and the
diagnostic names the supplied/configured mismatch.

For exact controlled stepping, pause through the existing application dispatcher and advance with
the registered fixed step. There is no caller-selected delta:

```java
runtime.controls().control(true, "pause", Duration.ofSeconds(1));
runtime.controls().advanceFixed("two-ticks", 2, Duration.ofSeconds(1));
```

The equivalent MCP tool is `runtime_simulation_advance`. Scheduled registered input runs before
the controlled callback. Controlled ticks bypass and preserve the render accumulator remainder.
The legacy unacknowledged registration form remains available only when configuration explicitly
allows it and produces `UNACKNOWLEDGED` timeline evidence.

From a scenario reset or checkpoint restore callback, explicitly clear or restore accumulator
state before the new epoch baseline:

```java
resetGameState();
runtime.fixedStepSimulation().clearAccumulator();
```

### Inspect through Java

```java
SimulationState state = runtime.simulation().state();
SimulationTickPage page = runtime.simulation().ticks(new SimulationTickQuery(
        runtime.currentEpoch(), 1, 60, 60));
if (!page.complete()) {
    // Inspect rangeStatus(): PAGINATED, PARTIALLY_EVICTED, or NOT_YET_EXECUTED.
}

FixedStepSimulationState accumulator = runtime.fixedStepSimulation().state();
FixedStepUpdatePage updates = runtime.fixedStepSimulation().updates(
        new FixedStepUpdateQuery(1, 120, 120));
```

Tick queries are safe from any thread after ticks complete. Mutation, registration, and tick
execution remain capture-thread owned. A scenario reset or checkpoint restore creates a new epoch;
its baseline is not a tick, its first tick is epoch tick 1, and session tick IDs are not reused.

### Inspect through MCP

Request current state:

```json
{"name":"runtime_simulation","arguments":{"sessionId":"game"}}
```

Request an inclusive bounded range:

```json
{"name":"runtime_simulation_ticks","arguments":{"sessionId":"game","executionEpochId":0,"fromEpochTick":1,"toEpochTick":60,"limit":60}}
```

Both tools have closed inputs and use exact protocol 2.1. Protocol 2.0 rejects the commands. A
callback failure may leave application mutation unknown and may still have a completed resulting
frame; inspect both `outcome` and `mutationOutcome` before deciding whether retry/reset is safe.

Inspect fixed-step state and loss reports through exact protocol 2.2:

```json
{"name":"runtime_fixed_step","arguments":{"sessionId":"game"}}
```

```json
{"name":"runtime_fixed_step_updates","arguments":{"sessionId":"game","fromSequence":1,"toSequence":120,"limit":120}}
```

Advance paused simulation without a delta field:

```json
{"name":"runtime_simulation_advance","arguments":{"sessionId":"game","controlRequestId":"step-60","ticks":60,"timeoutNanos":1000000000}}
```

Protocol 2.1 rejects these 2.2 commands. Reuse the same advance request ID and fields to poll its
at-most-once operation; changing them is rejected. A failed attempted tick is consumed, remaining
whole accumulator time is reported as dropped, and only the sub-step remainder is retained so an
unknown partial mutation is not silently replayed.

Failure reports preserve the retained simulation-tick and runtime-frame correlation whenever that
evidence exists. Inspect the closed diagnostics rather than treating every thrown update alike:
`APPLICATION_CALLBACK_FAILED`, `RUNTIME_CAPTURE_FAILED`, `EXECUTED_DELTA_INVALID`, and
`SIMULATION_TIME_LIMIT_EXCEEDED` distinguish the failure boundary; `TICK_FAILED` is the common
failure marker. Invalid executed-delta and epoch-time-limit outcomes may have completed application
mutation and capture even though the update rethrows, so inspect the correlated timeline outcome
before retrying or resetting.

Close the runtime on its capture thread. Completed immutable tick pages remain queryable after
close; no new tick is accepted.
