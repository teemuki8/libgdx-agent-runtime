# Getting started

## Add dependencies

During snapshot development:

```kotlin
dependencies {
    implementation("io.github.teemuki8:agent-runtime-core:0.1.0-SNAPSHOT")
    implementation("io.github.teemuki8:agent-runtime-libgdx:0.1.0-SNAPSHOT")
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
