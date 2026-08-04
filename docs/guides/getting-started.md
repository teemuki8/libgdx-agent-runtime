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

For the 1.0 release:

```kotlin
dependencies {
    implementation("io.github.teemuki8:agent-runtime-core:1.0.0")
    implementation("io.github.teemuki8:agent-runtime-libgdx:1.0.0")
}
```

V1 requires Java 25. It qualifies LWJGL3 desktop only; Android, iOS, and web are not release claims.

## Wrap an existing render loop

Create and register state on the render thread, call `start()`, and wrap the code whose resulting
state should form one completed frame:

```java
runtime = LibGdxAgentRuntime.builder()
        .captureThread(Thread.currentThread())
        .configuration(RuntimeConfiguration.developmentDefaults())
        .build();
registerInspectableState(runtime);
runtime.start();

// render()
runtime.frame(LibGdxTime.deltaNanos(), () -> {
    update(Gdx.graphics.getDeltaTime());
    renderGame();
});
```

`start()` captures baseline frame 0. The first wrapped frame is frame 1 and its diff compares to the
baseline. A callback exception is rethrown after the completed frame and any aborted decisions are
retained.

Close on the capture thread from `dispose()`. Completed immutable history remains readable after
close, but providers are released and no more capture is accepted.

## Disabled runtime

Use `RuntimeConfiguration.disabled()`. Registration returns no-op handles, `frame` only executes its
callback, events return an empty ID, decisions use no-op scopes, and no snapshot or serialization is
performed.
