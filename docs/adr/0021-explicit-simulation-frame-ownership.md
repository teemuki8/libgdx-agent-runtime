# ADR 0021: Explicit simulation capture ownership

- Status: Accepted
- Date: 2026-09-05

## Context

The runtime normally wraps each simulation callback in a capture frame. Gameplay's
`GameplayRuntimeBridge` instead opens and completes its own frame inside `GameWorld.step()`.
Combining them raises a nested-frame lifecycle error (runtime issue #84), blocking controlled
advancement, registered input timelines and replay for this consumer.

## Decision

Add the opt-in Java registration overload
`simulation().register(spec, SimulationFrameOwnership.CALLBACK)`. The existing overload retains
`RUNTIME` ownership and unchanged callback behavior. Registration is capture-thread confined and
must precede `start()`. Ownership applies to normal, controlled, replay and determinism ticks;
remote requests cannot select or change it.

In callback mode, registered inputs and operation-specific input preparation run before invoking
the simulation callback, outside any capture frame. This is necessary because a gameplay world
drains queued commands before its runtime-opening system executes. Handlers enqueue production
intent; gameplay systems emit attributed events after opening capture. In runtime mode these
handlers continue to run inside the runtime-owned frame.

The callback must open and complete exactly one frame with the supplied delta. The active simulation
context is present only while that frame is open, including provider capture. A second frame,
different delta, epoch reset, runtime close or nested tick is rejected. A caught ownership violation
still prevents a successful tick, as does a caught failure from `runtime.frame` application work
or capture. Missing or unfinished capture cannot claim completion. If a
callback leaves a frame open, runtime cleanup marks callback decisions failed and attempts capture,
retains honest failed tick evidence, then releases the scope. It never retries the callback,
rolls back gameplay, guesses a frame, or suppresses a nested-frame error.

## Consequences

The gameplay bridge needs no new production dependency or change. Applications register callback
ownership and use an acknowledged callback which steps their world and returns its executed delta.
Reset remains an application-owned operation between tick requests. A failed gameplay tick can
leave application state partially mutated; repairing/recreating that state remains the application's
responsibility even when runtime frame cleanup succeeds.
Persistent capture-provider failures may prevent frame completion even during cleanup. In that
case only the controlled-tick scope is released; the application must dispose/reconstruct the
runtime rather than assume that its capture frame recovered. Cancellation before dispatch prevents
execution; neither cancellation nor failure provides mid-tick rollback.

This is an additive Java integration API, not a protocol schema change. Existing protocol/MCP
advance, input-timeline, replay and determinism operations use the registered application contract
and existing typed failure outcomes. No thread, transport, reflection, second authority or
published gameplay dependency is added to runtime-core.
