# ADR 0001: Third-party layered agent runtime

- Status: Accepted
- Date: 2026-07-29

## Context

Agent inspection must not require a libGDX fork or leak live game objects into remote data. Core
capture, libGDX conveniences, JSON protocol, MCP transport, and executable evidence have different
dependency and lifecycle concerns.

## Decision

Use four published one-way layers and one unpublished fixture:

1. `runtime-core` is JDK-only and owns immutable values, capture, retention, queries, diagnostics,
   lifecycle, and explicit semantic instrumentation.
2. `runtime-libgdx` depends on core and supplies thread affinity, render-loop helpers, metrics, and
   `Vector2` conversion. It never owns the application or game objects.
3. `runtime-protocol` depends on core and Jackson. It owns a strict V1 command/result/error union,
   bounded JSON, and an instance-scoped session registry.
4. `runtime-mcp` depends on protocol and maps exactly eight stdio tools to protocol operations.
5. `runtime-fixtures` proves the public vertical stack with a real LWJGL3 application.

Core has no global registry, hidden thread, JSON type, MCP type, libGDX type, reflection, or
transport behavior. Protocol does not depend on MCP. The MCP adapter adds no domain behavior.

## Consequences

Applications remain in control of update, render, and disposal. Java callers can use the runtime
without JSON or MCP. UI harness integration can be added later without either library becoming a
mandatory dependency.
