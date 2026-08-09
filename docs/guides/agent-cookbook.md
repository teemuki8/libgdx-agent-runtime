# Agent cookbook

This cookbook contains task-oriented, versioned recipes for coding agents integrating or operating
the runtime. Every public Java API, protocol/MCP contract, dependency, or agent-visible behavior
change must update its affected recipe in the same pull request. Examples are exercised by the
repository fixture tests.

The simulation timeline APIs and protocol 2.1 described below are development-version APIs until a
release containing them is published. The current published 2.0.0 artifacts do not contain them.

## Fixed-step simulation ticks

Use this when game correctness depends on Box2D or another authoritative fixed-step simulation.
Rendering stays outside the tick callback.

```java
private static final long FIXED_STEP_NANOS = 16_666_667L;

runtime = LibGdxAgentRuntime.builder()
        .captureThread(Thread.currentThread())
        .commandDispatcher(Gdx.app::postRunnable)
        .build();
runtime.simulation().register(SimulationTimelineSpec.fixedStep(FIXED_STEP_NANOS));
registerInspectableState(runtime);
runtime.start();

// Called by the application-owned accumulator or controlled scheduler.
runtime.simulation().tick(FIXED_STEP_NANOS, suppliedDeltaNanos -> {
    processGameInput();
    updateSimulation(suppliedDeltaNanos);
    return FIXED_STEP_NANOS; // the delta actually executed
});

renderGame();
```

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

For controlled stepping, prefer the acknowledged callback while preserving application-owned pause
and dispatch:

```java
runtime.controls().register(SimulationControllerSpec.builder()
        .pause(() -> paused = true)
        .resume(() -> paused = false)
        .acknowledgedTick(deltaNanos -> {
            updateSimulation(deltaNanos);
            return deltaNanos;
        })
        .build());
```

The legacy `.tick(LongConsumer)` form remains source compatible but produces `UNACKNOWLEDGED`
timeline entries because the runtime cannot infer the executed delta.

### Inspect through Java

```java
SimulationState state = runtime.simulation().state();
SimulationTickPage page = runtime.simulation().ticks(new SimulationTickQuery(
        runtime.currentEpoch(), 1, 60, 60));
if (!page.complete()) {
    // Inspect rangeStatus(): PAGINATED, PARTIALLY_EVICTED, or NOT_YET_EXECUTED.
}
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

Close the runtime on its capture thread. Completed immutable tick pages remain queryable after
close; no new tick is accepted.
