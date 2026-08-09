# ADR 0014: Separate explicit Box2D inspection adapter

- Status: Accepted
- Date: 2026-08-09

## Context

Box2D objects are native-backed, mutable, and application-owned. Reflecting over a world, deriving
identifiers from object identity, or adding Box2D to core would violate the runtime evidence and
module boundaries. Requiring every game to hand-write the same entity providers is inconsistent and
error-prone.

## Decision

Add the separately publishable `runtime-box2d` module and `agent-runtime-box2d` artifact. It depends
only on `runtime-core` and libGDX Box2D. Applications explicitly register selected worlds, bodies,
fixtures, and joints under stable strings. The adapter maps them deterministically to namespaced
runtime entity IDs and exposes only closed property schemas through existing entity snapshots.

The adapter stores native objects through weak live references. Runtime entity providers copy every
primitive, vector, filter, vertex, and type-specific value during capture on the application thread.
Registration handles support explicit rebind and unregister; neither the adapter nor runtime owns or
disposes a Box2D object. Registration counts and shape vertices are hard-bounded. Shape geometry
contains its own observed/retained/limit/truncated fields because adapter truncation happens before
the value reaches core. The generic `EntityRegistry.requireProviderMutationAllowed()` guard lets an
adapter validate core's capture-thread/open-frame lifecycle immediately before changing an existing
provider target without adding adapter dependencies to core.

Physics values remain metres and radians. A required immutable transform defines positive finite
render units per metre and provides explicit conversion in both directions. No global scale or
semantic suspicious-scale heuristic is added.

## Consequences

Existing Java, protocol, MCP, assertion, recording, and determinism paths can inspect selected
physics facts without learning Box2D types. Core remains JDK-only. Applications must unregister or
rebind destroyed and recreated objects before capturing the next baseline. Protocol and MCP gain no
Box2D dependency or command union. The build qualifies publication archives but performs no
publication.
