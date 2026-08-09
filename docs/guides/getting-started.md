# Getting started

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

For the current 2.0 release:

```kotlin
dependencies {
    implementation("io.github.teemuki8:agent-runtime-core:2.0.0")
    implementation("io.github.teemuki8:agent-runtime-libgdx:2.0.0")
}
```

V1 requires Java 25. It qualifies LWJGL3 desktop only; Android, iOS, and web are not release claims.
The development-version Box2D adapter can be added with
`io.github.teemuki8:agent-runtime-box2d:2.0.1-SNAPSHOT`; it is not part of the published 2.0.0
release.

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
```

Register before `runtime.start()` so frame 0 contains the initial physics state. The adapter exposes
the entities as `box2d.world.main`, `box2d.body.ball`, and `box2d.fixture.ball-shape`; existing Java,
protocol, and MCP entity queries need no Box2D-specific transport command. Register joint endpoints
before their joint. For a chain fixture, supply `Box2dFixtureSpec.chainLoop(boolean)` because the
libGDX wrapper cannot reliably recover that Java-side construction choice.

The application still owns `World.step`, rendering, native destruction, and disposal. Before
destroying/recreating a selected object, close descendants as required or call the stable
registration's `rebind` method with its replacement. Close the adapter on the capture thread; it
unregisters providers and releases weak references but never disposes Box2D objects.

See [Inspect registered Box2D state](agent-cookbook.md#inspect-registered-box2d-state) for the
complete schemas, bounds, queries, and failure recipe.

## Disabled runtime

Use `RuntimeConfiguration.disabled()`. Registration returns no-op handles, `frame` only executes its
callback, events return an empty ID, decisions use no-op scopes, and no snapshot or serialization is
performed.
