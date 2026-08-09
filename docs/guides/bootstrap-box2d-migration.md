# Bootstrap migration: deterministic Box2D games

Use this integration when `libgdx-agent-bootstrap` generates a game that owns a Box2D `World`.
The generated application remains responsible for the render loop, world lifecycle, listener,
input mutation, and disposal. The runtime supplies the accumulator and immutable evidence path.

## Generated application setup

Use one runtime version and the same libGDX version for Java and native artifacts. The Box2D adapter
is currently a development snapshot, so bootstrap consumers must resolve it from a local build or
the repository configured for development; this guide does not authorize publication.

```kotlin
val agentRuntimeVersion = "2.0.1-SNAPSHOT"
val gdxVersion = "1.14.2"

dependencies {
    implementation("io.github.teemuki8:agent-runtime-core:$agentRuntimeVersion")
    implementation("io.github.teemuki8:agent-runtime-libgdx:$agentRuntimeVersion")
    implementation("io.github.teemuki8:agent-runtime-box2d:$agentRuntimeVersion")
    implementation("com.badlogicgames.gdx:gdx-box2d:$gdxVersion")

    runtimeOnly("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
    runtimeOnly("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-desktop")
}
```

Then generate these steps:

1. Create the runtime on the application/render thread and use
   `command -> Gdx.app.postRunnable(command)` as its application-owned dispatcher.
2. Register the world, selected bodies, fixtures, and joints with stable semantic IDs such as
   `main`, `player`, `player-shape`, and `player-rope`; never derive IDs from pointers or identity.
3. Register contacts, semantic input, scenario reset/checkpoint hooks, and the acknowledged
   fixed-step callback. Call `runtime.start()` only after registration is complete.

```java
AgentRuntime runtime = LibGdxAgentRuntime.builder()
        .captureThread(Thread.currentThread())
        .commandDispatcher(command -> Gdx.app.postRunnable(command))
        .build();
Box2dInspection box2d = new Box2dInspection(
        runtime, Box2dAdapterLimits.developmentDefaults());
box2d.registerWorld("main", world, new Box2dWorldSpec(
        true, true, true, 8, 3, OptionalDouble.of(60),
        new Box2dUnitTransform(100)));
box2d.registerBody("player", "main", playerBody);
box2d.registerFixture("player-shape", "player", playerFixture);
box2d.registerBody("anchor", "main", anchorBody);
box2d.registerJoint("player-rope", "main", playerRope);
Box2dContacts contacts = box2d.registerContacts(
        "main", Box2dContactLimits.developmentDefaults(),
        Box2dContactPolicy.developmentDefaults());
```

The generated callback should have this shape:

```java
LibGdxFixedStepSimulation simulation = LibGdxFixedStepSimulation.acknowledged(
        runtime,
        fixedStepConfiguration,
        tick -> {
            contacts.captureStep(() -> world.step(
                    tick.fixedStepSeconds(), velocityIterations, positionIterations));
            gameLogicAfterPhysics();
            return tick.fixedStepNanos();
        });
```

Use an explicit bounded configuration; generated games should expose these application-owned
choices alongside the Box2D world testimony:

```java
FixedStepSimulationConfiguration fixedStepConfiguration =
        new FixedStepSimulationConfiguration(
                16_666_667L, 533_333_344L, 266_666_672L, 8, 1_024,
                FixedStepDropPolicy.DROP_WHOLE_TICKS_KEEP_REMAINDER, true);
int velocityIterations = 8;
int positionIterations = 3;
```

If the game already owns a listener, compose it explicitly. The runtime evidence callback runs
first; neither registration nor `listener()` installs itself:

```java
world.setContactListener(contacts.compose(gameContactListener));
```

The generated render method stays application-owned:

```java
@Override public void render() {
    simulation.update(Gdx.graphics.getDeltaTime());
    renderGame(simulation.interpolationAlpha());
}
```

Do not emit `world.step(Gdx.graphics.getDeltaTime(), ...)`, create a worker thread, sleep, or use
render-frame count as simulation correctness evidence.

## Generated reset and input hooks

Scenario reset must recreate or restore native state before the runtime captures the new baseline.
When recreating a world, close selected descendant registrations, rebind the world, register the
replacement bodies/fixtures/joints under the same stable IDs, reinstall the contact listener, and
clear the accumulator. If registered `Box2dWorldSpec` or `Box2dFixtureSpec` testimony changes,
unregister and register again rather than preserving stale testimony.

Generated input handlers should mutate only explicit game intent or bodies on the application
thread. Agents can then schedule input for a controlled tick and use `advanceFixed`; bootstrap must
not expose a caller-selected physics delta.

## Disposal ownership

Dispose on the capture/render thread in dependency order: stop the local MCP publication/launcher
if present, detach or replace the world contact listener, close `Box2dInspection` (which closes its
contact/body/fixture/joint registrations), close the runtime, then dispose the application-owned
Box2D `World`. The runtime never destroys native objects.

## Protocol and MCP surface

Generated development launchers should expose current protocol 2.4 and advertise the additive
capabilities `simulation-timeline` (2.1), `fixed-step-simulation` (2.2),
`simulation-assertions` (2.3), and `simulation-determinism` (2.4) only when their prerequisites are
available. The copy-ready Box2D workflow uses these existing MCP tools; no Box2D-only transport is
needed:

```text
runtime_capabilities
runtime_scenarios, runtime_reset
runtime_inputs, runtime_input
runtime_control, runtime_simulation_advance
runtime_simulation, runtime_simulation_ticks
runtime_fixed_step, runtime_fixed_step_updates
runtime_entity, runtime_entity_history, runtime_events
runtime_checkpoints, runtime_checkpoint_create, runtime_checkpoint_restore
runtime_recording_start, runtime_recording_stop, runtime_recording_get
runtime_simulation_assert
runtime_simulation_determinism_check
```

MCP remains local stdio inside the application-owned development launcher. Do not generate a
network listener or claim that another JVM can inspect the live game without a separate transport.

## Verification template

Bootstrap-generated Box2D samples should copy the conformance sequence proven by
`Box2dConformanceSimulation` and `Box2dConformanceFixtureTest`:

```text
scenario reset -> pause -> scheduled input -> advanceFixed
-> Box2D step/contact capture -> immutable inspection -> assertion
-> repeated selected-evidence determinism comparison
```

Qualify Linux desktop samples under Xvfb. Require structured PASS/EQUAL evidence and explicit
tick-to-frame correlation; screenshots are supplementary only. Include one deliberate large-render-
delta or configuration mismatch and assert typed clamp/drop or `INVALID_QUERY` diagnostics. Also
inherit tests for executed-step mismatch, accumulator and catch-up loss, render/world extent
failure, shape/contact truncation, an unregistered contact endpoint, assertion `FAIL` and
`INCONCLUSIVE`, first-tick deterministic divergence, checkpoint restore, recording, structured
joint evidence, and post-solve points/normal/impulses.
