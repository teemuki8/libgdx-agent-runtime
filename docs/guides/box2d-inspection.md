# Box2D 3 inspection

`agent-runtime-box2d:3.0.0` uses the official `gdx-box2d:3.1.1-0` binding. It accepts Box2D 3 IDs at the adapter boundary and publishes only copied, bounded runtime values. The application owns initialization, stepping, and native destruction.

## Construct and register

Initialize once with `Box2d.initialize()`. Create a `b2WorldId` from `b2DefaultWorldDef`, keep `workerCount(0)` when deterministic scheduling is required, then create bodies, shapes, and joints with the corresponding Box2D 3 definitions.

Create `Box2dInspection` on the runtime capture thread. Register in parent-first order:

```java
Box2dInspection inspection = new Box2dInspection(
        runtime, Box2dAdapterLimits.developmentDefaults());
var worldRegistration = inspection.registerWorld(
        "main", world, new Box2dWorldSpec(4, new Box2dUnitTransform(32)));
var bodyRegistration = inspection.registerBody("player", "main", body);
var shapeRegistration = inspection.registerShape(
        "player-capsule", "player", capsuleShape, Box2dShapeSpec.defaults());
var jointRegistration = inspection.registerJoint("shoulder", "main", shoulderJoint);
```

Register `Box2dContacts` after all selected shapes. Call `captureStep` around exactly one
application-owned `b2World_Step`; it copies begin/end/hit arrays immediately and obtains whole-step
normal impulse from bounded matching contact data. No listener is installed.

A joint is accepted only when both endpoint bodies are registered. Stable runtime entity IDs are `box2d.world.<id>`, `box2d.body.<id>`, `box2d.fixture.<id>`, and `box2d.joint.<id>`.

World evidence includes native Box2D 3 counters and the declared substep count. Body evidence includes position, rotation, linear and angular velocity, mass, rotational inertia, damping, activity, and shape/joint counts. Shape evidence includes material/filter facts and copied circle, capsule, segment, chain-segment, or bounded polygon geometry. Capsule evidence contains both local segment centres and radius. Revolute joint evidence includes endpoints, anchors, limits, angle, and motor configuration.

Native `index1`, `world0`, and `generation` fields are private lookup keys. They never appear in runtime entities, protocol values, diagnostics, or MCP output.

## Failure and lifecycle

Registration and rebind reject null, invalid, stale, duplicate, wrong-world, over-limit, missing-parent, and missing-joint-endpoint IDs. Mutation requires the adapter owner thread, an open runtime, and no open frame. Completed immutable snapshots remain queryable according to the core runtime lifecycle.

Close contact capture and registration providers before destroying native resources, in
descendant-first order:

```java
contacts.close();
jointRegistration.close();
shapeRegistration.close();
bodyRegistration.close();
worldRegistration.close();
inspection.close();

Box2d.b2DestroyJoint(shoulderJoint);
Box2d.b2DestroyShape(capsuleShape, true);
Box2d.b2DestroyBody(body);
Box2d.b2DestroyWorld(world);
```

`Box2dInspection.close()` and registration `close()` are idempotent and never destroy application-owned native resources.
