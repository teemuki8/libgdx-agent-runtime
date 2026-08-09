# ADR 0015: Explicit bounded Box2D contact evidence

- Status: Accepted
- Date: 2026-08-09

## Context

Box2D contact callbacks contain the facts needed to explain collisions, but their arguments are
native-backed, mutable, callback-scoped objects. Callback delivery order is not a stable application
identity, and a listener installed or a world stepped by the runtime would violate application loop
and lifecycle ownership. A render frame also cannot prove which simulation tick produced a contact.

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

`runtime-box2d` adds one explicit `Box2dContacts` registration per registered world. The application
installs either `listener()` or the evidence-first result of `compose(applicationListener)` on its
world and wraps exactly one application-owned `World.step` per simulation tick with `captureStep`.
The adapter never installs a listener, steps a world independently, creates a loop or thread, or
disposes native objects.

Callbacks resolve only explicitly registered bodies and fixtures. Their values are copied before
the native callback returns into bounded immutable records. Stable contact identity is the
lexicographically canonical pair of application fixture IDs and child indices; it never contains a
native pointer, Java identity, discovery index, or `userData`. Canonical reversal swaps endpoint
facts, negates the world normal and signed tangent impulses, preserves normal impulse magnitudes,
and leaves world points unchanged.

Begin and end records contain endpoint facts only. Pre-solve, when explicitly enabled, contains the
current world manifold and a bounded old-manifold copy. Post-solve contains the current world
manifold and bounded impulses. Unavailable values are explicit nulls or empty lists paired with a
closed availability enum, not synthetic zeroes. The default policy retains begin, end, and
post-solve and omits pre-solve.

Callback records, active contacts, points, impulses, old-manifold points, diagnostics, typed history,
and query pages have independent positive hard bounds. Finalization publishes observed, retained,
and limit counters plus typed diagnostics and truncations. `complete=false` prevents truncated,
unmapped, reset, failed, or otherwise incomplete evidence from being read as proof that no contact
occurred.

Unknown active state is sticky across quiet ticks. Outside, late, unmapped, missing-begin, or failed
step evidence remains incomplete until an execution-epoch or world baseline authoritatively clears
it. Nested active-value truncation also participates in completeness. Public evidence constructors
preflight their hard sizes and closed truncation dimensions before copying caller collections.

Each completed captured step publishes the closed entity `box2d.contacts.<worldId>` and emits the
retained callback records as `box2d.contact.begin`, `.end`, `.preSolve`, or `.postSolve` events in
canonical order. The canonical body A is the event subject, body B is the source, and the event's
frame ID is the runtime-frame correlation. Existing entity, entity-history, event, protocol, and MCP
paths carry these values; there is no Box2D-specific protocol command.

A new execution epoch clears active contacts and typed contact history before its baseline. World or
fixture rebind/unregister clears affected active evidence and publishes a closed diagnostic. A
replacement world still requires the application to install the listener explicitly. Close releases
the entity provider, composition reference, staged state, active set, and typed history without
disposing application objects; already completed runtime frames remain immutable under core
retention.

Typed contact ticks are staged inside the simulation callback but enter queryable history only
after the simulation timeline confirms the resulting runtime frame. A failed frame is retained as
incomplete `MISSING_CORRELATION` evidence. Bounded history keeps an eviction watermark across deque
eviction and epoch reset, so old ranges are never reported as not-yet-captured. A disabled runtime
still executes the application step and composed application listener while retaining no evidence.

## Consequences

Agents can correlate selected contact facts to exact simulation ticks and runtime frames without
using screenshots or traversing native object graphs. Applications must explicitly register both
endpoints, install the listener, and wrap every authoritative step they want captured. A callback
with an unregistered endpoint exposes no partial identity.

Canonical ordering provides repeatable selected evidence for identical native library, platform,
configuration, initial state, fixed-step sequence, and scheduled input. It is not a claim of
cross-platform Box2D callback-order determinism or whole-program determinism. Event adjacency does
not establish gameplay causality; applications must emit their own semantic event or attribution
when that meaning is required.
