package io.github.teemuki8.libgdx.agent.runtime.box2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.Box2D;
import com.badlogic.gdx.physics.box2d.CircleShape;
import com.badlogic.gdx.physics.box2d.Contact;
import com.badlogic.gdx.physics.box2d.ContactImpulse;
import com.badlogic.gdx.physics.box2d.ContactListener;
import com.badlogic.gdx.physics.box2d.Fixture;
import com.badlogic.gdx.physics.box2d.Manifold;
import com.badlogic.gdx.physics.box2d.PolygonShape;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.physics.box2d.WorldManifold;
import com.badlogic.gdx.utils.GdxNativesLoader;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.Truncation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Box2dContactsTest {
    private static final long STEP_NANOS = 16_666_667L;
    private static final float STEP_SECONDS = 1f / 60f;

    @BeforeAll
    static void initializeNativeBox2d() {
        GdxNativesLoader.load();
        Box2D.init();
    }

    @Test
    void registrationDoesNotInstallAListenerAndDirectCaptureCopiesEveryPhase() {
        try (Scene scene = new Scene("contact-direct")) {
            AtomicInteger applicationBegins = new AtomicInteger();
            scene.world.setContactListener(listener(applicationBegins::incrementAndGet));
            Box2dContacts contacts = scene.registerContacts(Box2dContactPolicy.developmentDefaults());

            scene.world.step(STEP_SECONDS, 6, 2);
            assertEquals(1, applicationBegins.get());
            assertTrue(contacts.ticks(1, 1, 16).ticks().isEmpty());

            scene.separate();
            scene.world.step(STEP_SECONDS, 6, 2);
            scene.collide();
            scene.world.setContactListener(contacts.listener());
            scene.start();
            scene.tick(contacts);
            scene.separate();
            scene.tick(contacts);

            List<Box2dContactRecord.Phase> phases = contacts.ticks(1, 2, 16).ticks().stream()
                    .flatMap(tick -> tick.records().stream())
                    .map(Box2dContactRecord::phase).distinct()
                    .sorted(Comparator.naturalOrder()).toList();
            assertEquals(List.of(Box2dContactRecord.Phase.BEGIN,
                    Box2dContactRecord.Phase.END, Box2dContactRecord.Phase.POST_SOLVE), phases);
            assertTrue(contacts.ticks(1, 2, 16).ticks().stream()
                    .allMatch(Box2dContactTick::complete));
        }
    }

    @Test
    void composedListenerRunsSecondAndFailureIsRethrownWithoutItsMessage() {
        try (Scene scene = new Scene("contact-composed")) {
            Box2dContacts contacts = scene.registerContacts(
                    new Box2dContactPolicy(true, true, true, true));
            RuntimeException expected = new RuntimeException("secret application detail");
            ContactListener application = new ContactListener() {
                @Override public void beginContact(Contact contact) {
                    contact.getFixtureA().setSensor(true);
                    contact.getFixtureB().setSensor(true);
                    throw expected;
                }

                @Override public void endContact(Contact contact) {}

                @Override public void preSolve(Contact contact, Manifold oldManifold) {}

                @Override public void postSolve(Contact contact, ContactImpulse impulse) {}
            };
            ContactListener composed = contacts.compose(application);
            scene.world.setContactListener(composed);
            scene.start();

            RuntimeException actual = assertThrows(RuntimeException.class,
                    () -> scene.tick(contacts));
            assertSame(expected, actual);
            Box2dContactTick tick = contacts.ticks(1, 1, 16).ticks().getFirst();
            assertFalse(tick.records().getFirst().endpointA().sensor());
            assertFalse(tick.records().getFirst().endpointB().sensor());
            assertTrue(tick.diagnostics().stream().anyMatch(value ->
                    value.code() == Box2dContactTick.DiagnosticCode.APPLICATION_LISTENER_FAILED));
            assertTrue(tick.diagnostics().stream().noneMatch(value ->
                    value.toString().contains("secret application detail")));
            assertThrows(IllegalStateException.class, () -> contacts.compose(application));
        }
    }

    @Test
    void nativeOrientationIsCanonicalAndCopiesDirectionalValuesImmediately() {
        try (Scene scene = new Scene("contact-orientation")) {
            scene.world.step(STEP_SECONDS, 6, 2);
            Contact discovered = scene.world.getContactList().first();
            Fixture nativeA = discovered.getFixtureA();
            Fixture nativeB = discovered.getFixtureB();
            scene.separate();
            scene.world.step(STEP_SECONDS, 6, 2);

            scene.registerExplicit(nativeA, "z-native-a", nativeB, "a-native-b");
            Box2dContacts contacts = scene.inspection.registerContacts("main",
                    Box2dContactLimits.developmentDefaults(),
                    new Box2dContactPolicy(true, true, true, true));
            NativePostSolve application = new NativePostSolve();
            scene.world.setContactListener(contacts.compose(application));
            scene.collide();
            scene.start();
            scene.tick(contacts);

            Box2dContactRecord post = contacts.ticks(1, 1, 16).ticks().getFirst().records().stream()
                    .filter(value -> value.phase() == Box2dContactRecord.Phase.POST_SOLVE)
                    .findFirst().orElseThrow();
            assertEquals("a-native-b", post.key().fixtureAId());
            assertEquals("z-native-a", post.key().fixtureBId());
            assertEquals(application.childB, post.key().childIndexA());
            assertEquals(application.childA, post.key().childIndexB());
            assertEquals(application.points, post.points());
            assertEquals(new Box2dVector(-application.normal.x(), -application.normal.y()),
                    post.normal().orElseThrow());
            assertEquals(application.normalImpulses.length, post.impulses().size());
            for (int index = 0; index < post.impulses().size(); index++) {
                assertEquals(application.normalImpulses[index],
                        post.impulses().get(index).normal(), 0.000_001);
                assertEquals(-application.tangentImpulses[index],
                        post.impulses().get(index).tangent(), 0.000_001);
            }
            assertTrue(contacts.ticks(1, 1, 16).ticks().getFirst().records().stream()
                    .filter(value -> value.phase() == Box2dContactRecord.Phase.BEGIN
                            || value.phase() == Box2dContactRecord.Phase.END)
                    .allMatch(value -> value.points().isEmpty() && value.normal().isEmpty()
                            && value.impulses().isEmpty() && value.oldManifold().isEmpty()));

            scene.ballFixture.setSensor(true);
            scene.ball.setTransform(12, 12, 0);
            assertEquals(application.points, post.points());
            assertFalse(post.endpointA().sensor());
            assertFalse(post.endpointB().sensor());
        }
    }

    @Test
    void unmappedEndpointsNeverExposePartialIdentityAndBoundsAreExplicit() {
        try (Scene scene = new Scene("contact-bounds")) {
            scene.registerWorldAndBodies();
            scene.inspection.registerFixture("ground", "ground-body", scene.groundFixture);
            Box2dContactLimits limits = new Box2dContactLimits(1, 1, 1, 1, 1, 8, 2, 2);
            Box2dContacts contacts = scene.inspection.registerContacts(
                    "main", limits, new Box2dContactPolicy(true, true, true, true));
            scene.world.setContactListener(contacts.listener());
            scene.start();
            scene.tick(contacts);

            Box2dContactTick unmapped = contacts.ticks(1, 1, 2).ticks().getFirst();
            assertTrue(unmapped.records().isEmpty());
            assertTrue(unmapped.unmappedContactsObserved() > 0);
            assertTrue(unmapped.diagnostics().stream().anyMatch(value ->
                    value.code() == Box2dContactTick.DiagnosticCode.UNMAPPED_ENDPOINT));
            assertFalse(unmapped.complete());

            scene.inspection.registerFixture("ball", "ball-body", scene.ballFixture);
            scene.separate();
            scene.tick(contacts);
            scene.collide();
            scene.tick(contacts);
            scene.tick(contacts);

            Box2dContactTick bounded = contacts.ticks(2, 4, 2).ticks().get(1);
            assertEquals(1, bounded.records().size());
            assertTrue(bounded.callbackRecordsObserved() > bounded.callbackRecordsRetained());
            assertTrue(bounded.truncations().contains(new Truncation(
                    "box2d.contact.records", bounded.callbackRecordsObserved(), 1, 1)));
            Box2dContactTickPage page = contacts.ticks(1, 4, 2);
            assertEquals(Box2dContactTickPage.RangeStatus.PARTIALLY_EVICTED, page.rangeStatus());
            assertEquals(2, page.ticks().size());
            assertEquals(3, page.oldestRetainedTickId().orElseThrow().value());
            assertEquals(4, page.newestRetainedTickId().orElseThrow().value());
        }
    }

    @Test
    void activeContactsAndOldManifoldCopiesRemainBounded() {
        try (Scene scene = new Scene("contact-active-bound")) {
            scene.registerExplicit(scene.groundFixture, "ground", scene.ballFixture, "ball-a");
            BodyDef secondDefinition = new BodyDef();
            secondDefinition.type = BodyDef.BodyType.DynamicBody;
            secondDefinition.position.set(2, 0.9f);
            Body second = scene.world.createBody(secondDefinition);
            CircleShape circle = new CircleShape();
            circle.setRadius(0.5f);
            Fixture secondFixture = second.createFixture(circle, 1);
            circle.dispose();
            second.setLinearVelocity(-3, -1);
            scene.inspection.registerBody("second-body", "main", second);
            scene.inspection.registerFixture("ball-b", "second-body", secondFixture);
            Box2dContactLimits limits = new Box2dContactLimits(32, 1, 2, 2, 1, 8, 8, 8);
            Box2dContacts contacts = scene.inspection.registerContacts(
                    "main", limits, new Box2dContactPolicy(true, true, true, true));
            scene.world.setContactListener(contacts.listener());
            scene.start();
            scene.tick(contacts);
            scene.tick(contacts);

            Box2dContactTick first = contacts.ticks(1, 2, 8).ticks().getFirst();
            assertEquals(2, first.activeContactsObserved());
            assertEquals(1, first.activeContactsRetained());
            assertEquals(1, first.activeContacts().size());
            assertTrue(first.truncations().contains(
                    new Truncation("box2d.contact.active", 2, 1, 1)));
            assertFalse(first.complete());

            Box2dContactRecord preSolve = contacts.ticks(1, 2, 8).ticks().get(1).records().stream()
                    .filter(value -> value.phase() == Box2dContactRecord.Phase.PRE_SOLVE)
                    .findFirst().orElseThrow();
            assertEquals(Box2dContactRecord.Availability.CURRENT_AND_OLD_MANIFOLD,
                    preSolve.availability());
            assertTrue(preSolve.oldManifold().isPresent());
            assertTrue(preSolve.oldManifold().orElseThrow().points().size() <= 1);
            assertTrue(preSolve.impulses().isEmpty());
        }
    }

    @Test
    void callbacksOutsideCaptureRetainOnlyAClosedDiagnosticCount() {
        try (Scene scene = new Scene("contact-outside")) {
            Box2dContacts contacts = scene.registerContacts(Box2dContactPolicy.developmentDefaults());
            scene.world.setContactListener(contacts.listener());
            scene.world.step(STEP_SECONDS, 6, 2);
            scene.start();
            scene.tick(contacts);

            Box2dContactTick tick = contacts.ticks(1, 1, 16).ticks().getFirst();
            assertTrue(tick.records().stream().noneMatch(value ->
                    value.phase() == Box2dContactRecord.Phase.BEGIN));
            assertTrue(tick.activeContacts().isEmpty());
            assertTrue(tick.diagnostics().stream().anyMatch(value ->
                    value.code() == Box2dContactTick.DiagnosticCode.CALLBACK_OUTSIDE_TICK
                            && value.observed() > 0));
            assertFalse(tick.complete());
        }
    }

    @Test
    void missingCapturedTickInsideRetainedRangeIsNeverReportedComplete() {
        try (Scene scene = new Scene("contact-history-gap")) {
            Box2dContacts contacts = scene.registerContacts(
                    Box2dContactPolicy.developmentDefaults());
            scene.world.setContactListener(contacts.listener());
            scene.start();
            scene.tick(contacts);
            scene.runtime.simulation().tick(STEP_NANOS, supplied -> supplied);
            scene.tick(contacts);

            Box2dContactTickPage missing = contacts.ticks(2, 2, 16);
            assertTrue(missing.ticks().isEmpty());
            assertEquals(Box2dContactTickPage.RangeStatus.NOT_YET_CAPTURED,
                    missing.rangeStatus());
        }
    }

    @Test
    void lifecycleStepCorrelationAndCompletedReadsAreDeterministic() throws Exception {
        try (Scene scene = new Scene("contact-lifecycle")) {
            Box2dContacts contacts = scene.registerContacts(Box2dContactPolicy.developmentDefaults());
            assertThrows(IllegalArgumentException.class, () -> scene.inspection.registerContacts(
                    "main", Box2dContactLimits.developmentDefaults(),
                    Box2dContactPolicy.developmentDefaults()));
            scene.world.setContactListener(contacts.listener());

            assertThrows(IllegalStateException.class,
                    () -> contacts.captureStep(() -> scene.world.step(STEP_SECONDS, 6, 2)));
            AtomicReference<Throwable> wrongThread = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    contacts.captureStep(() -> {});
                } catch (Throwable failure) {
                    wrongThread.set(failure);
                }
            });
            thread.start();
            thread.join();
            assertTrue(wrongThread.get() instanceof IllegalStateException);

            scene.start();
            scene.runtime.simulation().tick(STEP_NANOS, supplied -> {
                contacts.captureStep(() -> scene.world.step(STEP_SECONDS, 6, 2));
                assertThrows(IllegalStateException.class, () -> contacts.captureStep(() -> {}));
                return supplied;
            });
            AtomicReference<Box2dContactTickPage> concurrent = new AtomicReference<>();
            Thread reader = new Thread(() -> concurrent.set(contacts.ticks(1, 1, 16)));
            reader.start();
            reader.join();
            assertEquals(contacts.ticks(1, 1, 16), concurrent.get());

            assertThrows(IllegalArgumentException.class, () -> contacts.ticks(0, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> contacts.ticks(2, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> contacts.ticks(1, 1, 257));
            contacts.close();
            contacts.close();
            assertThrows(IllegalStateException.class, () -> contacts.captureStep(() -> {}));
            assertThrows(IllegalStateException.class, () -> contacts.compose(listener(() -> {})));
            assertThrows(IllegalStateException.class, () -> contacts.ticks(1, 1, 1));
            Box2dContacts replacement = scene.inspection.registerContacts(
                    "main", Box2dContactLimits.developmentDefaults(),
                    Box2dContactPolicy.developmentDefaults());
            replacement.close();
        }
    }

    private static ContactListener listener(Runnable begin) {
        return new ContactListener() {
            @Override public void beginContact(Contact contact) {
                begin.run();
            }

            @Override public void endContact(Contact contact) {}

            @Override public void preSolve(Contact contact, Manifold oldManifold) {}

            @Override public void postSolve(Contact contact, ContactImpulse impulse) {}
        };
    }

    private static final class NativePostSolve implements ContactListener {
        private int childA;
        private int childB;
        private Box2dVector normal;
        private List<Box2dVector> points = List.of();
        private double[] normalImpulses = {};
        private double[] tangentImpulses = {};

        @Override public void beginContact(Contact contact) {}

        @Override public void endContact(Contact contact) {}

        @Override public void preSolve(Contact contact, Manifold oldManifold) {}

        @Override public void postSolve(Contact contact, ContactImpulse impulse) {
            childA = contact.getChildIndexA();
            childB = contact.getChildIndexB();
            WorldManifold manifold = contact.getWorldManifold();
            normal = vector(manifold.getNormal());
            ArrayList<Box2dVector> copiedPoints = new ArrayList<>();
            for (int index = 0; index < manifold.getNumberOfContactPoints(); index++) {
                copiedPoints.add(vector(manifold.getPoints()[index]));
            }
            points = List.copyOf(copiedPoints);
            normalImpulses = doubles(impulse.getNormalImpulses(), impulse.getCount());
            tangentImpulses = doubles(impulse.getTangentImpulses(), impulse.getCount());
        }
    }

    private static Box2dVector vector(Vector2 value) {
        return new Box2dVector(value.x, value.y);
    }

    private static double[] doubles(float[] values, int count) {
        double[] result = new double[count];
        for (int index = 0; index < count; index++) {
            result[index] = values[index];
        }
        return result;
    }

    private static final class Scene implements AutoCloseable {
        private final World world = new World(new Vector2(0, -10), true);
        private final Body ground;
        private final Fixture groundFixture;
        private final Body ball;
        private final Fixture ballFixture;
        private final AgentRuntime runtime;
        private final Box2dInspection inspection;
        private boolean registered;
        private boolean started;

        Scene(String sessionId) {
            BodyDef groundDef = new BodyDef();
            ground = world.createBody(groundDef);
            PolygonShape floor = new PolygonShape();
            floor.setAsBox(8, 0.5f);
            groundFixture = ground.createFixture(floor, 0);
            groundFixture.setFriction(1);
            floor.dispose();

            BodyDef ballDef = new BodyDef();
            ballDef.type = BodyDef.BodyType.DynamicBody;
            ballDef.position.set(0, 0.9f);
            ball = world.createBody(ballDef);
            CircleShape circle = new CircleShape();
            circle.setRadius(0.5f);
            ballFixture = ball.createFixture(circle, 1);
            ballFixture.setFriction(1);
            circle.dispose();
            ball.setLinearVelocity(5, -1);

            runtime = AgentRuntime.builder().sessionId(SessionId.of(sessionId)).build();
            inspection = new Box2dInspection(runtime, Box2dAdapterLimits.developmentDefaults());
        }

        Box2dContacts registerContacts(Box2dContactPolicy policy) {
            registerExplicit(groundFixture, "ground", ballFixture, "ball");
            return inspection.registerContacts(
                    "main", Box2dContactLimits.developmentDefaults(), policy);
        }

        void registerWorldAndBodies() {
            if (registered) {
                return;
            }
            inspection.registerWorld("main", world, worldSpec());
            inspection.registerBody("ground-body", "main", ground);
            inspection.registerBody("ball-body", "main", ball);
            registered = true;
        }

        void registerExplicit(Fixture first, String firstId, Fixture second, String secondId) {
            registerWorldAndBodies();
            inspection.registerFixture(firstId, first == groundFixture ? "ground-body" : "ball-body",
                    first);
            inspection.registerFixture(secondId,
                    second == groundFixture ? "ground-body" : "ball-body", second);
        }

        void start() {
            if (!started) {
                runtime.start();
                started = true;
            }
        }

        void tick(Box2dContacts contacts) {
            runtime.simulation().tick(STEP_NANOS, supplied -> {
                contacts.captureStep(() -> world.step(STEP_SECONDS, 6, 2));
                return supplied;
            });
        }

        void separate() {
            ball.setTransform(0, 5, 0);
            ball.setLinearVelocity(0, 0);
            ball.setAwake(true);
        }

        void collide() {
            ballFixture.setSensor(false);
            groundFixture.setSensor(false);
            ball.setTransform(0, 0.9f, 0);
            ball.setLinearVelocity(5, -1);
            ball.setAwake(true);
        }

        @Override public void close() {
            runtime.close();
            inspection.close();
            world.dispose();
        }
    }

    private static Box2dWorldSpec worldSpec() {
        return new Box2dWorldSpec(true, true, true, 6, 2,
                OptionalDouble.of(60), new Box2dUnitTransform(100));
    }
}
