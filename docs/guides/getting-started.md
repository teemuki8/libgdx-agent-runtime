# Getting started

For task-oriented integrations, start with the
[agent cookbook task index](agent-cookbook.md#task-index). Its linked `runtime-examples` sources
compile and run as ordinary consumers: basic state inspection, a complete controlled workflow,
same-JVM stdio MCP, and deterministic actual-native Box2D. The module is repository scaffolding,
not a published dependency.

## Linux development prerequisite

Install `xvfb-run` before running the repository's native fixture or bundled headless MCP launcher:

```bash
# Fedora or Nobara
sudo dnf install xorg-x11-server-Xvfb

# Debian or Ubuntu
sudo apt-get install xvfb
```

Xvfb is required for Linux headless development workflows, not for a game that embeds the runtime
inside its existing libGDX process and display session.

## Add dependencies

For the current 2.1 release:

```kotlin
dependencies {
    implementation("io.github.teemuki8:agent-runtime-core:2.1.0")
    implementation("io.github.teemuki8:agent-runtime-libgdx:2.1.0")
}
```

V1 requires Java 25. It qualifies LWJGL3 desktop only; Android, iOS, and web are not release claims.
The optional Box2D adapter is published separately as
`io.github.teemuki8:agent-runtime-box2d:2.1.0`; add it only when the game uses Box2D inspection,
contact evidence, or the data-only physics assertion/determinism factories.

## Capture a fixed-step simulation

Create and register state on the render thread, declare the authoritative fixed step, and call
`start()`. The application calls the canonical accumulator and still owns the render loop:

```java
runtime = LibGdxAgentRuntime.builder()
        .captureThread(Thread.currentThread())
        .configuration(RuntimeConfiguration.developmentDefaults())
        .build();
FixedStepSimulationConfiguration fixedStep =
        FixedStepSimulationConfiguration.developmentDefaults(16_666_667L);
LibGdxFixedStepSimulation simulation = LibGdxFixedStepSimulation.acknowledged(
        runtime, fixedStep, tick -> {
            updateFixedStep(tick.fixedStepSeconds());
            return tick.fixedStepNanos(); // explicit application testimony
        });
registerInspectableState(runtime);
runtime.start();

// render()
simulation.update(Gdx.graphics.getDeltaTime());
renderGame(simulation.interpolationAlpha());
```

`start()` captures baseline frame 0, which is not a simulation tick. The first tick has session ID
1, epoch tick 1, and normally correlates to runtime frame 1. The callback return is the delta the
application actually executed; a mismatch with the supplied or configured step is typed evidence,
not a successful fixed-step claim. A callback exception is rethrown after retaining honest attempted
tick and any completed frame evidence.

The helper clamps render time, bounds accumulated time, limits catch-up ticks, and reports every
dropped nanosecond and whole tick. It never calls render, sleeps, starts a thread, or changes
authoritative state when interpolation alpha is read. Call `clearAccumulator()` from an
application-owned scenario-reset/checkpoint-restore callback, or restore an explicit sub-step
remainder with `restoreAccumulator(...)`.

`FixedStepFixtureApplication` exercises this exact facade from a real hidden LWJGL3
`ApplicationAdapter.render()` loop and verifies paused configured-step advancement under Xvfb.

State-driven applications that do not have a simulation timeline may continue to use
`runtime.frame(deltaNanos, callback)` directly. A runtime frame is capture evidence, not proof of a
simulation step.

Close on the capture thread from `dispose()`. Completed immutable history remains readable after
close, but providers are released and no more capture is accepted.

To make registered values visible to the UI harness, record one explicit frame correlation per
rendered frame — see [Frame correlation](frame-correlation.md).

See the [agent cookbook](agent-cookbook.md#fixed-step-simulation-ticks) for inspection and failure
recipes.

## Register selected Box2D state

Create `Box2dInspection` on the runtime capture thread and register only the native objects an agent
should see. IDs are application-owned and remain stable across native object replacement:

```java
box2d = new Box2dInspection(runtime, Box2dAdapterLimits.developmentDefaults());
box2d.registerWorld("main", world, new Box2dWorldSpec(
        true, true, true, 6, 2, OptionalDouble.of(60),
        new Box2dUnitTransform(100))); // 100 render units per physics metre
ballRegistration = box2d.registerBody("ball", "main", ballBody);
box2d.registerFixture("ball-shape", "ball", ballFixture);
contacts = box2d.registerContacts(
        "main",
        Box2dContactLimits.developmentDefaults(),
        Box2dContactPolicy.developmentDefaults());
world.setContactListener(contacts.listener()); // explicit application-owned installation
```

Register before `runtime.start()` so frame 0 contains the initial physics state. The adapter exposes
the entities as `box2d.world.main`, `box2d.body.ball`, and `box2d.fixture.ball-shape`; existing Java,
protocol, and MCP entity queries need no Box2D-specific transport command. Register joint endpoints
before their joint. For a chain fixture, supply `Box2dFixtureSpec.chainLoop(boolean)` because the
libGDX wrapper cannot reliably recover that Java-side construction choice.

Capture the application-owned step inside the acknowledged fixed-step callback. Do not also call
`world.step` from render delta:

```java
runtime.simulation().tick(16_666_667L, suppliedDeltaNanos -> {
    contacts.captureStep(() -> world.step(
            suppliedDeltaNanos / 1_000_000_000f, 6, 2));
    gameLogicAfterPhysics();
    return suppliedDeltaNanos;
});
```

Registration and `listener()` never install anything on the native world. If the game already has a
listener, install `contacts.compose(gameContactListener)` instead; evidence runs first and the
application listener runs second. The default policy retains begin, end, and post-solve evidence and
omits pre-solve. Contact state appears as `box2d.contacts.main`, while retained callbacks appear as
`box2d.contact.*` events through the existing entity/history/event queries.

The application still owns `World.step`, rendering, native destruction, and disposal. Before
destroying/recreating a selected object, close descendants as required or call the stable
registration's `rebind` method with its replacement. Close the adapter on the capture thread; it
unregisters providers and releases weak references but never disposes Box2D objects.

See [Inspect registered Box2D state](agent-cookbook.md#inspect-registered-box2d-state) for the
registered-object schemas. See [Capture and inspect Box2D contacts](agent-cookbook.md#capture-and-inspect-box2d-contacts)
for the complete contact API, exact schemas, bounds, queries, lifecycle, and failure recipes.

## Assert physics over exact ticks

Build a data-only assertion after the ticks have completed. Evaluation reads retained snapshots;
it never consults the live Box2D body:

```java
SimulationAssertionSpec expectedRest = Box2dAssertions.bodyStopped("ball", 0.01, 0.01);
SimulationAssertionResult result = runtime.assertions().evaluateSimulation(
        expectedRest,
        new SimulationAssertionScope(runtime.currentEpoch(), 1, 60, 8));
```

Use `Box2dAssertions.contactOccurred` or `contactDidNotOccur` with stable
`ContactEndpoint(bodyId, fixtureId, childIndex)` values. Contact factories automatically require
the registered contact entity's `complete` flag. Missing ticks, failed capture, eviction,
truncation, missing frame correlation, or incomplete contact evidence yields `INCONCLUSIVE` when it
could otherwise create a misleading PASS.

Protocol 2.3 exposes `simulationAssert`; MCP exposes the equivalent closed
`runtime_simulation_assert` tool. See
[Assert physics over exact simulation ticks](agent-cookbook.md#assert-physics-over-exact-simulation-ticks)
for every factory, exact tags, request examples, bounds, and result semantics.

## Compare repeated physics runs

For a deterministic rerun, register the controller with `acknowledgedTick`, make scenario reset
restore and rebind every selected Box2D registration before its new baseline, then build a
data-only request:

```java
SimulationDeterminismSpec spec = Box2dDeterminism.builder(
        "main",
        new Box2dDeterminism.WorldSettings(16_666_667L,
                new Box2dVector(0, -9.8), 6, 2, true, true, true),
        "ball-drop", 7, RuntimeValues.object(), 2, 60)
        .body("ball", "position", "linearVelocity", "awake")
        .activeContacts()
        .build();
runtime.determinism().checkSimulation(
        spec, "ball-drop-repeat", Duration.ofSeconds(5));
```

The application-owned dispatcher executes the operation while paused. Poll with the identical
specification and request ID. `EQUAL` is limited to the selected evidence; any failed, missing,
uncorrelated, truncated, evicted, or explicitly incomplete relevant tick yields `INCONCLUSIVE`.
Protocol 2.4 and `runtime_simulation_determinism_check` expose the same bounded contract. See
[Compare deterministic Box2D runs](agent-cookbook.md#compare-deterministic-box2d-runs) for the
complete Java, protocol, MCP, schema, and failure recipes.

For a complete actual-native example, see
[Run the actual-native Box2D conformance recipe](agent-cookbook.md#run-the-actual-native-box2d-conformance-recipe).
Bootstrap generators should follow
[Bootstrap migration: deterministic Box2D games](bootstrap-box2d-migration.md).

## Disabled runtime

Use `RuntimeConfiguration.disabled()`. Registration returns no-op handles, `frame` only executes its
callback, events return an empty ID, decisions use no-op scopes, and no snapshot or serialization is
performed.
