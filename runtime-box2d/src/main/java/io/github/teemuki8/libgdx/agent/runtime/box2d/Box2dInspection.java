package io.github.teemuki8.libgdx.agent.runtime.box2d;

import com.badlogic.gdx.box2d.Box2d;
import com.badlogic.gdx.box2d.enums.b2JointType;
import com.badlogic.gdx.box2d.structs.b2BodyId;
import com.badlogic.gdx.box2d.structs.b2Counters;
import com.badlogic.gdx.box2d.structs.b2Filter;
import com.badlogic.gdx.box2d.structs.b2JointId;
import com.badlogic.gdx.box2d.structs.b2ShapeId;
import com.badlogic.gdx.box2d.structs.b2Rot;
import com.badlogic.gdx.box2d.structs.b2Vec2;
import com.badlogic.gdx.box2d.structs.b2WorldId;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityRegistration;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Explicit bounded registration adapter for selected application-owned Box2D 3 IDs. */
public final class Box2dInspection implements AutoCloseable {
    private final AgentRuntime runtime;
    private final Box2dAdapterLimits limits;
    private final Thread ownerThread;
    private final LinkedHashMap<String, WorldEntry> worlds = new LinkedHashMap<>();
    private final LinkedHashMap<String, BodyEntry> bodies = new LinkedHashMap<>();
    private final LinkedHashMap<String, ShapeEntry> shapes = new LinkedHashMap<>();
    private final LinkedHashMap<String, JointEntry> joints = new LinkedHashMap<>();
    private final LinkedHashMap<String, Box2dContacts> contacts = new LinkedHashMap<>();
    private boolean closed;

    /** Creates an adapter owned by the calling application/capture thread. */
    public Box2dInspection(AgentRuntime runtime, Box2dAdapterLimits limits) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.limits = Objects.requireNonNull(limits, "limits");
        ownerThread = Thread.currentThread();
    }

    /** Registers one live world ID and copied solver/unit testimony. */
    public Box2dRegistration<b2WorldId> registerWorld(
            String id, b2WorldId world, Box2dWorldSpec spec) {
        requireOwnerOpen();
        validateId(id);
        Objects.requireNonNull(spec, "spec");
        WorldKey key = WorldKey.copyOf(requireLive(world));
        requireUnique(id, key, worlds, "world");
        requireCapacity(worlds, limits.worlds(), "world");
        WorldEntry entry = new WorldEntry(id, entityId("world", id), key, spec);
        entry.registration = runtime.entities().register(entry.entityId,
                EntityType.of("box2d.world"), () -> id,
                inspector -> declareWorld(inspector, entry));
        worlds.put(id, entry);
        return handle(entry);
    }

    /** Registers one live body ID owned by an already registered world. */
    public Box2dRegistration<b2BodyId> registerBody(String id, String worldId, b2BodyId body) {
        requireOwnerOpen();
        validateId(id);
        b2BodyId value = requireLive(body);
        WorldEntry world = requireEntry(worlds, worldId, "world");
        BodyKey key = BodyKey.copyOf(value);
        if (!WorldKey.copyOf(Box2d.b2Body_GetWorld(value)).equals(world.key)) {
            throw new IllegalArgumentException("body does not belong to the registered world");
        }
        requireUnique(id, key, bodies, "body");
        requireCapacity(bodies, limits.bodies(), "body");
        BodyEntry entry = new BodyEntry(id, entityId("body", id), key, worldId);
        entry.registration = runtime.entities().register(entry.entityId,
                EntityType.of("box2d.body"), () -> id,
                inspector -> declareBody(inspector, entry));
        bodies.put(id, entry);
        return handle(entry);
    }

    /** Registers one live shape ID owned by an already registered body. */
    public Box2dRegistration<b2ShapeId> registerShape(String id, String bodyId, b2ShapeId shape,
            Box2dShapeSpec spec) {
        requireOwnerOpen();
        validateId(id);
        b2ShapeId value = requireLive(shape);
        Objects.requireNonNull(spec, "spec");
        BodyEntry body = requireEntry(bodies, bodyId, "body");
        ShapeKey key = ShapeKey.copyOf(value);
        if (!BodyKey.copyOf(Box2d.b2Shape_GetBody(value)).equals(body.key)) {
            throw new IllegalArgumentException("shape does not belong to the registered body");
        }
        requireUnique(id, key, shapes, "shape");
        requireCapacity(shapes, limits.fixtures(), "shape");
        ShapeEntry entry = new ShapeEntry(id, entityId("fixture", id), key, bodyId, spec);
        entry.registration = runtime.entities().register(entry.entityId,
                EntityType.of("box2d.fixture"), () -> id,
                inspector -> declareShape(inspector, entry));
        shapes.put(id, entry);
        return handle(entry);
    }

    /** Registers one live joint ID whose endpoint bodies are already registered. */
    public Box2dRegistration<b2JointId> registerJoint(String id, String worldId, b2JointId joint) {
        requireOwnerOpen();
        validateId(id);
        b2JointId value = requireLive(joint);
        WorldEntry world = requireEntry(worlds, worldId, "world");
        if (!WorldKey.copyOf(Box2d.b2Joint_GetWorld(value)).equals(world.key)) {
            throw new IllegalArgumentException("joint does not belong to the registered world");
        }
        String bodyA = bodyId(BodyKey.copyOf(Box2d.b2Joint_GetBodyA(value)));
        String bodyB = bodyId(BodyKey.copyOf(Box2d.b2Joint_GetBodyB(value)));
        JointKey key = JointKey.copyOf(value);
        requireUnique(id, key, joints, "joint");
        requireCapacity(joints, limits.joints(), "joint");
        JointEntry entry = new JointEntry(id, entityId("joint", id), key, worldId, bodyA, bodyB);
        entry.registration = runtime.entities().register(entry.entityId,
                EntityType.of("box2d.joint"), () -> id,
                inspector -> declareJoint(inspector, entry));
        joints.put(id, entry);
        return handle(entry);
    }

    /** Registers bounded post-step Box2D 3 contact-event capture for one registered world. */
    public Box2dContacts registerContacts(String worldId, Box2dContactLimits contactLimits,
            Box2dContactPolicy policy) {
        requireOwnerOpen();
        String id = Objects.requireNonNull(worldId, "worldId");
        requireEntry(worlds, id, "world");
        Objects.requireNonNull(contactLimits, "contactLimits");
        Objects.requireNonNull(policy, "policy");
        if (contacts.containsKey(id)) {
            throw new IllegalArgumentException(
                    "Box2D contacts are already registered for this world");
        }
        Box2dContacts registration = new Box2dContacts(
                runtime, this, id, contactLimits, policy, ownerThread);
        registration.registerEntity();
        contacts.put(id, registration);
        return registration;
    }

    /** Returns configured adapter limits. */
    public Box2dAdapterLimits limits() {
        return limits;
    }

    /** Releases registrations without destroying application-owned native IDs. */
    @Override public void close() {
        requireOwner();
        if (closed) {
            return;
        }
        contacts.values().forEach(Box2dContacts::closeFromInspection);
        if (runtime.status() != RuntimeStatus.CLOSED) {
            runtime.entities().requireProviderMutationAllowed();
            joints.values().forEach(Entry::closeProvider);
            shapes.values().forEach(Entry::closeProvider);
            bodies.values().forEach(Entry::closeProvider);
            worlds.values().forEach(Entry::closeProvider);
        }
        closed = true;
        contacts.clear();
        joints.clear();
        shapes.clear();
        bodies.clear();
        worlds.clear();
    }

    private void declareWorld(io.github.teemuki8.libgdx.agent.runtime.core.EntityInspector inspector,
            WorldEntry entry) {
        inspector.property("id", () -> RuntimeValues.string(entry.id))
                .property("runtimeEntityId", () -> RuntimeValues.string(entry.entityId.value()))
                .property("gravity", () -> worldGravity(entry))
                .property("sleepingAllowed", () -> Box2d.b2World_IsSleepingEnabled(entry.live()))
                .property("warmStarting", () -> Box2d.b2World_IsWarmStartingEnabled(entry.live()))
                .property("continuousPhysics", () -> Box2d.b2World_IsContinuousEnabled(entry.live()))
                .property("hitEventThreshold",
                        () -> decimal(Box2d.b2World_GetHitEventThreshold(entry.live())))
                .property("restitutionThreshold",
                        () -> decimal(Box2d.b2World_GetRestitutionThreshold(entry.live())))
                .property("subStepCount", () -> (long) entry.spec.subStepCount())
                .property("fixedStepNanos", this::fixedStep)
                .property("registeredBodyCount", () -> count(bodies, entry.id))
                .property("registeredShapeCount", () -> countShapes(entry.id))
                .property("registeredFixtureCount", () -> countShapes(entry.id))
                .property("registeredJointCount", () -> count(joints, entry.id))
                .property("totalBodyCount", () -> counters(entry).bodyCount())
                .property("totalShapeCount", () -> counters(entry).shapeCount())
                .property("totalFixtureCount", () -> counters(entry).shapeCount())
                .property("totalJointCount", () -> counters(entry).jointCount())
                .property("totalContactCount", () -> counters(entry).contactCount())
                .property("islandCount", () -> counters(entry).islandCount())
                .property("awakeBodyCount", () -> Box2d.b2World_GetAwakeBodyCount(entry.live()))
                .property("stackUsedBytes", () -> counters(entry).stackUsed())
                .property("allocatedBytes", () -> counters(entry).byteCount())
                .property("renderUnitsPerMeter",
                        () -> RuntimeValues.decimal(entry.spec.unitTransform().renderUnitsPerMeter()));
    }

    private void declareBody(io.github.teemuki8.libgdx.agent.runtime.core.EntityInspector inspector,
            BodyEntry entry) {
        inspector.property("id", () -> RuntimeValues.string(entry.id))
                .property("runtimeEntityId", () -> RuntimeValues.string(entry.entityId.value()))
                .property("worldId", () -> RuntimeValues.string(entry.parentId))
                .property("bodyType", () -> RuntimeValues.enumValue(bodyType(entry.live())))
                .property("position", () -> bodyPosition(entry))
                .property("angleRadians", () -> bodyAngle(entry))
                .property("linearVelocity", () -> bodyVelocity(entry))
                .property("angularVelocity", () -> decimal(
                        Box2d.b2Body_GetAngularVelocity(entry.live())))
                .property("mass", () -> decimal(Box2d.b2Body_GetMass(entry.live())))
                .property("inertia", () -> decimal(
                        Box2d.b2Body_GetRotationalInertia(entry.live())))
                .property("gravityScale", () -> decimal(Box2d.b2Body_GetGravityScale(entry.live())))
                .property("linearDamping", () -> decimal(
                        Box2d.b2Body_GetLinearDamping(entry.live())))
                .property("angularDamping", () -> decimal(
                        Box2d.b2Body_GetAngularDamping(entry.live())))
                .property("awake", () -> Box2d.b2Body_IsAwake(entry.live()))
                .property("active", () -> Box2d.b2Body_IsEnabled(entry.live()))
                .property("bullet", () -> Box2d.b2Body_IsBullet(entry.live()))
                .property("fixedRotation", () -> Box2d.b2Body_IsFixedRotation(entry.live()))
                .property("sleepingAllowed", () -> Box2d.b2Body_IsSleepEnabled(entry.live()))
                .property("registeredShapeCount", () -> count(shapes, entry.id))
                .property("registeredFixtureCount", () -> count(shapes, entry.id))
                .property("totalShapeCount", () -> Box2d.b2Body_GetShapeCount(entry.live()))
                .property("totalFixtureCount", () -> Box2d.b2Body_GetShapeCount(entry.live()))
                .property("totalJointCount", () -> Box2d.b2Body_GetJointCount(entry.live()));
    }

    private void declareShape(io.github.teemuki8.libgdx.agent.runtime.core.EntityInspector inspector,
            ShapeEntry entry) {
        inspector.property("id", () -> RuntimeValues.string(entry.id))
                .property("runtimeEntityId", () -> RuntimeValues.string(entry.entityId.value()))
                .property("bodyId", () -> RuntimeValues.string(entry.parentId))
                .property("shapeType", () -> RuntimeValues.enumValue(shapeType(entry.live())))
                .property("sensor", () -> Box2d.b2Shape_IsSensor(entry.live()))
                .property("density", () -> decimal(Box2d.b2Shape_GetDensity(entry.live())))
                .property("friction", () -> decimal(Box2d.b2Shape_GetFriction(entry.live())))
                .property("restitution", () -> decimal(Box2d.b2Shape_GetRestitution(entry.live())))
                .property("material", () -> Box2d.b2Shape_GetMaterial(entry.live()))
                .property("categoryBits", () -> shapeFilter(entry).categoryBits())
                .property("maskBits", () -> shapeFilter(entry).maskBits())
                .property("groupIndex", () -> (long) shapeFilter(entry).groupIndex())
                .property("geometry", () -> Box2dShapeValues.copy(
                        entry.live(), limits.shapeVertices(), entry.geometryScratch))
                .property("diagnostics", () -> Box2dShapeValues.diagnostics(
                        entry.live(), limits.shapeVertices(), entry.geometryScratch));
    }

    private void declareJoint(io.github.teemuki8.libgdx.agent.runtime.core.EntityInspector inspector,
            JointEntry entry) {
        inspector.property("id", () -> RuntimeValues.string(entry.id))
                .property("runtimeEntityId", () -> RuntimeValues.string(entry.entityId.value()))
                .property("worldId", () -> RuntimeValues.string(entry.parentId))
                .property("jointType", () -> RuntimeValues.enumValue(jointType(entry.live())))
                .property("bodyAId", () -> RuntimeValues.string(entry.bodyA))
                .property("bodyBId", () -> RuntimeValues.string(entry.bodyB))
                .property("localAnchorA", () -> jointAnchorA(entry))
                .property("localAnchorB", () -> jointAnchorB(entry))
                .property("collideConnected",
                        () -> Box2d.b2Joint_GetCollideConnected(entry.live()))
                .property("constraintForce", () -> jointConstraintForce(entry))
                .property("constraintTorque",
                        () -> decimal(Box2d.b2Joint_GetConstraintTorque(entry.live())))
                .property("detail", () -> jointDetail(entry.live()));
    }

    private RuntimeValue jointDetail(b2JointId joint) {
        if (Box2d.b2Joint_GetType(joint) == b2JointType.b2_revoluteJoint) {
            return RuntimeValues.object(
                    RuntimeValues.field("type", RuntimeValues.enumValue("REVOLUTE")),
                    RuntimeValues.field("referenceAngle",
                            decimal(Box2d.b2Joint_GetReferenceAngle(joint))),
                    RuntimeValues.field("jointAngle", decimal(Box2d.b2RevoluteJoint_GetAngle(joint))),
                    RuntimeValues.field("limitEnabled",
                            RuntimeValues.bool(Box2d.b2RevoluteJoint_IsLimitEnabled(joint))),
                    RuntimeValues.field("lowerLimit",
                            decimal(Box2d.b2RevoluteJoint_GetLowerLimit(joint))),
                    RuntimeValues.field("upperLimit",
                            decimal(Box2d.b2RevoluteJoint_GetUpperLimit(joint))),
                    RuntimeValues.field("motorEnabled",
                            RuntimeValues.bool(Box2d.b2RevoluteJoint_IsMotorEnabled(joint))),
                    RuntimeValues.field("motorSpeed",
                            decimal(Box2d.b2RevoluteJoint_GetMotorSpeed(joint))),
                    RuntimeValues.field("maxMotorTorque",
                            decimal(Box2d.b2RevoluteJoint_GetMaxMotorTorque(joint))));
        }
        return RuntimeValues.object(RuntimeValues.field("type",
                RuntimeValues.enumValue(jointType(joint))));
    }

    private RuntimeValue fixedStep() {
        OptionalLong value = runtime.simulation().state().configuredFixedStepNanos();
        return value.isPresent() ? RuntimeValues.integer(value.orElseThrow())
                : RuntimeValues.nullValue();
    }

    private RuntimeValue.Vector2Value worldGravity(WorldEntry entry) {
        Box2d.b2World_GetGravity(entry.live(), entry.vectorScratch);
        return vector(entry.vectorScratch);
    }

    private b2Counters counters(WorldEntry entry) {
        Box2d.b2World_GetCounters(entry.live(), entry.countersScratch);
        return entry.countersScratch;
    }

    private RuntimeValue.Vector2Value bodyPosition(BodyEntry entry) {
        Box2d.b2Body_GetPosition(entry.live(), entry.positionScratch);
        return vector(entry.positionScratch);
    }

    private RuntimeValue.DecimalValue bodyAngle(BodyEntry entry) {
        Box2d.b2Body_GetRotation(entry.live(), entry.rotationScratch);
        return decimal(Box2d.b2Rot_GetAngle(entry.rotationScratch));
    }

    private RuntimeValue.Vector2Value bodyVelocity(BodyEntry entry) {
        Box2d.b2Body_GetLinearVelocity(entry.live(), entry.velocityScratch);
        return vector(entry.velocityScratch);
    }

    private b2Filter shapeFilter(ShapeEntry entry) {
        Box2d.b2Shape_GetFilter(entry.live(), entry.filterScratch);
        return entry.filterScratch;
    }

    private RuntimeValue.Vector2Value jointAnchorA(JointEntry entry) {
        Box2d.b2Joint_GetLocalAnchorA(entry.live(), entry.anchorAScratch);
        return vector(entry.anchorAScratch);
    }

    private RuntimeValue.Vector2Value jointAnchorB(JointEntry entry) {
        Box2d.b2Joint_GetLocalAnchorB(entry.live(), entry.anchorBScratch);
        return vector(entry.anchorBScratch);
    }

    private RuntimeValue.Vector2Value jointConstraintForce(JointEntry entry) {
        Box2d.b2Joint_GetConstraintForce(entry.live(), entry.forceScratch);
        return vector(entry.forceScratch);
    }

    private long count(Map<String, ? extends Entry<?, ?>> entries, String parentId) {
        return entries.values().stream().filter(entry -> parentId.equals(entry.parentId)).count();
    }

    private long countShapes(String worldId) {
        return shapes.values().stream().filter(shape -> {
            BodyEntry body = bodies.get(shape.parentId);
            return body != null && worldId.equals(body.parentId);
        }).count();
    }

    private String bodyId(BodyKey key) {
        return bodies.values().stream().filter(entry -> entry.key.equals(key)).map(entry -> entry.id)
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "joint endpoints must be explicitly registered bodies"));
    }

    private <T, K> Box2dRegistration<T> handle(Entry<T, K> entry) {
        return new Registration<>(entry);
    }

    private <T, K> void rebind(Entry<T, K> entry, T value) {
        requireOwnerOpen();
        if (entry.closed) {
            throw new IllegalStateException("Box2D registration is closed");
        }
        runtime.entities().requireProviderMutationAllowed();
        K key = entry.copyAndValidate(value);
        entry.validateParent(key);
        entry.requireNoDescendants();
        requireUniqueKey(key, entry.map(), entry);
        entry.setKey(key);
        notifyContactMutation(entry);
    }

    private void remove(Entry<?, ?> entry) {
        requireOwner();
        if (entry.closed) {
            return;
        }
        entry.requireNoDescendants();
        if (runtime.status() != RuntimeStatus.CLOSED) {
            entry.closeProvider();
        }
        entry.closed = true;
        notifyContactMutation(entry);
        entry.map().remove(entry.id, entry);
    }

    b2WorldId worldId(String worldId) {
        return requireEntry(worlds, worldId, "world").live();
    }

    Optional<ContactMapping> contactMapping(
            String worldId, b2ShapeId nativeA, b2ShapeId nativeB) {
        ShapeEntry shapeA = shapes.values().stream()
                .filter(entry -> entry.key.equals(ShapeKey.copyOf(nativeA)))
                .findFirst().orElse(null);
        ShapeEntry shapeB = shapes.values().stream()
                .filter(entry -> entry.key.equals(ShapeKey.copyOf(nativeB)))
                .findFirst().orElse(null);
        if (shapeA == null || shapeB == null) {
            return Optional.empty();
        }
        BodyEntry bodyA = bodies.get(shapeA.parentId);
        BodyEntry bodyB = bodies.get(shapeB.parentId);
        if (bodyA == null || bodyB == null || !worldId.equals(bodyA.parentId)
                || !worldId.equals(bodyB.parentId)) {
            return Optional.empty();
        }
        Box2dContactRecord.Endpoint endpointA = new Box2dContactRecord.Endpoint(
                bodyA.id, shapeA.id, 0, Box2d.b2Shape_IsSensor(nativeA));
        Box2dContactRecord.Endpoint endpointB = new Box2dContactRecord.Endpoint(
                bodyB.id, shapeB.id, 0, Box2d.b2Shape_IsSensor(nativeB));
        int order = endpointA.compareTo(endpointB);
        if (order == 0) {
            return Optional.empty();
        }
        Box2dContactRecord.Endpoint canonicalA = order < 0 ? endpointA : endpointB;
        Box2dContactRecord.Endpoint canonicalB = order < 0 ? endpointB : endpointA;
        return Optional.of(new ContactMapping(
                new Box2dContactRecord.Key(
                        canonicalA.fixtureId(), 0, canonicalB.fixtureId(), 0),
                canonicalA, canonicalB, order > 0));
    }

    void unregisterContacts(String worldId, Box2dContacts registration) {
        contacts.remove(worldId, registration);
    }

    private void notifyContactMutation(Entry<?, ?> entry) {
        if (entry instanceof WorldEntry) {
            Box2dContacts registration = contacts.get(entry.id);
            if (registration != null) {
                registration.worldChanged();
            }
        } else if (entry instanceof ShapeEntry shape) {
            BodyEntry body = bodies.get(shape.parentId);
            if (body != null) {
                Box2dContacts registration = contacts.get(body.parentId);
                if (registration != null) {
                    registration.fixtureChanged(shape.id);
                }
            }
        }
    }

    record ContactMapping(Box2dContactRecord.Key key, Box2dContactRecord.Endpoint endpointA,
            Box2dContactRecord.Endpoint endpointB, boolean reversed) {}

    private void requireOwnerOpen() {
        requireOwner();
        if (closed || runtime.status() == RuntimeStatus.CLOSED) {
            throw new IllegalStateException("Box2D inspection is closed");
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Box2D inspection requires its application thread");
        }
    }

    private static void validateId(String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || id.length() > 220) {
            throw new IllegalArgumentException("Box2D application ID is outside range");
        }
    }

    private static EntityId entityId(String kind, String id) {
        return EntityId.of("box2d." + kind + "." + id);
    }

    private static <K, E extends Entry<?, K>> void requireUnique(
            String id, K key, Map<String, E> entries, String kind) {
        if (entries.containsKey(id)) {
            throw new IllegalArgumentException("duplicate Box2D " + kind + " ID");
        }
        requireUniqueKey(key, entries, null);
    }

    private static <K> void requireUniqueKey(
            K key, Map<String, ? extends Entry<?, K>> entries, Entry<?, K> ignored) {
        if (entries.values().stream().anyMatch(
                entry -> entry != ignored && entry.key.equals(key))) {
            throw new IllegalArgumentException("native Box2D ID is already registered");
        }
    }

    private static void requireCapacity(Map<?, ?> entries, int limit, String kind) {
        if (entries.size() >= limit) {
            throw new IllegalArgumentException("Box2D " + kind + " registration limit reached");
        }
    }

    private static <E> E requireEntry(Map<String, E> entries, String id, String kind) {
        E entry = entries.get(Objects.requireNonNull(id, kind + "Id"));
        if (entry == null) {
            throw new IllegalArgumentException("unknown registered Box2D " + kind);
        }
        return entry;
    }

    private static b2WorldId requireLive(b2WorldId value) {
        Objects.requireNonNull(value, "world");
        if (!Box2d.b2World_IsValid(value)) {
            throw new IllegalArgumentException("Box2D world ID is stale or invalid");
        }
        return value;
    }

    private static b2BodyId requireLive(b2BodyId value) {
        Objects.requireNonNull(value, "body");
        if (!Box2d.b2Body_IsValid(value)) {
            throw new IllegalArgumentException("Box2D body ID is stale or invalid");
        }
        return value;
    }

    private static b2ShapeId requireLive(b2ShapeId value) {
        Objects.requireNonNull(value, "shape");
        if (!Box2d.b2Shape_IsValid(value)) {
            throw new IllegalArgumentException("Box2D shape ID is stale or invalid");
        }
        return value;
    }

    private static b2JointId requireLive(b2JointId value) {
        Objects.requireNonNull(value, "joint");
        if (!Box2d.b2Joint_IsValid(value)) {
            throw new IllegalArgumentException("Box2D joint ID is stale or invalid");
        }
        return value;
    }

    static RuntimeValue.Vector2Value vector(b2Vec2 value) {
        return new RuntimeValue.Vector2Value(decimal(value.x()), decimal(value.y()));
    }

    static RuntimeValue.DecimalValue decimal(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Box2D float value must be finite");
        }
        return RuntimeValues.decimal(Float.toString(value));
    }

    private static String bodyType(b2BodyId body) {
        return switch (Box2d.b2Body_GetType(body)) {
            case b2_staticBody -> "STATIC";
            case b2_kinematicBody -> "KINEMATIC";
            case b2_dynamicBody -> "DYNAMIC";
            case b2_bodyTypeCount -> throw new IllegalStateException("invalid Box2D body type");
        };
    }

    private static String shapeType(b2ShapeId shape) {
        return switch (Box2d.b2Shape_GetType(shape)) {
            case b2_circleShape -> "CIRCLE";
            case b2_capsuleShape -> "CAPSULE";
            case b2_segmentShape -> "SEGMENT";
            case b2_polygonShape -> "POLYGON";
            case b2_chainSegmentShape -> "CHAIN_SEGMENT";
            case b2_shapeTypeCount -> throw new IllegalStateException("invalid Box2D shape type");
        };
    }

    private static String jointType(b2JointId joint) {
        String name = Box2d.b2Joint_GetType(joint).name();
        return name.substring(3, name.length() - "Joint".length()).toUpperCase(java.util.Locale.ROOT);
    }

    private abstract class Entry<T, K> {
        final String id;
        final EntityId entityId;
        final String parentId;
        K key;
        EntityRegistration registration;
        boolean closed;

        Entry(String id, EntityId entityId, K key, String parentId) {
            this.id = id;
            this.entityId = entityId;
            this.key = key;
            this.parentId = parentId;
        }

        abstract T live();
        abstract K copyAndValidate(T value);
        abstract Map<String, ? extends Entry<?, K>> map();
        abstract void setKey(K replacement);

        void validateParent(K replacement) {}
        void requireNoDescendants() {}

        void closeProvider() {
            if (registration != null) {
                registration.close();
                registration = null;
            }
        }
    }

    private final class WorldEntry extends Entry<b2WorldId, WorldKey> {
        final Box2dWorldSpec spec;
        final b2WorldId nativeId;
        final b2Vec2 vectorScratch = new b2Vec2();
        final b2Counters countersScratch = new b2Counters();
        WorldEntry(String id, EntityId entityId, WorldKey key, Box2dWorldSpec spec) {
            super(id, entityId, key, null);
            this.spec = spec;
            nativeId = key.toId();
        }
        @Override b2WorldId live() { return requireLive(nativeId); }
        @Override WorldKey copyAndValidate(b2WorldId value) { return WorldKey.copyOf(requireLive(value)); }
        @Override void setKey(WorldKey replacement) {
            key = replacement;
            replacement.copyTo(nativeId);
        }
        @Override Map<String, WorldEntry> map() { return worlds; }
        @Override void requireNoDescendants() {
            if (bodies.values().stream().anyMatch(body -> id.equals(body.parentId))
                    || joints.values().stream().anyMatch(joint -> id.equals(joint.parentId))) {
                throw new IllegalStateException("unregister world descendants first");
            }
        }
    }

    private final class BodyEntry extends Entry<b2BodyId, BodyKey> {
        final b2BodyId nativeId;
        final b2Vec2 positionScratch = new b2Vec2();
        final b2Vec2 velocityScratch = new b2Vec2();
        final b2Rot rotationScratch = new b2Rot();
        BodyEntry(String id, EntityId entityId, BodyKey key, String worldId) {
            super(id, entityId, key, worldId);
            nativeId = key.toId();
        }
        @Override b2BodyId live() { return requireLive(nativeId); }
        @Override BodyKey copyAndValidate(b2BodyId value) { return BodyKey.copyOf(requireLive(value)); }
        @Override void setKey(BodyKey replacement) {
            key = replacement;
            replacement.copyTo(nativeId);
        }
        @Override Map<String, BodyEntry> map() { return bodies; }
        @Override void validateParent(BodyKey replacement) {
            b2BodyId id = replacement.toId();
            if (!WorldKey.copyOf(Box2d.b2Body_GetWorld(id)).equals(worlds.get(parentId).key)) {
                throw new IllegalArgumentException("body does not belong to the registered world");
            }
        }
        @Override void requireNoDescendants() {
            if (shapes.values().stream().anyMatch(shape -> id.equals(shape.parentId))
                    || joints.values().stream().anyMatch(
                            joint -> id.equals(joint.bodyA) || id.equals(joint.bodyB))) {
                throw new IllegalStateException("unregister body shapes and joints first");
            }
        }
    }

    private final class ShapeEntry extends Entry<b2ShapeId, ShapeKey> {
        final Box2dShapeSpec spec;
        final b2ShapeId nativeId;
        final b2Filter filterScratch = new b2Filter();
        final Box2dShapeValues.Scratch geometryScratch = new Box2dShapeValues.Scratch();
        ShapeEntry(String id, EntityId entityId, ShapeKey key, String bodyId, Box2dShapeSpec spec) {
            super(id, entityId, key, bodyId);
            this.spec = spec;
            nativeId = key.toId();
        }
        @Override b2ShapeId live() { return requireLive(nativeId); }
        @Override ShapeKey copyAndValidate(b2ShapeId value) { return ShapeKey.copyOf(requireLive(value)); }
        @Override void setKey(ShapeKey replacement) {
            key = replacement;
            replacement.copyTo(nativeId);
        }
        @Override Map<String, ShapeEntry> map() { return shapes; }
        @Override void validateParent(ShapeKey replacement) {
            if (!BodyKey.copyOf(Box2d.b2Shape_GetBody(replacement.toId()))
                    .equals(bodies.get(parentId).key)) {
                throw new IllegalArgumentException("shape does not belong to the registered body");
            }
        }
    }

    private final class JointEntry extends Entry<b2JointId, JointKey> {
        final String bodyA;
        final String bodyB;
        final b2JointId nativeId;
        final b2Vec2 anchorAScratch = new b2Vec2();
        final b2Vec2 anchorBScratch = new b2Vec2();
        final b2Vec2 forceScratch = new b2Vec2();
        JointEntry(String id, EntityId entityId, JointKey key, String worldId,
                String bodyA, String bodyB) {
            super(id, entityId, key, worldId);
            this.bodyA = bodyA;
            this.bodyB = bodyB;
            nativeId = key.toId();
        }
        @Override b2JointId live() { return requireLive(nativeId); }
        @Override JointKey copyAndValidate(b2JointId value) { return JointKey.copyOf(requireLive(value)); }
        @Override void setKey(JointKey replacement) {
            key = replacement;
            replacement.copyTo(nativeId);
        }
        @Override Map<String, JointEntry> map() { return joints; }
        @Override void validateParent(JointKey replacement) {
            b2JointId id = replacement.toId();
            if (!WorldKey.copyOf(Box2d.b2Joint_GetWorld(id)).equals(worlds.get(parentId).key)
                    || !bodyA.equals(bodyId(BodyKey.copyOf(Box2d.b2Joint_GetBodyA(id))))
                    || !bodyB.equals(bodyId(BodyKey.copyOf(Box2d.b2Joint_GetBodyB(id))))) {
                throw new IllegalArgumentException("joint world or endpoints differ from registration");
            }
        }
    }

    private final class Registration<T, K> implements Box2dRegistration<T> {
        private final Entry<T, K> entry;
        Registration(Entry<T, K> entry) { this.entry = entry; }
        @Override public String id() { return entry.id; }
        @Override public EntityId runtimeEntityId() { return entry.entityId; }
        @Override public void rebind(T value) { Box2dInspection.this.rebind(entry, value); }
        @Override public void close() { remove(entry); }
    }

    private record WorldKey(char index1, char generation) {
        static WorldKey copyOf(b2WorldId id) { return new WorldKey(id.index1(), id.generation()); }
        b2WorldId toId() {
            b2WorldId id = new b2WorldId();
            id.index1(index1);
            id.generation(generation);
            return id;
        }
        void copyTo(b2WorldId id) {
            id.index1(index1);
            id.generation(generation);
        }
    }

    private record BodyKey(int index1, char world0, char generation) {
        static BodyKey copyOf(b2BodyId id) {
            return new BodyKey(id.index1(), id.world0(), id.generation());
        }
        b2BodyId toId() {
            b2BodyId id = new b2BodyId();
            id.index1(index1);
            id.world0(world0);
            id.generation(generation);
            return id;
        }
        void copyTo(b2BodyId id) {
            id.index1(index1);
            id.world0(world0);
            id.generation(generation);
        }
    }

    private record ShapeKey(int index1, char world0, char generation) {
        static ShapeKey copyOf(b2ShapeId id) {
            return new ShapeKey(id.index1(), id.world0(), id.generation());
        }
        b2ShapeId toId() {
            b2ShapeId id = new b2ShapeId();
            id.index1(index1);
            id.world0(world0);
            id.generation(generation);
            return id;
        }
        void copyTo(b2ShapeId id) {
            id.index1(index1);
            id.world0(world0);
            id.generation(generation);
        }
    }

    private record JointKey(int index1, char world0, char generation) {
        static JointKey copyOf(b2JointId id) {
            return new JointKey(id.index1(), id.world0(), id.generation());
        }
        b2JointId toId() {
            b2JointId id = new b2JointId();
            id.index1(index1);
            id.world0(world0);
            id.generation(generation);
            return id;
        }
        void copyTo(b2JointId id) {
            id.index1(index1);
            id.world0(world0);
            id.generation(generation);
        }
    }
}
