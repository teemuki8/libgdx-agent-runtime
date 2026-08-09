# ADR 0017: Tick-aware determinism uses explicit immutable physics evidence

## Status

Accepted

## Context

The existing determinism API compares selected completed-frame evidence, but its divergence
position is not authoritative simulation-tick evidence. Box2D runs additionally need fixed-step,
solver, world, contact-completeness, and repeated input testimony without making core depend on
libGDX or native objects.

## Decision

Keep `DeterminismRegistry` as the only repeated-scenario execution and comparison engine. Add an
additive simulation-specific specification and result that require an application-acknowledged
fixed step, repeat a bounded script of already registered runtime inputs, validate exact
configuration facts before admission and after every reset, and correlate comparison evidence with
completed `SimulationTick` records.

`runtime-box2d` supplies only a data-only builder that compiles stable IDs and explicit world
settings into the generic core specification. Protocol 2.4 and MCP expose separate closed command
and result schemas. Existing Java and protocol determinism contracts remain unchanged.

## Consequences

- `runtime-core` remains JDK-only and reads only registered immutable evidence.
- Application callbacks remain authoritative for scenario reset, rebind, input mutation, and
  simulation stepping; the runtime creates no loop or thread.
- Fixed-step or solver conflicts are rejected before execution, while post-reset rebind/config
  failures are explicitly inconclusive.
- Equality is exact and limited to the selected observables on the same qualified environment.
- Selected event comparison excludes runtime-owned absolute epoch/tick/frame correlation fields
  while preserving epoch-relative and semantic attributes; ordinary retained events are unchanged.
- Legacy and simulation operations share bounded retention.
- Box2D native pointers, wrapper identity, rendering, and unregistered application state are never
  compared or claimed deterministic.
- Public API changes require matching cookbook updates.
