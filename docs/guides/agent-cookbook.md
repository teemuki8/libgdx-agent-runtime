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

## Inspect registered Box2D state

Use this development-version API when an agent needs authoritative physics evidence without
reflection or native-pointer identities. Add `agent-runtime-box2d`, create the adapter on the
runtime capture thread, and explicitly register the useful subset before `runtime.start()`:

```java
Box2dInspection physics = new Box2dInspection(
        runtime, Box2dAdapterLimits.developmentDefaults());
Box2dRegistration<World> mainWorld = physics.registerWorld(
        "main", world, new Box2dWorldSpec(
                true, true, true, 6, 2, OptionalDouble.of(60),
                new Box2dUnitTransform(100)));
Box2dRegistration<Body> ball = physics.registerBody("ball", "main", ballBody);
Box2dRegistration<Fixture> ballShape = physics.registerFixture(
        "ball-shape", "ball", ballFixture);
```

`Box2dUnitTransform(100)` explicitly means one physics metre equals 100 application render units.
Use `physicsToRender` and `renderToPhysics` for finite scalar or `Box2dVector` conversion. The
runtime never assumes that render units are pixels and does not invent a globally correct scale.

Registration produces ordinary runtime entities with stable IDs and types:

| Kind | Runtime ID | Entity type | Closed properties |
| --- | --- | --- | --- |
| World | `box2d.world.main` | `box2d.world` | IDs, gravity, supplied world/solver settings, fixed step, registered/total body-fixture-joint counts, contact count, locked state, render scale |
| Body | `box2d.body.ball` | `box2d.body` | IDs, type, position/angle, linear/angular velocity, mass/inertia, gravity scale, damping, awake/active/bullet/fixed-rotation/sleeping flags, fixture counts |
| Fixture | `box2d.fixture.ball-shape` | `box2d.fixture` | IDs, shape type, sensor/material/filter values, geometry, diagnostics |
| Joint | `box2d.joint.spring` | `box2d.joint` | IDs/type/endpoints, anchors, active/collide-connected state, optional reaction values, type detail |

Circle geometry contains radius and local centre. Polygon and chain geometry contains a bounded
vertex prefix plus `observedVertices`, `retainedVertices`, `vertexLimit`, and `truncated`. Edge
geometry contains endpoints and optional adjacent vertices. Chain registration must supply
`Box2dFixtureSpec.chainLoop(true|false)`. A truncated polygon or chain also adds the bounded
`SHAPE_VERTICES_TRUNCATED` fixture diagnostic. Distance, revolute, and prismatic joints have closed
type-specific detail; other joint types expose only their stable native type. Reaction force and
torque are `null` unless `Box2dWorldSpec.inverseStep` explicitly supplies the value required by
Box2D's query.

Inspect the result through the existing tool:

```json
{"name":"runtime_entity","arguments":{"sessionId":"game","entityId":"box2d.body.ball","fromFrame":0,"toFrame":60,"limit":60}}
```

For a fixed-step game, capture happens after `world.step` inside the acknowledged simulation tick:

```java
runtime.simulation().tick(FIXED_STEP_NANOS, supplied -> {
    world.step((float) (supplied / 1_000_000_000.0), 6, 2);
    gameLogicAfterPhysics();
    return supplied;
});
```

`Box2dAdapterLimits` bounds worlds, bodies, fixtures, joints, copied shape vertices, the largest
closed property schema, and diagnostics. Core capture limits still apply afterward; always inspect
`EntitySnapshot.truncations()` as well as fixture diagnostics. Capacity overflow, duplicate IDs or
native wrappers, missing parents/endpoints, wrong-world relationships, missing chain-loop
testimony, wrong-thread use, and use after close fail explicitly.

The adapter stores weak native references and owns neither discovery nor lifecycle. Before replacing
a native object, remove dependent fixture/joint registrations as required and call `rebind` on the
stable registration. Close registrations from leaves to roots, or close `Box2dInspection` to remove
all providers. The adapter never calls `World.dispose`, `Shape.dispose`, or any native destroy
operation; application code remains responsible for those objects.
