# Agent cookbook

This cookbook contains task-oriented, versioned recipes for coding agents integrating or operating
the runtime. Every public Java API, protocol/MCP contract, dependency, or agent-visible behavior
change must update its affected recipe in the same pull request. Examples are exercised by the
repository fixture tests.

The simulation timeline APIs and protocol 2.1 described below are development-version APIs until a
release containing them is published. The current published 2.0.0 artifacts do not contain them.

## Fixed-step simulation ticks

Use this when game correctness depends on Box2D or another authoritative fixed-step simulation.
Rendering stays outside the tick callback.

```java
private static final long FIXED_STEP_NANOS = 16_666_667L;

runtime = LibGdxAgentRuntime.builder()
        .captureThread(Thread.currentThread())
        .commandDispatcher(Gdx.app::postRunnable)
        .build();
runtime.simulation().register(SimulationTimelineSpec.fixedStep(FIXED_STEP_NANOS));
registerInspectableState(runtime);
runtime.start();

// Called by the application-owned accumulator or controlled scheduler.
runtime.simulation().tick(FIXED_STEP_NANOS, suppliedDeltaNanos -> {
    processGameInput();
    updateSimulation(suppliedDeltaNanos);
    return FIXED_STEP_NANOS; // the delta actually executed
});

renderGame();
```

Success evidence has distinct identities and timing:

```text
simulationTickId: 1
executionEpochId: 0
epochTick: 1
configuredFixedStepNanos: 16666667
runtimeSuppliedDeltaNanos: 16666667
executedDeltaNanos: 16666667
epochSimulationTimeNanos: 16666667
resultingFrameId: 1
source: RUNNING
outcome: COMPLETED
```

Do not use `FrameSnapshot.deltaNanos` as proof that simulation executed that delta. A runtime frame
is observation evidence. The callback return is explicit application testimony. If it reports
`33333334` while the fixed step is `16666667`, the tick outcome is `DELTA_MISMATCH` and the
diagnostic names the supplied/configured mismatch.

For controlled stepping, prefer the acknowledged callback while preserving application-owned pause
and dispatch:

```java
runtime.controls().register(SimulationControllerSpec.builder()
        .pause(() -> paused = true)
        .resume(() -> paused = false)
        .acknowledgedTick(deltaNanos -> {
            updateSimulation(deltaNanos);
            return deltaNanos;
        })
        .build());
```

The legacy `.tick(LongConsumer)` form remains source compatible but produces `UNACKNOWLEDGED`
timeline entries because the runtime cannot infer the executed delta.

### Inspect through Java

```java
SimulationState state = runtime.simulation().state();
SimulationTickPage page = runtime.simulation().ticks(new SimulationTickQuery(
        runtime.currentEpoch(), 1, 60, 60));
if (!page.complete()) {
    // Inspect rangeStatus(): PAGINATED, PARTIALLY_EVICTED, or NOT_YET_EXECUTED.
}
```

Tick queries are safe from any thread after ticks complete. Mutation, registration, and tick
execution remain capture-thread owned. A scenario reset or checkpoint restore creates a new epoch;
its baseline is not a tick, its first tick is epoch tick 1, and session tick IDs are not reused.

### Inspect through MCP

Request current state:

```json
{"name":"runtime_simulation","arguments":{"sessionId":"game"}}
```

Request an inclusive bounded range:

```json
{"name":"runtime_simulation_ticks","arguments":{"sessionId":"game","executionEpochId":0,"fromEpochTick":1,"toEpochTick":60,"limit":60}}
```

Both tools have closed inputs and use exact protocol 2.1. Protocol 2.0 rejects the commands. A
callback failure may leave application mutation unknown and may still have a completed resulting
frame; inspect both `outcome` and `mutationOutcome` before deciding whether retry/reset is safe.

Close the runtime on its capture thread. Completed immutable tick pages remain queryable after
close; no new tick is accepted.

## Inspect registered Box2D state

Use this development-version API when an agent needs authoritative physics evidence without
reflection or native-pointer identities. Add `agent-runtime-box2d`, create the adapter on the
runtime capture thread, and explicitly register the useful subset before `runtime.start()`:

```java
Box2dInspection physics = new Box2dInspection(
        runtime, Box2dAdapterLimits.developmentDefaults());
Box2dRegistration<World> mainWorld = physics.registerWorld(
        "main", world, new Box2dWorldSpec(
                true, true, true, 6, 2, OptionalDouble.of(60),
                new Box2dUnitTransform(100)));
Box2dRegistration<Body> ball = physics.registerBody("ball", "main", ballBody);
Box2dRegistration<Fixture> ballShape = physics.registerFixture(
        "ball-shape", "ball", ballFixture);
Box2dRegistration<Joint> spring = physics.registerJoint(
        "spring", "main", springJoint); // register both endpoint bodies first
```

`Box2dUnitTransform(100)` explicitly means one physics metre equals 100 application render units.
Use `physicsToRender` and `renderToPhysics` for finite scalar or `Box2dVector` conversion. The
runtime never assumes that render units are pixels and does not invent a globally correct scale.

Registration produces ordinary runtime entities with stable IDs and exact closed property sets:

```text
box2d.world.<id> / box2d.world
  id, runtimeEntityId, gravity, sleepingAllowed, warmStarting, continuousPhysics,
  velocityIterations, positionIterations, fixedStepNanos,
  registeredBodyCount, registeredFixtureCount, registeredJointCount,
  totalBodyCount, totalFixtureCount, totalJointCount, totalContactCount,
  locked, renderUnitsPerMeter

box2d.body.<id> / box2d.body
  id, runtimeEntityId, worldId, bodyType, position, angleRadians,
  linearVelocity, angularVelocity, mass, inertia, gravityScale,
  linearDamping, angularDamping, awake, active, bullet, fixedRotation,
  sleepingAllowed, registeredFixtureCount, totalFixtureCount

box2d.fixture.<id> / box2d.fixture
  id, runtimeEntityId, bodyId, shapeType, sensor, density, friction,
  restitution, categoryBits, maskBits, groupIndex, geometry, diagnostics

box2d.joint.<id> / box2d.joint
  id, runtimeEntityId, worldId, jointType, bodyAId, bodyBId,
  anchorA, anchorB, active, collideConnected, reactionForce,
  reactionTorque, detail
```

The closed `geometry` variants are:

```text
CIRCLE:  type, radius, localCenter
POLYGON: type, vertices, observedVertices, retainedVertices, vertexLimit, truncated
EDGE:    type, endpoint1, endpoint2, hasAdjacent0, adjacent0,
         hasAdjacent3, adjacent3
CHAIN:   type, vertices, observedVertices, retainedVertices, vertexLimit, truncated, loop
```

`vertices` is the ordered bounded native prefix. Missing edge-adjacent vertices are explicit
`null`. Chain registration must supply `Box2dFixtureSpec.chainLoop(true|false)`. A truncated polygon
or chain also adds `SHAPE_VERTICES_TRUNCATED` to the bounded fixture `diagnostics` list.

The joint `detail` object is also closed:

```text
DISTANCE:  type, localAnchorA, localAnchorB, length, frequency, dampingRatio
REVOLUTE:  type, localAnchorA, localAnchorB, referenceAngle, jointAngle, jointSpeed,
           limitEnabled, lowerLimit, upperLimit, motorEnabled, motorSpeed, maxMotorTorque
PRISMATIC: type, localAnchorA, localAnchorB, localAxisA, referenceAngle, translation,
           jointSpeed, limitEnabled, lowerLimit, upperLimit, motorEnabled, motorSpeed,
           maxMotorForce
GENERIC:   type, nativeJointType
```

Reaction force and torque are runtime `null` unless `Box2dWorldSpec.inverseStep` explicitly supplies
a positive finite value representable by Box2D's float API.

Inspect the result through the existing tool:

```json
{"name":"runtime_entity","arguments":{"sessionId":"game","entityId":"box2d.body.ball","fromFrame":0,"toFrame":60,"limit":60}}
```

A representative structured `latest` fragment is:

```json
{
  "id": {"value": "box2d.body.ball"},
  "type": {"value": "box2d.body"},
  "properties": [
    {"name": "active", "value": {"valueType": "boolean", "value": true}},
    {"name": "angleRadians", "value": {"valueType": "decimal", "value": 0}},
    {"name": "bodyType", "value": {"valueType": "enum", "value": "DYNAMIC"}},
    {"name": "position", "value": {
      "valueType": "vector2",
      "x": {"valueType": "decimal", "value": 5},
      "y": {"valueType": "decimal", "value": 0.51}
    }}
  ],
  "truncations": []
}
```

Properties are sorted by name in actual responses; the fragment omits unchanged keys only for
readability. Query `truncations` before trusting a negative or complete-state conclusion.

For a fixed-step game, capture happens after `world.step` inside the acknowledged simulation tick:

```java
runtime.simulation().tick(FIXED_STEP_NANOS, supplied -> {
    world.step((float) (supplied / 1_000_000_000.0), 6, 2);
    gameLogicAfterPhysics();
    return supplied;
});
```

`Box2dAdapterLimits` bounds worlds, bodies, fixtures, joints, copied shape vertices, the largest
closed property schema, and diagnostics. Core capture limits still apply afterward; always inspect
`EntitySnapshot.truncations()` as well as fixture diagnostics. Capacity overflow, duplicate IDs or
native wrappers, missing parents/endpoints, wrong-world relationships, missing chain-loop
testimony, wrong-thread use, and use after close fail explicitly.

The adapter stores weak native references and owns neither discovery nor lifecycle. Before replacing
a native object, remove dependent fixture/joint registrations as required and call `rebind` on the
stable registration. Close registrations from leaves to roots, or close `Box2dInspection` to remove
all providers. The adapter never calls `World.dispose`, `Shape.dispose`, or any native destroy
operation; application code remains responsible for those objects. World and fixture rebind
preserve their registered `Box2dWorldSpec` or `Box2dFixtureSpec`; unregister and register again when
solver/unit or chain-loop testimony changes. Rebind and close reject an open runtime frame without
changing the registration.

Authors of other adapter modules that change an object behind an already registered entity provider
must call `runtime.entities().requireProviderMutationAllowed()` immediately before the swap. This
public guard preserves capture-thread ownership and rejects open-frame or closed-runtime mutation.

## Capture and inspect Box2D contacts

Use this development-version API when collision callbacks must be correlated with an authoritative
fixed simulation tick. Register both bodies and fixtures before registering contacts. There may be
one live contact registration per registered world:

```java
Box2dContacts contacts = physics.registerContacts(
        "main",
        Box2dContactLimits.developmentDefaults(),
        Box2dContactPolicy.developmentDefaults());

// The application installs the returned listener. Registration and listener() do not.
world.setContactListener(contacts.listener());
```

The defaults are an exact public contract:

```java
new Box2dContactLimits(
        128,  // callbackRecordsPerTick
        256,  // activeContactsPerTick
        2,    // pointsPerContact
        2,    // impulsesPerContact
        2,    // oldManifoldPointsPerContact
        8,    // diagnosticsPerTick
        1_024,// retainedContactTicks
        256); // queryPageSize

new Box2dContactPolicy(
        true,  // begin
        true,  // end
        false, // preSolve
        true); // postSolve
```

All contact limits are positive hard bounds. `queryPageSize` cannot exceed
`retainedContactTicks`. Supply an explicit `Box2dContactPolicy` to enable pre-solve or omit another
phase. Active-contact maintenance still processes native callbacks whose phase is not retained as a
record.

### Compose an existing application listener

If the game already has one listener, install the evidence-first, application-second composition:

```java
ContactListener combined = contacts.compose(gameContactListener);
world.setContactListener(combined);
```

`compose` accepts one application listener once and rejects composing the evidence listener with
itself. If the application listener throws, the adapter retains the closed
`APPLICATION_LISTENER_FAILED` and `STEP_FAILED` diagnostics and rethrows the original unchecked
failure. It never copies the exception message or stack trace, and the simulation tick cannot
silently report a successful callback.

### Capture the authoritative step

Wrap exactly one application-owned step inside the existing acknowledged tick callback:

```java
runtime.simulation().tick(FIXED_STEP_NANOS, suppliedDeltaNanos -> {
    processScheduledInput();
    contacts.captureStep(() -> world.step(
            suppliedDeltaNanos / 1_000_000_000f,
            worldSpec.velocityIterations(),
            worldSpec.positionIterations()));
    gameLogicAfterPhysics();
    return suppliedDeltaNanos;
});

renderGame();
```

`captureStep` requires the adapter's application thread, an active timeline-owned runtime frame,
and at most one captured step for that world in the simulation tick. It finalizes bounded contact
evidence before the frame closes and then rethrows an application step or listener failure. It
does not sleep, render, step again, install a listener, or create a loop or thread. Do not also step
the world from `render(delta)`.

With `RuntimeConfiguration.disabled()`, the same wrapper still invokes the application-owned step
exactly once and a composed listener still forwards to the application listener. It retains no
contact callbacks, history, entity, or events, so disabling observation never disables physics.

The generic `runtime.simulation().activeTick()` API is the transient integration context used by
the adapter. While the timeline-owned frame is open on the capture thread, including the simulation
callback and provider capture, it returns:

```text
ActiveSimulationTick
  simulationTickId
  executionEpochId
  epochTick
  suppliedDeltaNanos
  source
  runtimeFrameId
```

It is empty before `tick`, after `tick` returns, and on every other thread, including while the
callback is active. `source` is the closed `RUNNING` or `PAUSED` testimony. The context is cleared
after success or failure. Do not retain it or treat it as completed tick evidence; query
`SimulationTick` for the executed delta, outcome, elapsed simulation time, and final frame
correlation.

### Inspect typed Java evidence

Completed contact history is safe to query from another thread:

```java
Box2dContactTickPage page = contacts.ticks(1, 60, 60);
for (Box2dContactTick tick : page.ticks()) {
    if (!tick.complete()) {
        inspect(tick.diagnostics(), tick.truncations());
    }
    inspect(tick.records(), tick.activeContacts());
}
```

The range is inclusive and uses session-monotonic `SimulationTickId` values, not epoch-relative
ticks or render frames. `limit` must not exceed `queryPageSize`. `Box2dContactTickPage` exposes
`ticks`, `hasMore`, `rangeStatus`, `oldestRetainedTickId`, and `newestRetainedTickId`. Its closed
range statuses are `COMPLETE`, `PAGINATED`, `PARTIALLY_EVICTED`, `EVICTION_UNKNOWN`, and
`NOT_YET_CAPTURED`. A missing tick inside the requested retained range is `NOT_YET_CAPTURED`, never
an invented complete page.
The adapter confirms the simulation timeline's resulting frame before moving a captured contact
tick into typed history. A query made while that frame is pending omits it; a failed frame is
retained with `MISSING_CORRELATION` and `complete=false`. Paging allocates at most the requested
page. Bounded exact evicted-tick metadata survives history eviction and reset, so a known evicted
tick reports `PARTIALLY_EVICTED`. If that metadata is itself discarded, the affected old range
reports `EVICTION_UNKNOWN`; it does not silently become a PASS-capable negative result.

Each immutable `Box2dContactTick` exposes:

```text
simulationTickId, executionEpochId, epochTick, runtimeFrameId,
records, activeContacts,
callbackRecordsObserved, callbackRecordsRetained, callbackRecordLimit,
activeContactsObserved, activeContactsRetained, activeContactLimit,
unmappedContactsObserved, diagnostics, truncations, complete
```

Each `Box2dContactRecord` exposes `phase`, `key`, `endpointA`, `endpointB`, combined `sensor`,
`touching`, `enabled`, `availability`, `points`, optional `normal`, `impulses`, optional
`oldManifold`, `occurrence`, and `truncations`. Its closed phases are `BEGIN`, `END`, `PRE_SOLVE`,
and `POST_SOLVE`; availability is respectively `ENDPOINTS_ONLY`,
`CURRENT_AND_OLD_MANIFOLD`, or
`CURRENT_MANIFOLD_AND_IMPULSES`. `OldManifold` has a closed type of `CIRCLES`, `FACE_A`, or
`FACE_B` and bounded `OldManifoldPoint(id, normalImpulse, tangentImpulse)` values. Impulses are
`Impulse(normal, tangent)` values. `ActiveContact` exposes the latest bounded key/endpoints,
combined sensor and touching/enabled state, points, optional normal, impulses, and truncations.

The stable key is `Key(fixtureAId, childIndexA, fixtureBId, childIndexB)`. It is ordered by the
application fixture ID and then child index; native pointer and Java identity never appear. Each
endpoint adds its registered `bodyId`, `fixtureId`, `childIndex`, and copied `sensor` state. When
native A/B is reversed, the adapter swaps the endpoint facts, negates the world normal and signed
tangent impulses, preserves normal impulse magnitude, and leaves world points unchanged.

### Exact runtime entity schema

The current completed contact tick is the ordinary runtime entity
`box2d.contacts.<worldId>` with type `box2d.contacts`. Its exact top-level property set is:

```text
worldId
runtimeEntityId
policy
limits
latestTick
records
activeContacts
callbackCounts
activeCounts
unmappedContacts
diagnostics
truncations
complete
```

Runtime properties and object fields are serialized in canonical name order. The exact nested
closed schemas are:

```text
policy:
  begin, end, preSolve, postSolve

limits:
  callbackRecordsPerTick, activeContactsPerTick, pointsPerContact,
  impulsesPerContact, oldManifoldPointsPerContact, diagnosticsPerTick,
  retainedContactTicks, queryPageSize

latestTick:
  simulationTickId, executionEpochId, epochTick, runtimeFrameId

callbackCounts | activeCounts:
  observed, retained, limit

key:
  fixtureAId, childIndexA, fixtureBId, childIndexB

endpointA | endpointB:
  bodyId, fixtureId, childIndex, sensor

record:
  phase, key, endpointA, endpointB, sensor, touching, enabled, availability,
  points, normal, impulses, oldManifold, occurrence, truncations

activeContact:
  key, endpointA, endpointB, sensor, touching, enabled,
  points, normal, impulses, truncations

impulse:
  normal, tangent

oldManifold:
  type, points

oldManifoldPoint:
  id, normalImpulse, tangentImpulse

diagnostic:
  code, observed

truncation:
  dimension, observed, retained, limit
```

The combined `sensor` field is true when either endpoint fixture is a sensor; the endpoint fields
preserve which fixture supplied that state. `points` is a list of runtime `vector2` values.
`normal` is a `vector2` or an explicit runtime `null`. `oldManifold` is an object or explicit
runtime `null`. Begin/end records have `normal=null`, `oldManifold=null`, and empty `points` and
`impulses`. Pre-solve records have current points/normal, an old-manifold object, and empty
`impulses`. Post-solve records have current points/normal and impulses, with
`oldManifold=null`. Zero is never substituted for an unavailable phase value.

A representative begin record is:

```text
phase: BEGIN
key: {fixtureAId: ball, childIndexA: 0, fixtureBId: ground, childIndexB: 0}
endpointA: {bodyId: ball-body, fixtureId: ball, childIndex: 0, sensor: false}
endpointB: {bodyId: ground-body, fixtureId: ground, childIndex: 0, sensor: false}
sensor: false
touching: true
enabled: true
availability: ENDPOINTS_ONLY
points: []
normal: null
impulses: []
oldManifold: null
occurrence: 1
truncations: []
```

Before the first captured step, and after a reset baseline, `latestTick` is explicit null,
`records` and `activeContacts` are empty, and `complete` is false. Only a completed tick with
`complete=true`, no relevant core snapshot truncation, and an empty exact active/record set can
support a negative contact conclusion.

Adapter incompleteness is sticky when a callback arrived outside capture, after close, with an
unmapped endpoint, without a known begin, or during a failed step. Later quiet ticks remain
incomplete because silence cannot reconstruct the active set. A scenario/epoch reset or world
replacement supplies the authoritative clean baseline that clears this taint. Nested record or
active-contact truncations also force `complete=false` until the affected active value is replaced
by complete evidence or ends.

### Exact contact event schema

Each retained record emits one of:

```text
box2d.contact.begin
box2d.contact.end
box2d.contact.preSolve
box2d.contact.postSolve
```

The event type supplies the phase. Canonical body A is `subject`; canonical body B is `source`. The
event's own `frameId` is the runtime-frame correlation. Its exact attribute set is the record field
set without `phase`, plus world/tick correlation:

```text
worldId
simulationTickId
executionEpochId
epochTick
key
endpointA
endpointB
sensor
touching
enabled
availability
points
normal
impulses
oldManifold
occurrence
truncations
```

There is deliberately no duplicate `runtimeFrameId` attribute. Explicit null rules and every nested
schema are identical to the entity record. Records and emitted events use stable phase/key/occurrence
ordering after selecting the configured bounded native-delivery prefix.

### Query through protocol and MCP

No Box2D command or transport dependency is added. Use the existing exact entity, entity-history,
and event commands. MCP examples:

```json
{"name":"runtime_entity","arguments":{"sessionId":"game","entityId":"box2d.contacts.main","fromFrame":0,"toFrame":60,"limit":60}}
```

```json
{"name":"runtime_entity_history","arguments":{"sessionId":"game","entityId":"box2d.contacts.main","fromFrame":0,"toFrame":60,"versionOffset":0,"versionLimit":60}}
```

```json
{"name":"runtime_events","arguments":{"sessionId":"game","fromFrame":1,"toFrame":60,"eventType":"box2d.contact.begin","subject":"box2d.body.ball-body","source":"box2d.body.ground-body","limit":60}}
```

The equivalent transport-neutral protocol commands are `RuntimeCommand.Entity`,
`RuntimeCommand.EntityHistory`, and `RuntimeCommand.Events`. Protocol 2.0 or 2.1 is required for
entity history; the existing frozen entity/event command shapes remain unchanged. Protocol/MCP
responses carry the same closed `RuntimeValue` objects described above. Check both adapter-level
`complete`/`truncations` and the enclosing `EntitySnapshot.truncations` or query retention metadata.

### Bounds, diagnostics, and failure handling

The adapter selects a bounded callback prefix, bounds every retained callback value, and then sorts
the retained evidence. It does not promise that different Box2D native versions or platforms
deliver callbacks in the same order. The deterministic claim is limited to selected observations
under the same native library, platform, configuration, initial state, fixed step, and scheduled
input.

Contact-level truncation dimensions are closed strings:

```text
box2d.contact.records
box2d.contact.active
box2d.contact.points
box2d.contact.impulses
box2d.contact.oldManifoldPoints
box2d.contact.diagnostics
```

Each truncation contains `dimension`, saturating `observed`, `retained`, and `limit`. Relevant
diagnostics make `complete=false`. The closed diagnostic codes are:

Public contact record, active-contact, tick, and page constructors preflight their documented hard
sizes and closed truncation dimensions before copying a caller collection. They cannot be used to
construct an oversized or open-schema value that only appears bounded.

```text
UNMAPPED_ENDPOINT
CALLBACK_OUTSIDE_TICK
CALLBACK_AFTER_CLOSE
RECORD_LIMIT_REACHED
ACTIVE_LIMIT_REACHED
POINT_LIMIT_REACHED
IMPULSE_LIMIT_REACHED
OLD_MANIFOLD_LIMIT_REACHED
ENDPOINT_CHANGED
MISSING_CORRELATION
APPLICATION_LISTENER_FAILED
PHASE_VALUE_UNAVAILABLE
STEP_FAILED
EPOCH_RESET
WORLD_REBOUND
```

An unregistered callback endpoint increments `unmappedContacts` and `UNMAPPED_ENDPOINT` but exposes
no partial endpoint identity. A callback outside `captureStep` copies no native endpoint detail.
An application listener or step failure is rethrown after bounded finalization. Agent logic should
surface the structured facts instead of treating an empty list as success, for example:

```text
expected contact ball-body <-> ground-body
no complete contact evidence observed

contact entity:
  simulationTickId: 42
  callbackCounts: {observed: 129, retained: 128, limit: 128}
  complete: false
  diagnostics: [{code: RECORD_LIMIT_REACHED, observed: 1}]
  truncations:
    [{dimension: box2d.contact.records, observed: 129, retained: 128, limit: 128}]
```

### Reset, rebind, and close

A scenario reset or checkpoint restore starts a new execution epoch. Its baseline clears the active
set and typed `Box2dContacts.ticks` history, publishes `latestTick=null`, and reports `EPOCH_RESET`;
session simulation tick IDs still are not reused. Old typed queries report `PARTIALLY_EVICTED`
while their exact eviction IDs are retained, then `EVICTION_UNKNOWN` if that bounded metadata is
discarded. World rebind clears contact evidence and reports
`WORLD_REBOUND`. Install the same explicit listener or composition on the replacement world before
stepping it. Fixture rebind/unregister clears only affected retained active keys, preserves
unrelated contacts, and reports `ENDPOINT_CHANGED`.
These operations retain incomplete evidence instead of silently preserving a stale native contact.

Close `Box2dContacts` or its parent `Box2dInspection` on the application thread and outside an open
frame or captured step. Close is idempotent, removes live contact providers and listener-composition
references, clears typed contact history, and never calls a native dispose operation. Calls through
the closed handle fail with stable lifecycle errors. Completed runtime frames and events already
copied into core remain immutable until ordinary core retention evicts them.

Contact events state only that Box2D delivered a callback for registered endpoints. The runtime
never infers that a player landed, took damage, died, scored, or caused another gameplay outcome.
Emit an application semantic event or explicit attribution when an agent needs that causality.
