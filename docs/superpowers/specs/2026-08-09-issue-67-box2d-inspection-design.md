# Issue 67 Box2D inspection adapter design

- Status: Approved by issue #67 and the instruction to continue
- Date: 2026-08-09
- Base: issue #65 / PR #74

## Architecture

Create `runtime-box2d` with dependency direction `runtime-box2d -> runtime-core` and
`runtime-box2d -> gdx-box2d`. `runtime-fixtures` may depend on it; core, protocol, and MCP must not.
The module is separately buildable and configured as artifact `agent-runtime-box2d`, without staging
or publishing.

`Box2dInspection` owns only registrations and runtime entity providers. Applications supply stable
IDs and native objects. Runtime entity IDs are deterministic `box2d.<kind>.<application-id>` values;
the application ID and mapped runtime ID are both inspectable. IDs are never derived from pointers,
object identity, enumeration order, or `userData`.

## Public API

- `Box2dAdapterLimits`: hard bounds for worlds, bodies, fixtures, joints, shape vertices,
  per-entity properties, and diagnostic entries.
- `Box2dUnitTransform`: positive finite render-units-per-metre plus scalar/vector conversion helpers.
- `Box2dWorldSpec`: explicit sleeping, warm-starting, continuous-physics, velocity-iteration,
  position-iteration, optional valid inverse step, and unit transform testimony.
- `Box2dRegistration<T>`: stable application ID, runtime entity ID, explicit `rebind(T)`, and
  idempotent unregister.
- `Box2dInspection`: explicit world/body/fixture/joint registration, deterministic lookup, counts,
  and close.

Registration validates the limit and every relationship before retaining a weak native reference or
runtime provider. Duplicate IDs, duplicate native registrations, wrong world/body ownership, missing
related registrations, wrong-thread rebind, closed adapter/runtime use, and rebinding a world with
registered descendants fail deterministically. Recreated worlds are handled by unregistering their
selected descendants, rebinding the world, and registering the new descendants before the new epoch
baseline.

## Entity schemas

All schemas contain `id` and `runtimeEntityId`.

- `box2d.world`: gravity, explicit solver/settings testimony, configured fixed step when present,
  registered/total counts, lock state, and unit transform.
- `box2d.body`: owning world, type, position, angle, velocities, mass/inertia, gravity scale,
  damping, awake, active, bullet, fixed rotation, sleeping allowed, and fixture counts.
- `box2d.fixture`: owning body, shape type, sensor/material/filter values, and one closed geometry
  object. Polygon/chain geometry reports observed and retained vertices, limit, and truncation.
- `box2d.joint`: type, registered body endpoints, anchors, active, collide-connected, optional
  reaction values only with explicit inverse step, and a closed type-specific detail object for
  distance, revolute, and prismatic joints. Other native joint types use explicit generic detail.

All native vectors and vertex buffers are copied into `RuntimeValue` instances during capture.
Identity comparison is used only internally to verify or resolve an already registered native
object and is never exposed.

## Lifecycle and verification

The creating thread owns registration, rebind, unregister, and adapter close. Capture remains owned
by core. Weak references plus core provider release ensure runtime close cannot keep native objects
alive. Adapter close unregisters providers when runtime is open and otherwise only clears adapter
state. It never calls Box2D `dispose` or destroys native objects.

Tests use actual Box2D native objects for every schema, relationship, rebind/destruction workflow,
bounds, truncation, thread/lifecycle, and ownership assertion. Build work updates catalogs,
lockfiles, verification checksums, dependency review, publication mappings, Javadocs, guides, and
the cookbook. Verification includes focused adapter tests, core `jdeps`, repository checks, and the
Linux Xvfb full gate.
