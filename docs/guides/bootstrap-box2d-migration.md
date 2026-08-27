# Bootstrap migration: deterministic Box2D games

Use this integration when `libgdx-agent-bootstrap` generates a game that owns a Box2D 3
`b2WorldId`. The generated application remains responsible for rendering, world stepping, input
mutation, and native destruction. The runtime supplies the accumulator and immutable evidence path.

## Generated application setup

Use runtime 3.0 with libGDX core 1.14.2 and the independently versioned official Box2D binding:

```kotlin
val agentRuntimeVersion = "3.0.0"
val gdxVersion = "1.14.2"
val box2dVersion = "3.1.1-0"

dependencies {
    implementation("io.github.teemuki8:agent-runtime-core:$agentRuntimeVersion")
    implementation("io.github.teemuki8:agent-runtime-libgdx:$agentRuntimeVersion")
    implementation("io.github.teemuki8:agent-runtime-box2d:$agentRuntimeVersion")
    implementation("com.badlogicgames.gdx:gdx-box2d:$box2dVersion")
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
    runtimeOnly("com.badlogicgames.gdx:gdx-box2d-platform:$box2dVersion:natives-desktop")
}
```

Create definitions and IDs on the render thread, then register stable semantic IDs:

```java
Box2d.initialize();
Box2dInspection box2d = new Box2dInspection(
        runtime, Box2dAdapterLimits.developmentDefaults());
Box2dWorldSpec worldSpec = new Box2dWorldSpec(4, new Box2dUnitTransform(100));
box2d.registerWorld("main", world, worldSpec);
box2d.registerBody("player", "main", playerBody);
box2d.registerShape(
        "player-shape", "player", playerShape, Box2dShapeSpec.defaults());
box2d.registerBody("anchor", "main", anchorBody);
box2d.registerJoint("player-rope", "main", playerRope);
Box2dContacts contacts = box2d.registerContacts(
        "main", Box2dContactLimits.developmentDefaults(),
        Box2dContactPolicy.developmentDefaults());
```

There is no contact listener. Capture exactly one post-step event array inside the acknowledged
fixed-step callback:

```java
LibGdxFixedStepSimulation simulation = LibGdxFixedStepSimulation.acknowledged(
        runtime, fixedStepConfiguration, tick -> {
            contacts.captureStep(() -> Box2d.b2World_Step(
                    world, tick.fixedStepSeconds(), worldSpec.subStepCount()));
            gameLogicAfterPhysics();
            return tick.fixedStepNanos();
        });
```

The generated render method remains application-owned:

```java
@Override public void render() {
    simulation.update(Gdx.graphics.getDeltaTime());
    renderGame(simulation.interpolationAlpha());
}
```

Do not step from render delta directly, create a worker thread, sleep, retain event pointers, or use
render-frame count as simulation correctness evidence.

## Generated reset and input hooks

Scenario reset must recreate or restore native state before the runtime captures the new baseline.
When recreating a world, close contact capture and selected descendant registrations, rebind the
world, register replacement body/shape/joint IDs under the same stable IDs, register fresh contact
capture, and clear the accumulator. If `Box2dWorldSpec` or `Box2dShapeSpec` changes, unregister and
register again rather than preserving stale testimony.

Generated input handlers should mutate only explicit game intent or bodies on the application
thread. Agents can then schedule input for a controlled tick and use `advanceFixed`; bootstrap must
not expose a caller-selected physics delta.

## Disposal ownership

Dispose on the capture/render thread in dependency order: stop the local MCP publication/launcher,
close contacts and `Box2dInspection`, close the runtime, then destroy application-owned joints,
shapes, bodies, and world. The runtime never destroys native resources.

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
