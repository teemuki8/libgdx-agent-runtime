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

## Capture a fixed-step simulation

Create and register state on the render thread, declare the authoritative fixed step, and call
`start()`. The application still owns its accumulator and render loop:

```java
runtime = LibGdxAgentRuntime.builder()
        .captureThread(Thread.currentThread())
        .configuration(RuntimeConfiguration.developmentDefaults())
        .build();
runtime.simulation().register(SimulationTimelineSpec.fixedStep(16_666_667L));
registerInspectableState(runtime);
runtime.start();

// render()
accumulatorNanos += boundedRenderDeltaNanos();
while (accumulatorNanos >= 16_666_667L) {
    runtime.simulation().tick(16_666_667L, suppliedDeltaNanos -> {
        updateFixedStep(suppliedDeltaNanos);
        return 16_666_667L; // explicit application testimony
    });
    accumulatorNanos -= 16_666_667L;
}
renderGame();
```

`start()` captures baseline frame 0, which is not a simulation tick. The first tick has session ID
1, epoch tick 1, and normally correlates to runtime frame 1. The callback return is the delta the
application actually executed; a mismatch with the supplied or configured step is typed evidence,
not a successful fixed-step claim. A callback exception is rethrown after retaining honest attempted
tick and any completed frame evidence.

State-driven applications that do not have a simulation timeline may continue to use
`runtime.frame(deltaNanos, callback)` directly. A runtime frame is capture evidence, not proof of a
simulation step.

Close on the capture thread from `dispose()`. Completed immutable history remains readable after
close, but providers are released and no more capture is accepted.

To make registered values visible to the UI harness, record one explicit frame correlation per
rendered frame — see [Frame correlation](frame-correlation.md).

See the [agent cookbook](agent-cookbook.md#fixed-step-simulation-ticks) for inspection and failure
recipes.

## Disabled runtime

Use `RuntimeConfiguration.disabled()`. Registration returns no-op handles, `frame` only executes its
callback, events return an empty ID, decisions use no-op scopes, and no snapshot or serialization is
performed.
