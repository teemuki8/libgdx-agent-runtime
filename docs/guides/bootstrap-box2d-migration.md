# Bootstrap migration: deterministic Box2D games

Use this integration when `libgdx-agent-bootstrap` generates a game that owns a Box2D `World`.
The generated application remains responsible for the render loop, world lifecycle, listener,
input mutation, and disposal. The runtime supplies the accumulator and immutable evidence path.

## Generated application setup

1. Add `agent-runtime-core`, `agent-runtime-libgdx`, and `agent-runtime-box2d` with the same runtime
   version. Keep the ordinary libGDX Box2D dependency and desktop natives in the generated game.
2. Create the `AgentRuntime` on the application/render thread and dispatch commands through
   `Gdx.app.postRunnable`.
3. Register one fixed `SimulationTimelineSpec`, selected Box2D objects under stable IDs, contacts,
   registered semantic input, scenario reset, and an acknowledged fixed-step callback.
4. Call `runtime.start()` only after registration is complete.

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
delta or configuration mismatch and assert typed clamp/drop or `INVALID_QUERY` diagnostics.
