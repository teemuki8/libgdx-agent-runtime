# ADR 0006: Application-owned command dispatch

- Status: Accepted
- Date: 2026-08-03

## Context

Runtime queries read immutable completed frames from transport threads. Planned controls, scenario
resets, checkpoints, semantic actions, and input injection mutate application state and therefore
must execute on the application's capture thread. The library cannot assume ownership of a libGDX
loop, render thread, scheduler, or executor.

## Decision

Applications explicitly register an `ApplicationCommandDispatcher` while building a runtime. The
dispatcher receives only runtime-owned `Runnable` tasks and must enqueue them onto the configured
capture thread. Transports never receive the dispatcher or an arbitrary-code command surface.

`CommandDispatch` provides bounded queue depth, maximum timeout, terminal-result retention, expired
request-ID retention, diagnostics, stable request correlation, and at-most-once execution while an
ID is retained. Concurrent submissions enter one bounded internal queue and reach the application
dispatcher in acceptance order. It creates no threads and uses no timer. Deadlines are checked on
submission, status or cancellation access, and immediately before application-thread execution. A
deadline observed after execution starts reports a timed-out outcome as unknown until the
application task completes.

Cancellation succeeds only while a command is queued. Once application-thread execution begins,
the runtime reports the current state and does not claim to have cancelled the mutation. Dispatcher
rejection, wrong-thread execution, and application failures produce bounded diagnostics without
stack traces.

The Java task submission surface is infrastructure for closed runtime features. Protocol and MCP
extensions may submit only typed, allowlisted commands; they cannot expose a generic task, Java
method, class name, script, or expression.

## Consequences

Read-only completed-frame queries remain directly available even when the application loop is
paused. Mutating extensions are unavailable unless dispatch was explicitly registered. Future
extensions share deterministic per-session ordering and must correlate their typed results through
this boundary. The application remains responsible for draining its dispatch queue.
