# Issue 68 bounded Box2D contact evidence design

- Status: Approved by issue #68 and the instruction to continue
- Date: 2026-08-09
- Base: issue #67 / PR #76

## Decision summary

Add a generic transient active-tick context to `runtime-core` and a separately bounded contact
capture in `runtime-box2d`. Applications explicitly install or compose the adapter listener and
explicitly wrap their own `World.step`. The adapter copies callback facts immediately, completes
one immutable contact snapshot before the runtime frame closes, emits bounded structured events,
and retains a bounded Java contact-tick history. Existing entity, event, frame-history, protocol,
and MCP paths carry the evidence without adding Box2D dependencies outside `runtime-box2d`.

## Considered approaches

1. **Explicit step capture plus a transient core tick context (selected).** The application calls
   `contacts.captureStep(() -> world.step(...))` inside the existing simulation callback. This
   provides an exact callback boundary, lets the adapter sort and emit records while the runtime
   frame remains open, and makes incomplete or missing integration diagnosable. Core learns only a
   reusable immutable active-tick value.
2. **Emit directly from every native callback.** This is simpler, but runtime event retention would
   select callback-order prefixes before contact records can be canonically ordered, and there is
   no reliable point at which to publish a completed active-contact snapshot.
3. **Add generic simulation observers to core.** Begin/end observers could automate finalization,
   but they would introduce callback registration, lifecycle, failure, and retention contracts for
   a single adapter use case. The explicit step boundary is smaller and keeps scheduling in the
   application.

## Core tick context

`SimulationTimelineRegistry.activeTick()` returns an immutable optional context containing the
planned session tick ID, execution epoch, epoch-relative tick, supplied delta, source, and actual
open runtime frame ID. It is present only on the capture thread while a timeline-owned simulation
frame is open. It is cleared in all success and failure paths before the tick call returns and is
never retained as authoritative completed evidence. Completed `SimulationTick` remains the source
of truth for outcome, executed delta, simulation time, and final frame correlation.

The context is JDK-only. It adds no worker, listener registry, scheduler, or Box2D dependency.
Wrong-thread access and access outside an open timeline tick return an empty optional rather than
guessing from the latest completed tick.

## Contact integration API

`Box2dInspection.registerContacts(worldId, limits, policy)` explicitly attaches one contact
capture to an already registered world and returns an application-owned `Box2dContacts` handle.
The handle exposes:

- `listener()` for an application that has no other listener;
- `compose(ContactListener applicationListener)` for evidence-first, application-second
  delegation;
- `captureStep(Runnable worldStep)` as the canonical wrapper;
- stable world/contact runtime entity IDs, configured limits and policy, bounded typed history,
  and idempotent close.

Neither registration nor `listener()` calls `World.setContactListener`. The application makes the
installation explicit. Composition invokes the adapter first and the application listener second.
An application listener exception is recorded as a closed diagnostic code and then rethrown, so
the simulation timeline cannot report that tick as successful. Raw exception messages and stack
traces are never copied.

`captureStep` requires the owner thread, an open active simulation tick for the registered world,
and at most one captured Box2D step for that world in the tick. It stages callbacks, invokes the
application-owned step, finalizes evidence in a `finally` path, emits retained events in canonical
order, and rethrows a step/listener failure. Direct callbacks outside an active capture record only
a bounded diagnostic counter and copy no native endpoint details. Use after close fails with a
stable lifecycle error.

## Immediate copy and stable identity

The listener resolves both fixtures and both bodies only through issue #67 registrations. A contact
with any unregistered endpoint contributes to a saturating unmapped counter and a bounded diagnostic
but exposes no pointer, object identity, enumeration index, `userData`, or partial endpoint detail.

The stable key is the canonical pair `(fixtureId, childIndex)`. Endpoint A is the lexicographically
smaller pair. When native A/B is reversed, body, fixture, child, and sensor facts are swapped; the
world normal is negated; normal impulse magnitudes are unchanged; and signed tangent impulses are
negated. World points do not change. The adapter never exposes a native contact address.

Every immutable record and active-contact value also exposes a combined `sensor` fact derived from
the two immediately copied endpoint sensor flags. It is true when either endpoint is a sensor; the
per-endpoint flags remain available so the source of that state is not lost.

Every callback copies allowed primitive and vector values before returning. It never retains a
`Contact`, `ContactImpulse`, `Manifold`, `WorldManifold`, `Fixture`, `Body`, libGDX vector, or native
array. Phase availability is closed and explicit:

- begin/end: endpoints, sensors, touching, and enabled only;
- pre-solve when enabled: current world points/normal plus bounded old-manifold type, point IDs,
  and prior impulse values; no ambiguous native-local directional coordinates;
- post-solve: current world points/normal and bounded normal/tangent impulses;
- unavailable fields are null with an availability code, never synthetic zero values.

## Bounds, ordering, and history

`Box2dContactLimits` has positive hard limits for callback records per tick, active contacts,
points, impulses, old-manifold points, diagnostics, and retained contact ticks. The default policy
retains begin, end, and post-solve records; pre-solve is disabled. Active-contact maintenance runs
even when a callback phase is not retained.

Callbacks retain only the configured prefix of native delivery; each retained callback is already
individually bounded. Finalization sorts that bounded prefix by phase, stable endpoint pairs, child
indices, and bounded occurrence. It reports saturating observed, retained, and limit counts. This
guarantees repeatability for identical native library, platform, configuration, initial state, and
input; it does not claim cross-platform Box2D callback-order determinism.

The active set uses a bounded ordered selection of stable keys. If the adapter cannot reconstruct an
unretained active key after a retained contact ends, the snapshot remains explicitly incomplete.
Negative consumers must never treat truncated or incomplete active evidence as “no contact.”
Outside, late, unmapped, missing-begin, and failed-step callbacks taint the active-set testimony
across later quiet ticks; only an authoritative epoch or replacement-world baseline clears it.
Nested active/record truncations are part of tick completeness.

Each finalized `Box2dContactTick` contains the tick/frame/epoch correlation, sorted records, sorted
active contacts, counters, diagnostics, and truncations. Captured ticks remain pending until the
simulation timeline confirms their resulting frame; capture failure produces
`MISSING_CORRELATION` rather than a phantom completed frame. A bounded deque retains typed Java
history and a bounded exact set of evicted tick IDs across capacity eviction and epoch reset. If
that eviction metadata is itself discarded, queries report `EVICTION_UNKNOWN`; they never
misclassify a never-captured gap as evicted or claim that discarded evidence was not yet captured.
The registered `box2d.contacts` runtime entity exposes the current completed contact tick and active
set through a closed `RuntimeValue` schema, so existing immutable frame and entity-history queries
provide protocol/MCP history as well. Runtime frame eviction remains explicit in those generic
queries; Box2D history eviction is explicit in the typed Java page.

## Events and entity schema

Finalization emits these existing-runtime events for retained callbacks:

- `box2d.contact.begin`
- `box2d.contact.end`
- `box2d.contact.preSolve`
- `box2d.contact.postSolve`

The canonical body A is the subject and body B is the source. Closed attributes contain the contact
key, fixture IDs, child indices, per-endpoint and combined sensor states, touching/enabled values,
phase availability, bounded points/normal/manifold/impulses, simulation tick ID, epoch tick, and
execution epoch. The event's own frame ID is the runtime-frame correlation. No gameplay causality
is inferred.

The `box2d.contacts` entity exposes exact properties for world ID, policy, configured limits,
latest tick/frame/epoch correlation, current records, active contacts, observed/retained/limit
counters, unmapped contacts, diagnostics, truncation, and completeness. The cookbook documents the
complete property and nested object schemas plus Java, protocol, and MCP recipes.

## Reset, rebind, and close

The provider compares its last epoch with `AgentRuntime.currentEpoch()` during baseline capture.
A new scenario/checkpoint epoch clears staged callbacks and active contacts before publishing the
baseline. World rebind also clears them and requires the application to install the same explicit
listener on the replacement world. Fixture rebind/unregister removes affected retained active keys
and marks the next evidence incomplete; it never silently preserves a possibly stale native contact.

Contact capture closes before its parent inspection registrations. Close releases the provider,
listener composition references, bounded history, staged values, and native weak references without
disposing a world or fixture. Completed runtime frames remain immutable and queryable under core
retention.

## Verification

Focused real-native tests cover begin/end/pre/post, sensors, chain child indices, reversed native
orientation, phase absence, stable order, every hard bound, unmapped endpoints, callbacks outside a
tick, application listener failure propagation, epoch reset, world/fixture rebind, close, and
post-callback immutability. A fixture proves Java entity/event evidence and protocol/MCP queries over
an actual Box2D step. Verification finishes with repository checks, warning-free Javadocs, core
`jdeps`, and the Linux Xvfb full gate. No staging or publication task runs.
