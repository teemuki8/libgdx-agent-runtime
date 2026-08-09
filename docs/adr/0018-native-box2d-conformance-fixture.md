# ADR 0018: Native Box2D conformance fixture

## Status

Accepted for the development line.

## Context

The fixed-step facade and Box2D evidence features have focused tests, but those tests do not prove
their complete composition in one real LWJGL3 application using Box2D desktop natives.

## Decision

`runtime-fixtures` owns one unpublished application-scoped physics fixture. It uses the public
fixed-step facade, explicitly registered Box2D state and contacts, application-dispatched scenario
reset/input/control, immutable assertions, determinism, protocol, and MCP. The application owns the
world, listener, render loop, command queue, native disposal, and every rebind.

The fixture has three closed scenarios: ball drop, dynamic collision, and input-driven player
movement. Scenario reset recreates the native world, closes descendant fixture/body registrations,
rebinds the world, registers replacements under the same stable IDs, clears the fixed-step
accumulator, and lets the runtime capture the new epoch baseline. Rendering is never authoritative;
tests may execute many simulation ticks between renders.

The integrated additive protocol allocation is 2.1 timeline, 2.2 fixed-step facade, 2.3 simulation
assertions, and 2.4 simulation determinism. Earlier exact shapes remain unchanged.

## Consequences

Linux Xvfb plus actual LWJGL3/Box2D natives is the qualified conformance platform. Exact equality is
limited to selected evidence on the same native/platform configuration. The fixture is not
published and adds no production loop, thread, transport, reflection, or disposal ownership.
