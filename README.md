# libGDX Agent Runtime

`libgdx-agent-runtime` is an early-stage Java 25 library for exposing deliberately registered,
immutable facts about a live libGDX game to Java tests and coding agents.

It answers questions such as:

- What entities and properties existed after frame 42?
- Which properties changed between completed frames?
- Which structured events did game code emit?
- Which target candidates did game code explicitly accept or reject?

It does not infer causality, inspect arbitrary objects, or run an LLM. The game chooses every
exposed property and semantic hook.

## Relationship to libGDX and the UI harness

This is an ordinary third-party library. It does not fork, patch, own, or dispose libGDX.
Applications retain control of `ApplicationListener`, update, render, input, and assets.

[`libgdx-ui-harness`](https://github.com/teemuki8/libgdx-ui-harness) addresses Scene2D UI
inspection and operation. This project addresses game state, changes, events, frames, and explicit
decisions. Neither is a dependency of the other.

## V1 scope

V1 includes registered static/dynamic entities, bounded immutable values, baseline plus frame
snapshots, automatic structural diffs, explicit events and decisions, concurrent completed-frame
queries, a strict JSON protocol, eight stdio MCP tools, and a deterministic LWJGL3 fixture.

V1 excludes replay, reflection, instrumentation, mutation interception, networking, ECS adapters,
hot reload, frame stepping, visual debugging, natural-language queries, and automatic causality.
This repository is a `0.1.0` release candidate, not a production-stability claim.

## Minimal Java integration

```java
public final class GameApplication extends ApplicationAdapter {
    private AgentRuntime runtime;
    private Enemy enemy;

    @Override public void create() {
        enemy = new Enemy("enemy-1", 100);
        runtime = LibGdxAgentRuntime.builder()
                .captureThread(Thread.currentThread())
                .configuration(RuntimeConfiguration.developmentDefaults())
                .build();
        runtime.entities().register(
                EntityId.of(enemy.id()),
                EntityType.of("enemy"),
                enemy::displayName,
                inspector -> inspector
                        .property("health", enemy::health)
                        .property("position", () ->
                                RuntimeValues.vector2(enemy.x(), enemy.y())));
        runtime.start(); // captures baseline frame 0
    }

    @Override public void render() {
        runtime.frame(LibGdxTime.deltaNanos(), () -> {
            update();
            renderGame();
        });
    }

    @Override public void dispose() {
        runtime.close();
    }
}
```

## Minimal MCP hosting

The registry is process-local because V1 has no network transport. Host the stdio server inside the
same JVM as the runtime, normally in a development launcher:

```java
PublishedRuntime publication = RuntimeRegistry.global().publish(runtime);
RuntimeMcpServer server = RuntimeMcpServer.open(
        new RuntimeProtocolService(RuntimeRegistry.global()),
        System.in,
        System.out);
```

Keep both handles for the launcher lifetime and close `server`, `publication`, then `runtime`.
Do not use stdout for game logging while it carries MCP JSON-RPC; use stderr or a file.

This repository includes a same-JVM deterministic development launcher. On Linux, an MCP client
configuration can point to:

```json
{
  "mcpServers": {
    "libgdx-runtime": {
      "command": "xvfb-run",
      "args": [
        "-a",
        "./gradlew",
        "-q",
        ":runtime-fixtures:runMcpFixture"
      ]
    }
  }
}
```

Use the same embedding pattern for an application-owned development launcher. The bundled
`runtime-mcp` main class alone can expose the tool catalog, but a separate JVM has no access to a
game running elsewhere. Remote process attachment is explicitly outside V1.

## Modules

| Module | Purpose | Published artifact |
| --- | --- | --- |
| `runtime-core` | JDK-only model, capture, retention, queries | `agent-runtime-core` |
| `runtime-libgdx` | render-thread helpers, metrics, converters | `agent-runtime-libgdx` |
| `runtime-protocol` | strict V1 JSON and session registry | `agent-runtime-protocol` |
| `runtime-mcp` | eight stdio MCP tools | `agent-runtime-mcp` |
| `runtime-fixtures` | deterministic LWJGL3 qualification | not published |

Group: `io.github.teemuki8`. Snapshot version: `0.1.0-SNAPSHOT`.

## Build

```bash
./gradlew clean check javadoc --warning-mode=fail
```

Linux CI runs the real fixture under Xvfb. Windows and macOS compile the fixture but do not create a
native graphics context on hosted runners.

## Security model

The runtime exposes only allowlisted properties registered by application code. Protocol values
form a closed union and all strings, collections, depth, frames, events, decisions, requests, and
responses are bounded. MCP uses stdio only, closed schemas, and no filesystem, shell, reflection,
class-loading, expression, script, or caller-selected network operation.

Do not register secrets, credentials, tokens, private user data, or unnecessary filesystem
information. Stdio access is equivalent to access to every property the application deliberately
publishes.

## Guides

- [Getting started](docs/guides/getting-started.md)
- [Instrumenting game state](docs/guides/instrumenting-game-state.md)
- [Decision tracing](docs/guides/decision-tracing.md)
- [Agent tools](docs/guides/agent-tools.md)
- [Releasing to Maven Central](docs/guides/releasing.md)
- [Behavioral contract](docs/design-contract.md)
- [Dependency review](docs/dependency-review.md)
- [Sonatype Central compliance](docs/sonatype-central-compliance.md)
- [Roadmap](docs/roadmap.md)

## License

Copyright 2026 Teemu Jääskeläinen.

Licensed under the [Apache License 2.0](LICENSE). `libGDX` is used descriptively; this independent
third-party project is not affiliated with, endorsed by, or sponsored by the libGDX project.
