# ADR 0004: Capture-thread ownership

- Status: Accepted
- Date: 2026-07-29

## Context

Most live libGDX state is render-thread confined, while agents and tests query from other threads.
Implicit scheduling would hide latency and application lifecycle control.

## Decision

An `AgentRuntime` captures the thread that builds it unless configured otherwise. `start`,
registration, unregistration, `beginFrame`, events, decisions, `endFrame`, and close must run on
that thread. V1 does not expose an off-thread or thread-safe-provider capture path; all providers
are evaluated during capture to keep one deterministic ordering rule.

Completed immutable frames and query APIs may be read concurrently from any thread. No core thread
is created. The libGDX adapter provides a guard and render-loop wrapper, not a scheduler.

`start()` captures frame zero as a baseline. `beginFrame` twice, nested frames, end without begin,
events or decisions outside a frame, and closing during a frame are typed programmer errors. The
exception-safe `frame` helper always attempts `endFrame`; callback failure is rethrown after the
completed frame is retained. Open decisions are marked aborted at frame end.

After close, retained completed snapshots remain queryable and status is `CLOSED`; no further
capture or registration is allowed. Publication does not own or keep a closed runtime strongly
reachable. Duplicate publication IDs fail. Unsupported protocol major/minor versions return
`PROTOCOL_VERSION_UNSUPPORTED` without executing a command.

## Consequences

Thread ownership is predictable and testable. External clients only observe completed frames.
Games choose where capture sits around update/render and pay no hidden scheduling cost.
