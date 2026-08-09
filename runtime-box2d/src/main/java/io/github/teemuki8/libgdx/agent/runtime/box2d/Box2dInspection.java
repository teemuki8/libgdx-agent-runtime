package io.github.teemuki8.libgdx.agent.runtime.box2d;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.Filter;
import com.badlogic.gdx.physics.box2d.Fixture;
import com.badlogic.gdx.physics.box2d.Joint;
import com.badlogic.gdx.physics.box2d.Shape;
import com.badlogic.gdx.physics.box2d.World;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityRegistration;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/** Explicit bounded registration adapter for selected application-owned Box2D objects. */
public final class Box2dInspection implements AutoCloseable {
    private final AgentRuntime runtime;
    private final Box2dAdapterLimits limits;
    private final Thread ownerThread;
    private final LinkedHashMap<String, WorldEntry> worlds = new LinkedHashMap<>();
    private final LinkedHashMap<String, BodyEntry> bodies = new LinkedHashMap<>();
    private final LinkedHashMap<String, FixtureEntry> fixtures = new LinkedHashMap<>();
    private final LinkedHashMap<String, JointEntry> joints = new LinkedHashMap<>();
    private boolean closed;

    /** Creates an adapter owned by the calling application/capture thread. */
    public Box2dInspection(AgentRuntime runtime, Box2dAdapterLimits limits) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.limits = Objects.requireNonNull(limits, "limits");
        ownerThread = Thread.currentThread();
    }

    /** Registers one selected world and explicit solver/unit testimony. */
    public Box2dRegistration<World> registerWorld(
            String id, World world, Box2dWorldSpec spec) {
        requireOwnerOpen();
        validateId(id);
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(spec, "spec");
        requireCapacity(worlds, limits.worlds(), "world");
        requireUnique(id, world, worlds, "world");
        WorldEntry entry = new WorldEntry(id, entityId("world", id), world, spec);
        entry.entityRegistration = runtime.entities().register(entry.entityId,
                EntityType.of("box2d.world"), () -> id, inspector -> declareWorld(inspector, entry));
        worlds.put(id, entry);
        return handle(entry);
    }

    /** Registers one selected body owned by an already registered world. */
    public Box2dRegistration<Body> registerBody(String id, String worldId, Body body) {
        requireOwnerOpen();
        validateId(id);
        Body value = Objects.requireNonNull(body, "body");
        WorldEntry world = requireEntry(worlds, worldId, "world");
        if (value.getWorld() != live(world)) {
            throw new IllegalArgumentException("body does not belong to the registered world");
        }
        requireCapacity(bodies, limits.bodies(), "body");
        requireUnique(id, value, bodies, "body");
        BodyEntry entry = new BodyEntry(id, entityId("body", id), value, worldId);
        entry.entityRegistration = runtime.entities().register(entry.entityId,
                EntityType.of("box2d.body"), () -> id, inspector -> declareBody(inspector, entry));
        bodies.put(id, entry);
        return handle(entry);
    }

    /** Registers one selected fixture owned by an already registered body. */
    public Box2dRegistration<Fixture> registerFixture(String id, String bodyId, Fixture fixture) {
        return registerFixture(id, bodyId, fixture, Box2dFixtureSpec.unspecified());
    }

    /** Registers one selected fixture with explicit metadata unavailable from its native wrapper. */
    public Box2dRegistration<Fixture> registerFixture(String id, String bodyId, Fixture fixture,
            Box2dFixtureSpec spec) {
        requireOwnerOpen();
        validateId(id);
        Fixture value = Objects.requireNonNull(fixture, "fixture");
        Box2dFixtureSpec metadata = Objects.requireNonNull(spec, "spec");
        if (value.getType() == Shape.Type.Chain && metadata.chainLoop().isEmpty()) {
            throw new IllegalArgumentException("chain fixture requires explicit loop-state testimony");
        }
        BodyEntry body = requireEntry(bodies, bodyId, "body");
        if (value.getBody() != live(body)) {
            throw new IllegalArgumentException("fixture does not belong to the registered body");
        }
        requireCapacity(fixtures, limits.fixtures(), "fixture");
        requireUnique(id, value, fixtures, "fixture");
        FixtureEntry entry = new FixtureEntry(
                id, entityId("fixture", id), value, bodyId, metadata);
        entry.entityRegistration = runtime.entities().register(entry.entityId,
                EntityType.of("box2d.fixture"), () -> id,
                inspector -> declareFixture(inspector, entry));
        fixtures.put(id, entry);
        return handle(entry);
    }

    /** Registers one selected joint whose endpoints are already registered bodies. */
    public Box2dRegistration<Joint> registerJoint(String id, String worldId, Joint joint) {
        requireOwnerOpen();
        validateId(id);
        Joint value = Objects.requireNonNull(joint, "joint");
        WorldEntry world = requireEntry(worlds, worldId, "world");
        String bodyA = bodyId(value.getBodyA());
        String bodyB = bodyId(value.getBodyB());
        if (value.getBodyA().getWorld() != live(world) || value.getBodyB().getWorld() != live(world)) {
            throw new IllegalArgumentException("joint does not belong to the registered world");
        }
        requireCapacity(joints, limits.joints(), "joint");
        requireUnique(id, value, joints, "joint");
        JointEntry entry = new JointEntry(id, entityId("joint", id), value, worldId, bodyA, bodyB);
        entry.entityRegistration = runtime.entities().register(entry.entityId,
                EntityType.of("box2d.joint"), () -> id,
                inspector -> Box2dJointValues.declare(inspector, entry, worlds));
        joints.put(id, entry);
        return handle(entry);
    }

    /** Returns configured adapter limits. */
    public Box2dAdapterLimits limits() {
        return limits;
    }

    /** Releases registrations and weak native references without disposing Box2D objects. */
    @Override public void close() {
        requireOwner();
        if (closed) {
            return;
        }
        closed = true;
        if (runtime.status() != RuntimeStatus.CLOSED) {
            joints.values().forEach(Entry::closeProvider);
            fixtures.values().forEach(Entry::closeProvider);
            bodies.values().forEach(Entry::closeProvider);
            worlds.values().forEach(Entry::closeProvider);
        }
        clearEntries(joints);
        clearEntries(fixtures);
        clearEntries(bodies);
        clearEntries(worlds);
    }

    private void declareWorld(io.github.teemuki8.libgdx.agent.runtime.core.EntityInspector inspector,
            WorldEntry entry) {
        inspector.property("id", () -> RuntimeValues.string(entry.id))
                .property("runtimeEntityId", () -> RuntimeValues.string(entry.entityId.value()))
                .property("gravity", () -> vector(live(entry).getGravity()))
                .property("sleepingAllowed", () -> entry.spec.sleepingAllowed())
                .property("warmStarting", () -> entry.spec.warmStarting())
                .property("continuousPhysics", () -> entry.spec.continuousPhysics())
                .property("velocityIterations", () -> (long) entry.spec.velocityIterations())
                .property("positionIterations", () -> (long) entry.spec.positionIterations())
                .property("fixedStepNanos", () -> fixedStep())
                .property("registeredBodyCount", () -> count(bodies, entry.id))
                .property("registeredFixtureCount", () -> countFixtures(entry.id))
                .property("registeredJointCount", () -> count(joints, entry.id))
                .property("totalBodyCount", () -> (long) live(entry).getBodyCount())
                .property("totalFixtureCount", () -> (long) live(entry).getFixtureCount())
                .property("totalJointCount", () -> (long) live(entry).getJointCount())
                .property("totalContactCount", () -> (long) live(entry).getContactCount())
                .property("locked", () -> live(entry).isLocked())
                .property("renderUnitsPerMeter",
                        () -> RuntimeValues.decimal(entry.spec.unitTransform().renderUnitsPerMeter()));
    }

    private void declareBody(io.github.teemuki8.libgdx.agent.runtime.core.EntityInspector inspector,
            BodyEntry entry) {
        inspector.property("id", () -> RuntimeValues.string(entry.id))
                .property("runtimeEntityId", () -> RuntimeValues.string(entry.entityId.value()))
                .property("worldId", () -> RuntimeValues.string(entry.parentId))
                .property("bodyType", () -> RuntimeValues.enumValue(bodyType(live(entry))))
                .property("position", () -> vector(live(entry).getPosition()))
                .property("angleRadians", () -> decimal(live(entry).getAngle()))
                .property("linearVelocity", () -> vector(live(entry).getLinearVelocity()))
                .property("angularVelocity",
                        () -> decimal(live(entry).getAngularVelocity()))
                .property("mass", () -> decimal(live(entry).getMass()))
                .property("inertia", () -> decimal(live(entry).getInertia()))
                .property("gravityScale", () -> decimal(live(entry).getGravityScale()))
                .property("linearDamping",
                        () -> decimal(live(entry).getLinearDamping()))
                .property("angularDamping",
                        () -> decimal(live(entry).getAngularDamping()))
                .property("awake", () -> live(entry).isAwake())
                .property("active", () -> live(entry).isActive())
                .property("bullet", () -> live(entry).isBullet())
                .property("fixedRotation", () -> live(entry).isFixedRotation())
                .property("sleepingAllowed", () -> live(entry).isSleepingAllowed())
                .property("registeredFixtureCount", () -> count(fixtures, entry.id))
                .property("totalFixtureCount", () -> (long) live(entry).getFixtureList().size);
    }

    private void declareFixture(
            io.github.teemuki8.libgdx.agent.runtime.core.EntityInspector inspector,
            FixtureEntry entry) {
        inspector.property("id", () -> RuntimeValues.string(entry.id))
                .property("runtimeEntityId", () -> RuntimeValues.string(entry.entityId.value()))
                .property("bodyId", () -> RuntimeValues.string(entry.parentId))
                .property("shapeType", () -> RuntimeValues.enumValue(
                        live(entry).getType().name().toUpperCase(Locale.ROOT)))
                .property("sensor", () -> live(entry).isSensor())
                .property("density", () -> decimal(live(entry).getDensity()))
                .property("friction", () -> decimal(live(entry).getFriction()))
                .property("restitution", () -> decimal(live(entry).getRestitution()))
                .property("categoryBits", () -> (long) Short.toUnsignedInt(filter(entry).categoryBits))
                .property("maskBits", () -> (long) Short.toUnsignedInt(filter(entry).maskBits))
                .property("groupIndex", () -> (long) filter(entry).groupIndex)
                .property("geometry", () -> Box2dShapeValues.copy(
                        live(entry).getShape(), limits.shapeVertices(), entry.spec))
                .property("diagnostics", () -> Box2dShapeValues.diagnostics(
                        live(entry).getShape(), limits.shapeVertices(),
                        limits.diagnosticEntries()));
    }

    private RuntimeValue fixedStep() {
        OptionalLong value = runtime.simulation().state().configuredFixedStepNanos();
        return value.isPresent() ? RuntimeValues.integer(value.orElseThrow())
                : RuntimeValues.nullValue();
    }

    private Filter filter(FixtureEntry entry) {
        return live(entry).getFilterData();
    }

    private long count(Map<String, ? extends Entry<?>> entries, String parentId) {
        return entries.values().stream().filter(entry -> parentId.equals(entry.parentId)).count();
    }

    private long countFixtures(String worldId) {
        return fixtures.values().stream().filter(fixture -> {
            BodyEntry body = bodies.get(fixture.parentId);
            return body != null && worldId.equals(body.parentId);
        }).count();
    }

    private String bodyId(Body body) {
        return bodies.values().stream().filter(entry -> entry.reference.get() == body)
                .map(entry -> entry.id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "joint endpoints must be explicitly registered bodies"));
    }

    private <T> Box2dRegistration<T> handle(Entry<T> entry) {
        return new Registration<>(entry);
    }

    private void rebind(Entry<?> entry, Object value) {
        requireOwnerOpen();
        if (entry.closed) {
            throw new IllegalStateException("Box2D registration is closed");
        }
        Objects.requireNonNull(value, "value");
        if (entry instanceof WorldEntry world) {
            if (bodies.values().stream().anyMatch(body -> body.parentId.equals(world.id))
                    || joints.values().stream().anyMatch(joint -> joint.parentId.equals(world.id))) {
                throw new IllegalStateException("unregister world descendants before rebinding");
            }
            requireUniqueNative(value, worlds, entry);
        } else if (entry instanceof BodyEntry body) {
            requireNoBodyDescendants(body);
            Body nativeBody = (Body) value;
            if (nativeBody.getWorld() != live(requireEntry(worlds, body.parentId, "world"))) {
                throw new IllegalArgumentException("body does not belong to the registered world");
            }
            requireUniqueNative(value, bodies, entry);
        } else if (entry instanceof FixtureEntry fixture) {
            Fixture nativeFixture = (Fixture) value;
            if (nativeFixture.getBody() != live(requireEntry(bodies, fixture.parentId, "body"))) {
                throw new IllegalArgumentException("fixture does not belong to the registered body");
            }
            requireUniqueNative(value, fixtures, entry);
        } else if (entry instanceof JointEntry joint) {
            Joint nativeJoint = (Joint) value;
            if (!bodyId(nativeJoint.getBodyA()).equals(joint.bodyA)
                    || !bodyId(nativeJoint.getBodyB()).equals(joint.bodyB)) {
                throw new IllegalArgumentException("joint endpoints differ from registration");
            }
            requireUniqueNative(value, joints, entry);
        }
        entry.rebind(value);
    }

    private void remove(Entry<?> entry) {
        requireOwner();
        if (entry.closed) {
            return;
        }
        if (entry instanceof WorldEntry world
                && (bodies.values().stream().anyMatch(body -> body.parentId.equals(world.id))
                        || joints.values().stream().anyMatch(
                                joint -> joint.parentId.equals(world.id)))) {
            throw new IllegalStateException("unregister world descendants before unregistering");
        }
        if (entry instanceof BodyEntry body) {
            requireNoBodyDescendants(body);
        }
        entry.closed = true;
        if (runtime.status() != RuntimeStatus.CLOSED) {
            entry.closeProvider();
        }
        entry.reference.clear();
        map(entry).remove(entry.id, entry);
    }

    private void requireNoBodyDescendants(BodyEntry body) {
        if (fixtures.values().stream().anyMatch(fixture -> fixture.parentId.equals(body.id))
                || joints.values().stream().anyMatch(
                        joint -> joint.bodyA.equals(body.id) || joint.bodyB.equals(body.id))) {
            throw new IllegalStateException("unregister body fixtures and joints first");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Entry<?>> map(Entry<?> entry) {
        if (entry instanceof WorldEntry) {
            return (Map<String, Entry<?>>) (Map<?, ?>) worlds;
        } else if (entry instanceof BodyEntry) {
            return (Map<String, Entry<?>>) (Map<?, ?>) bodies;
        } else if (entry instanceof FixtureEntry) {
            return (Map<String, Entry<?>>) (Map<?, ?>) fixtures;
        }
        return (Map<String, Entry<?>>) (Map<?, ?>) joints;
    }

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

    private static <T, E extends Entry<T>> void requireUnique(
            String id, T value, Map<String, E> entries, String kind) {
        if (entries.containsKey(id)) {
            throw new IllegalArgumentException("duplicate Box2D " + kind + " ID");
        }
        requireUniqueNative(value, entries, null);
    }

    private static void requireUniqueNative(
            Object value, Map<String, ? extends Entry<?>> entries, Entry<?> ignored) {
        if (entries.values().stream().anyMatch(
                entry -> entry != ignored && entry.reference.get() == value)) {
            throw new IllegalArgumentException("native Box2D object is already registered");
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

    static <T> T live(Entry<T> entry) {
        T value = entry.reference.get();
        if (value == null) {
            throw new IllegalStateException("registered Box2D object is no longer live; rebind it");
        }
        return value;
    }

    static RuntimeValue.Vector2Value vector(Vector2 value) {
        return new RuntimeValue.Vector2Value(decimal(value.x), decimal(value.y));
    }

    static RuntimeValue.DecimalValue decimal(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Box2D float value must be finite");
        }
        return RuntimeValues.decimal(Float.toString(value));
    }

    private static String bodyType(Body body) {
        return switch (body.getType()) {
            case StaticBody -> "STATIC";
            case KinematicBody -> "KINEMATIC";
            case DynamicBody -> "DYNAMIC";
        };
    }

    private static void clearEntries(Map<String, ? extends Entry<?>> entries) {
        entries.values().forEach(entry -> entry.reference.clear());
        entries.clear();
    }

    abstract static class Entry<T> {
        final String id;
        final EntityId entityId;
        final String parentId;
        WeakReference<T> reference;
        EntityRegistration entityRegistration;
        boolean closed;

        Entry(String id, EntityId entityId, T value, String parentId) {
            this.id = id;
            this.entityId = entityId;
            this.parentId = parentId;
            reference = new WeakReference<>(value);
        }

        @SuppressWarnings("unchecked")
        void rebind(Object value) {
            reference.clear();
            reference = new WeakReference<>((T) value);
        }

        void closeProvider() {
            if (entityRegistration != null) {
                entityRegistration.close();
                entityRegistration = null;
            }
        }
    }

    static final class WorldEntry extends Entry<World> {
        final Box2dWorldSpec spec;

        WorldEntry(String id, EntityId entityId, World value, Box2dWorldSpec spec) {
            super(id, entityId, value, null);
            this.spec = spec;
        }
    }

    static final class BodyEntry extends Entry<Body> {
        BodyEntry(String id, EntityId entityId, Body value, String worldId) {
            super(id, entityId, value, worldId);
        }
    }

    static final class FixtureEntry extends Entry<Fixture> {
        final Box2dFixtureSpec spec;

        FixtureEntry(String id, EntityId entityId, Fixture value, String bodyId,
                Box2dFixtureSpec spec) {
            super(id, entityId, value, bodyId);
            this.spec = spec;
        }
    }

    static final class JointEntry extends Entry<Joint> {
        final String bodyA;
        final String bodyB;

        JointEntry(String id, EntityId entityId, Joint value, String worldId,
                String bodyA, String bodyB) {
            super(id, entityId, value, worldId);
            this.bodyA = bodyA;
            this.bodyB = bodyB;
        }
    }

    private final class Registration<T> implements Box2dRegistration<T> {
        private final Entry<T> entry;

        Registration(Entry<T> entry) {
            this.entry = entry;
        }

        @Override public String id() {
            return entry.id;
        }

        @Override public EntityId runtimeEntityId() {
            return entry.entityId;
        }

        @Override public void rebind(T value) {
            Box2dInspection.this.rebind(entry, value);
        }

        @Override public void close() {
            remove(entry);
        }
    }
}
