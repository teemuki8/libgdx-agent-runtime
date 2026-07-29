# ADR 0002: Explicit semantic instrumentation

- Status: Accepted
- Date: 2026-07-29

## Context

libGDX cannot infer game-specific meaning or causality from arbitrary objects. Reflection and
mutation interception would broaden the security boundary while still producing ambiguous facts.

## Decision

Games explicitly register entity/property providers, emit typed events, and open typed decision
scopes. Only registered properties are captured. Values must already be members of the closed
`RuntimeValue` hierarchy; no arbitrary object adapter or class-name input exists.

Events receive their frame ID synchronously when emitted into the currently open frame. Emission
between frames fails with a typed lifecycle error. Snapshot comparison produces only an unknown
cause. A game may explicitly associate a semantic cause, event ID, or decision ID with a property
change; the runtime never infers such correlation.

Decision scopes may not nest on the capture thread in V1. Closing normally completes a trace.
Closing during exception unwinding or leaving a scope open at frame end retains an `ABORTED` trace.
Candidates and reasons use stable codes with optional bounded descriptions.

## Consequences

Recorded facts are machine-readable and honest about attribution. Instrumentation is visible in
game code. V1 deliberately excludes reflection, bytecode instrumentation, setter interception,
natural-language explanation, replay, and generic expressions.
