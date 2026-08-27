# ADR 0015: Explicit bounded Box2D contact evidence

- Status: Accepted
- Date: 2026-08-09

## Context

Box2D 3 post-step contact arrays contain facts needed to explain collisions, but their entries,
shape IDs, contact-data buffers, manifolds, and points are native-backed scoped views. Native
delivery order is not stable application identity, and a world stepped by the runtime would violate
application loop/lifecycle ownership. A render frame cannot prove which simulation tick produced a
contact.

The existing runtime already provides explicit simulation ticks, immutable frames, registered
entities, bounded events, entity history, protocol queries, and MCP tools. Contact evidence should
use those contracts without introducing Box2D into core, protocol, or MCP.

## Decision

`runtime-core` exposes `SimulationTimelineRegistry.activeTick()`, a transient immutable context for
the timeline-owned runtime frame currently open on the capture thread. It contains the planned
simulation tick, execution epoch, epoch tick, supplied delta, source, and open runtime frame. It is
empty outside that timeline-owned frame and is not completed-tick evidence. The completed `SimulationTick`
remains authoritative for executed delta, outcome, elapsed simulation time, and final frame
correlation.

`runtime-box2d` adds one explicit `Box2dContacts` registration per registered world. Every selected
shape must enable contact events for retained begin/end facts and hit events for retained
post-solve facts. The application wraps exactly one application-owned `b2World_Step` per simulation
tick with `captureStep`; the adapter validates flags/liveness before stepping and never owns a loop,
thread, world, or native destruction.

After the step, begin, hit, and end arrays resolve only explicitly registered body/shape IDs. Values
are copied immediately into bounded immutable records. Stable identity is the lexicographically
canonical pair of application fixture IDs and child indices; it never contains a native scalar,
pointer, Java identity, discovery index, or `userData`. Canonical reversal swaps endpoint facts,
negates the world normal, and leaves world points and normal impulse magnitudes unchanged.

Begin and end records contain endpoint facts only. Hit events supply copied point and normal facts;
bounded matching contact data supplies the maximum positive `totalNormalImpulse` accumulated across
substeps and restitution. Runtime 3 has no pre-solve phase, listener, listener composition, or old
manifold model. Records, active contacts, points, impulses, diagnostics, typed history, and query
pages have independent positive hard bounds. Finalization publishes observed, retained, and limit
counters plus typed diagnostics/truncations. `complete=false` prevents incomplete evidence from
being read as proof that no contact occurred.

Unknown active state is sticky across quiet ticks for unmapped, missing-correlation, failed-step, or
bounded evidence until contact capture closes and is freshly registered for an epoch/replacement
world. Nested active-value truncation participates in completeness. Public constructors preflight
hard sizes and closed truncation dimensions before copying collections.

Each completed captured step publishes `box2d.contacts.<worldId>` and emits retained records as
`box2d.contact.begin`, `.end`, or compatible `.postSolve` events in canonical order. Canonical body
A is subject, body B is source, and frame ID is the runtime-frame correlation. Existing entity,
history, event, protocol, and MCP paths carry these values; there is no Box2D-specific protocol
command.

Contact capture is a world descendant. Scenario/world replacement closes contacts, joints, shapes,
and bodies before world rebind, then registers replacement descendants and fresh contacts. Close
releases the entity provider, manually owned contact buffer, staged state, active set, and typed
history without destroying the application world; completed runtime frames remain immutable.

Typed contact ticks are staged inside the simulation callback but enter queryable history only
after the simulation timeline confirms the resulting runtime frame. A failed frame is retained as
incomplete `MISSING_CORRELATION` evidence. Bounded history keeps an eviction watermark across deque
eviction and epoch reset, together with a bounded exact set of evicted tick IDs. Exact matches
report `PARTIALLY_EVICTED`; once exact eviction metadata is discarded, affected old ranges report
`EVICTION_UNKNOWN`. A missing tick not covered by either form of evidence remains
`NOT_YET_CAPTURED`. A disabled runtime still executes the application step while retaining no
evidence.

## Consequences

Agents can correlate selected contact facts to exact simulation ticks and runtime frames without
using screenshots or traversing native object graphs. Applications must explicitly register both
endpoints, enable required native shape event flags, and wrap every authoritative step they want
captured. An event with an unregistered endpoint exposes no partial identity.

Canonical ordering provides repeatable selected evidence for identical native library, platform,
configuration, initial state, fixed-step sequence, and scheduled input. It is not a claim of
cross-platform Box2D callback-order determinism or whole-program determinism. Event adjacency does
not establish gameplay causality; applications must emit their own semantic event or attribution
when that meaning is required.
