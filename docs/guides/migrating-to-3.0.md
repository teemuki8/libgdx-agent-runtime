# Migrating runtime Box2D inspection to 3.0

Runtime 3.0 is a clean major-version cutover to the official Box2D 3.1.1 binding. Runtime 2.x remains available for applications that still use libGDX's legacy object binding; the two backends must not share one dependency configuration because both artifacts use the same Maven coordinate.

## Breaking API map

| Runtime 2.x | Runtime 3.0 |
| --- | --- |
| `World`, `Body`, `Fixture`, `Joint` registrations | `b2WorldId`, `b2BodyId`, `b2ShapeId`, `b2JointId` registrations |
| `registerFixture` | `registerShape` |
| solver iteration and optional inverse-step testimony | bounded `Box2dWorldSpec.subStepCount` |
| `Box2dFixtureSpec` chain testimony | `Box2dShapeSpec`; geometry is copied from the live Box2D 3 shape |
| legacy listener composition through `Box2dContacts` | copy Box2D 3 post-step event arrays in the application integration |
| legacy fixture/body/world counts | Box2D 3 body, shape, contact, joint, island, awake-body, stack, and allocation counters |

There are no aliases, adapters, deprecated overloads, or dual backend. Remove all `com.badlogic.gdx.physics.box2d` imports and lock `com.badlogicgames.gdx:gdx-box2d:3.1.1-0` plus the matching platform native.

## Lifecycle cutover

Create and register parent-first. Close runtime registrations and destroy native resources child-first: joints, shapes, bodies, then world. Rebind only to a live ID with the same registered parent and, for joints, the same registered endpoints. A stale ID is an error rather than missing evidence.

Runtime IDs remain stable across the major migration. Native ID scalar fields are implementation-private and are not evidence.
