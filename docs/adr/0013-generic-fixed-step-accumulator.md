# ADR 0013: Generic fixed-step accumulator with a libGDX facade

- Status: Accepted
- Date: 2026-08-09

## Context

The simulation timeline records explicit ticks but does not decide when normal render-loop time
produces a tick. Keeping the accumulator only in `runtime-libgdx` would prevent the JDK-only
protocol layer from inspecting clamp/drop reports. Putting libGDX types in core would violate the
module boundary.

## Decision

Implement integer-nanosecond accumulator bookkeeping, immutable bounded update reports, and exact
configured-step control in `runtime-core`. The helper remains application-driven: it creates no
thread, timer, scheduler, sleep, render loop, render call, or disposal behavior. It delegates every
simulation mutation and capture to the #65 tick boundary.

Add a thin `runtime-libgdx` facade for finite float render-delta conversion and one canonical float
fixed-step value. The facade contains no second accumulator or evidence store.

Use protocol 2.2 for fixed-step state, update-report inspection, and exact configured-step advance.
Do not change the closed protocol 2.1 union or the existing caller-delta `runtime_advance` command.

## Consequences

Applications and bootstrap obtain one canonical loop, while agents can diagnose clamped render
time, accumulated-time loss, catch-up drops, partial failures, and tick/frame correlation without
screenshots. Core remains reusable and JDK-only. The application must still explicitly acknowledge
the executed delta; only a future direct Box2D adapter can provide stronger `World.step` evidence.
