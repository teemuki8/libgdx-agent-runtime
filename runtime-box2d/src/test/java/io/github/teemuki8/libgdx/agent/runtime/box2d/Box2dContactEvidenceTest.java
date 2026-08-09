package io.github.teemuki8.libgdx.agent.runtime.box2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.Box2D;
import com.badlogic.gdx.physics.box2d.ChainShape;
import com.badlogic.gdx.physics.box2d.Fixture;
import com.badlogic.gdx.physics.box2d.PolygonShape;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.GdxNativesLoader;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntimeException;
import io.github.teemuki8.libgdx.agent.runtime.core.BaselineKind;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntitySnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameId;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameSnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.MonotonicClock;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeEvent;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Box2dContactEvidenceTest {
    private static final long STEP_NANOS = 16_666_667L;
    private static final float STEP_SECONDS = 1f / 60f;
    private static final EntityId CONTACTS_ID = EntityId.of("box2d.contacts.main");

    @BeforeAll
    static void initializeNativeBox2d() {
        GdxNativesLoader.load();
        Box2D.init();
    }

    @Test
    void publishesExactClosedEntityAndEventSchemasFromNativeCallbacks() {
        try (Scene scene = new Scene("contact-evidence-schema")) {
            Box2dContacts contacts = scene.registerContacts(
                    Box2dContactLimits.developmentDefaults(),
                    Box2dContactPolicy.developmentDefaults());
            scene.world.setContactListener(contacts.listener());
            scene.start();

            EntitySnapshot baseline = scene.runtime.entity(CONTACTS_ID).orElseThrow();
            assertEquals("box2d.contacts", baseline.type().value());
            assertFieldNames(baseline.properties(), "activeContacts", "activeCounts",
                    "callbackCounts", "complete", "diagnostics", "latestTick", "limits",
                    "policy", "records", "runtimeEntityId", "truncations",
                    "unmappedContacts", "worldId");
            assertEquals(RuntimeValues.nullValue(), property(baseline, "latestTick"));
            assertEquals(RuntimeValues.bool(false), property(baseline, "complete"));

            scene.tick(contacts);

            FrameSnapshot frame = scene.runtime.frame(new FrameId(1)).orElseThrow();
            Box2dContactTick typedTick = contacts.ticks(1, 1, 16).ticks().getFirst();
            assertTrue(typedTick.records().stream().noneMatch(Box2dContactRecord::sensor));
            assertTrue(typedTick.activeContacts().stream()
                    .noneMatch(Box2dContactTick.ActiveContact::sensor));
            EntitySnapshot entity = frame.entity(CONTACTS_ID).orElseThrow();
            assertEquals(RuntimeValues.string("main"), property(entity, "worldId"));
            assertEquals(RuntimeValues.string("box2d.contacts.main"),
                    property(entity, "runtimeEntityId"));
            assertObjectFields(property(entity, "policy"),
                    "begin", "end", "postSolve", "preSolve");
            assertEquals(RuntimeValues.bool(false),
                    objectField(property(entity, "policy"), "preSolve"));
            assertObjectFields(property(entity, "limits"), "activeContactsPerTick",
                    "callbackRecordsPerTick", "diagnosticsPerTick", "impulsesPerContact",
                    "oldManifoldPointsPerContact", "pointsPerContact", "queryPageSize",
                    "retainedContactTicks");
            assertObjectFields(property(entity, "latestTick"), "epochTick",
                    "executionEpochId", "runtimeFrameId", "simulationTickId");
            assertEquals(RuntimeValues.integer(1),
                    objectField(property(entity, "latestTick"), "simulationTickId"));
            assertEquals(RuntimeValues.integer(1),
                    objectField(property(entity, "latestTick"), "runtimeFrameId"));
            assertObjectFields(property(entity, "callbackCounts"),
                    "limit", "observed", "retained");
            assertObjectFields(property(entity, "activeCounts"),
                    "limit", "observed", "retained");
            assertEquals(RuntimeValues.integer(0), property(entity, "unmappedContacts"));
            assertEquals(RuntimeValues.bool(true), property(entity, "complete"));

            RuntimeValue.ListValue records = list(property(entity, "records"));
            assertFalse(records.values().isEmpty());
            RuntimeValue begin = records.values().stream()
                    .filter(value -> RuntimeValues.enumValue("BEGIN")
                            .equals(objectField(value, "phase")))
                    .findFirst().orElseThrow();
            assertRecordFields(begin);
            assertEquals(RuntimeValues.nullValue(), objectField(begin, "normal"));
            assertEquals(RuntimeValues.nullValue(), objectField(begin, "oldManifold"));
            assertTrue(list(objectField(begin, "impulses")).values().isEmpty());
            assertKeyAndEndpoints(begin);

            RuntimeValue postSolve = records.values().stream()
                    .filter(value -> RuntimeValues.enumValue("POST_SOLVE")
                            .equals(objectField(value, "phase")))
                    .findFirst().orElseThrow();
            assertRecordFields(postSolve);
            assertInstanceOf(RuntimeValue.Vector2Value.class,
                    objectField(postSolve, "normal"));
            assertEquals(RuntimeValues.nullValue(), objectField(postSolve, "oldManifold"));
            assertKeyAndEndpoints(postSolve);

            RuntimeValue.ListValue active = list(property(entity, "activeContacts"));
            assertFalse(active.values().isEmpty());
            assertObjectFields(active.values().getFirst(), "enabled", "endpointA", "endpointB",
                    "impulses", "key", "normal", "points", "sensor", "touching",
                    "truncations");

            assertFalse(frame.events().isEmpty());
            assertTrue(frame.events().stream().noneMatch(
                    event -> event.type().value().equals("box2d.contact.preSolve")));
            for (RuntimeEvent event : frame.events()) {
                assertEquals(new FrameId(1), event.frameId());
                assertEquals(EntityId.of("box2d.body.ball-body"),
                        event.subject().orElseThrow());
                assertEquals(EntityId.of("box2d.body.ground-body"),
                        event.source().orElseThrow());
                assertFieldNames(event.attributes(), "availability", "enabled", "endpointA",
                        "endpointB", "epochTick", "executionEpochId", "impulses", "key",
                        "normal", "occurrence", "oldManifold", "points", "sensor",
                        "simulationTickId", "touching", "truncations", "worldId");
                assertEquals(RuntimeValues.string("main"),
                        field(event.attributes(), "worldId"));
                assertEquals(RuntimeValues.integer(1),
                        field(event.attributes(), "simulationTickId"));
                assertFalse(event.attributes().stream()
                        .anyMatch(attribute -> attribute.name().equals("runtimeFrameId")));
            }
        }
    }

    @Test
    void exposesEveryContactBoundAndNeverTurnsIncompleteEvidenceIntoAnExactEmptySet() {
        Box2dContactLimits limits = new Box2dContactLimits(64, 1, 1, 1, 1, 1, 1, 1);
        try (Scene scene = new Scene("contact-evidence-bounds")) {
            scene.addSecondBox();
            Box2dContacts contacts = scene.registerContacts(
                    limits, new Box2dContactPolicy(true, true, true, true));
            scene.world.setContactListener(contacts.listener());
            scene.start();
            scene.tick(contacts);
            scene.tick(contacts);

            EntitySnapshot entity = scene.runtime.entity(CONTACTS_ID).orElseThrow();
            assertEquals(RuntimeValues.integer(2),
                    objectField(property(entity, "activeCounts"), "observed"));
            assertEquals(RuntimeValues.integer(1),
                    objectField(property(entity, "activeCounts"), "retained"));
            assertEquals(RuntimeValues.bool(false), property(entity, "complete"));
            assertFalse(list(property(entity, "activeContacts")).values().isEmpty());
            assertTrue(list(property(entity, "diagnostics")).values().size() <= 1);
            RuntimeValue diagnostic = list(property(entity, "diagnostics")).values().getFirst();
            assertObjectFields(diagnostic, "code", "observed");
            RuntimeValue diagnosticTruncation = list(property(entity, "truncations")).values()
                    .stream().filter(value ->
                    RuntimeValues.string("box2d.contact.diagnostics")
                            .equals(objectField(value, "dimension")))
                    .findFirst().orElseThrow();
            assertObjectFields(diagnosticTruncation,
                    "dimension", "limit", "observed", "retained");

            List<RuntimeValue> records = list(property(entity, "records")).values();
            assertTrue(records.stream().flatMap(value ->
                            list(objectField(value, "truncations")).values().stream())
                    .map(value -> objectField(value, "dimension"))
                    .anyMatch(RuntimeValues.string("box2d.contact.points")::equals));
            assertTrue(records.stream().flatMap(value ->
                            list(objectField(value, "truncations")).values().stream())
                    .map(value -> objectField(value, "dimension"))
                    .anyMatch(RuntimeValues.string("box2d.contact.impulses")::equals));
            assertTrue(records.stream().flatMap(value ->
                            list(objectField(value, "truncations")).values().stream())
                    .map(value -> objectField(value, "dimension"))
                    .anyMatch(RuntimeValues.string("box2d.contact.oldManifoldPoints")::equals));
            RuntimeValue preSolve = records.stream()
                    .filter(value -> RuntimeValues.enumValue("PRE_SOLVE")
                            .equals(objectField(value, "phase")))
                    .filter(value -> !(objectField(value, "oldManifold")
                            instanceof RuntimeValue.NullValue))
                    .findFirst().orElseThrow();
            RuntimeValue oldManifold = objectField(preSolve, "oldManifold");
            assertObjectFields(oldManifold, "points", "type");
            assertObjectFields(list(objectField(oldManifold, "points")).values().getFirst(),
                    "id", "normalImpulse", "tangentImpulse");
            RuntimeValue postSolve = records.stream()
                    .filter(value -> RuntimeValues.enumValue("POST_SOLVE")
                            .equals(objectField(value, "phase")))
                    .filter(value -> !list(objectField(value, "impulses")).values().isEmpty())
                    .findFirst().orElseThrow();
            assertObjectFields(list(objectField(postSolve, "impulses")).values().getFirst(),
                    "normal", "tangent");
            assertEquals(Box2dContactTickPage.RangeStatus.PARTIALLY_EVICTED,
                    contacts.ticks(1, 2, 1).rangeStatus());
        }

        try (Scene scene = new Scene("contact-evidence-record-bound")) {
            Box2dContacts contacts = scene.registerContacts(
                    new Box2dContactLimits(1, 8, 2, 2, 2, 8, 8, 8),
                    new Box2dContactPolicy(true, true, true, true));
            scene.world.setContactListener(contacts.listener());
            scene.start();
            scene.tick(contacts);

            EntitySnapshot entity = scene.runtime.entity(CONTACTS_ID).orElseThrow();
            assertTrue(integer(objectField(property(entity, "callbackCounts"), "observed")) > 1);
            assertEquals(RuntimeValues.integer(1),
                    objectField(property(entity, "callbackCounts"), "retained"));
            assertEquals(RuntimeValues.bool(false), property(entity, "complete"));
        }
    }

    @Test
    void epochResetPublishesAClosedDiagnosticAndClearsActiveAndHistoryEvidence() {
        try (Scene scene = new Scene("contact-evidence-epoch")) {
            Box2dContacts contacts = scene.registerContacts(
                    Box2dContactLimits.developmentDefaults(),
                    Box2dContactPolicy.developmentDefaults());
            scene.world.setContactListener(contacts.listener());
            scene.start();
            scene.tick(contacts);
            assertFalse(list(property(scene.runtime.entity(CONTACTS_ID).orElseThrow(),
                    "activeContacts")).values().isEmpty());

            FrameId baseline = scene.runtime.startEpoch(BaselineKind.SCENARIO_RESET);

            EntitySnapshot reset = scene.runtime.frame(baseline).orElseThrow()
                    .entity(CONTACTS_ID).orElseThrow();
            assertEquals(RuntimeValues.nullValue(), property(reset, "latestTick"));
            assertTrue(list(property(reset, "activeContacts")).values().isEmpty());
            assertEquals(RuntimeValues.bool(false), property(reset, "complete"));
            assertDiagnostic(reset, "EPOCH_RESET");
            Box2dContactTickPage evicted = contacts.ticks(1, 1, 16);
            assertTrue(evicted.ticks().isEmpty());
            assertEquals(Box2dContactTickPage.RangeStatus.PARTIALLY_EVICTED,
                    evicted.rangeStatus());

            scene.separate();
            scene.tick(contacts);
            Box2dContactTick afterReset = contacts.ticks(2, 2, 16).ticks().getFirst();
            assertTrue(afterReset.complete());
            assertTrue(afterReset.activeContacts().isEmpty());
        }
    }

    @Test
    void nestedActiveTruncationRemainsIncompleteWithoutAnotherCallback() {
        try (Scene scene = new Scene("contact-evidence-sticky-truncation")) {
            Box2dContacts contacts = scene.registerContacts(
                    new Box2dContactLimits(64, 8, 1, 2, 2, 8, 8, 8),
                    Box2dContactPolicy.developmentDefaults());
            scene.world.setContactListener(contacts.listener());
            scene.start();
            scene.tick(contacts);
            scene.runtime.simulation().tick(STEP_NANOS, supplied -> {
                contacts.captureStep(() -> {});
                return supplied;
            });

            Box2dContactTick later = contacts.ticks(2, 2, 8).ticks().getFirst();
            assertTrue(later.activeContacts().stream()
                    .anyMatch(value -> !value.truncations().isEmpty()));
            assertFalse(later.complete());
            assertEquals(RuntimeValues.bool(false), property(
                    scene.runtime.entity(CONTACTS_ID).orElseThrow(), "complete"));
        }
    }

    @Test
    void captureFailureMarksTypedHistoryAsMissingItsClaimedRuntimeFrame() {
        long[] clockCalls = {0};
        Scene scene = new Scene("contact-evidence-capture-failure",
                () -> clockCalls[0]++ == 0 ? 1 : -1);
        try {
            Box2dContacts contacts = scene.registerContacts(
                    Box2dContactLimits.developmentDefaults(),
                    Box2dContactPolicy.developmentDefaults());
            scene.world.setContactListener(contacts.listener());
            scene.start();

            assertThrows(IllegalStateException.class, () -> scene.tick(contacts));

            Box2dContactTick failed = contacts.ticks(1, 1, 16).ticks().getFirst();
            assertFalse(failed.complete());
            assertTrue(failed.diagnostics().stream().anyMatch(value ->
                    value.code() == Box2dContactTick.DiagnosticCode.MISSING_CORRELATION));
            assertTrue(scene.runtime.frame(new FrameId(1)).isEmpty());
        } finally {
            // A deliberate end-frame failure leaves core's frame open by contract.
            scene.world.dispose();
        }
    }

    @Test
    void fixtureMutationPreservesUnrelatedRetainedActiveContacts() {
        try (Scene scene = new Scene("contact-evidence-scoped-fixture-reset")) {
            scene.addSecondBox();
            Box2dContacts contacts = scene.registerContacts(
                    Box2dContactLimits.developmentDefaults(),
                    Box2dContactPolicy.developmentDefaults());
            scene.world.setContactListener(contacts.listener());
            scene.start();
            scene.tick(contacts);
            assertEquals(2, contacts.ticks(1, 1, 16).ticks().getFirst()
                    .activeContacts().size());

            scene.ballFixtureRegistration.rebind(scene.createBallFixture());
            scene.runtime.simulation().tick(STEP_NANOS, supplied -> {
                contacts.captureStep(() -> {});
                return supplied;
            });

            Box2dContactTick after = contacts.ticks(2, 2, 16).ticks().getFirst();
            assertEquals(1, after.activeContacts().size());
            assertTrue(after.activeContacts().getFirst().key().fixtureAId().equals("ground")
                    || after.activeContacts().getFirst().key().fixtureBId().equals("ground"));
            assertTrue(after.activeContacts().getFirst().key().fixtureAId().equals("second")
                    || after.activeContacts().getFirst().key().fixtureBId().equals("second"));
            assertTrue(after.diagnostics().stream().anyMatch(value ->
                    value.code() == Box2dContactTick.DiagnosticCode.ENDPOINT_CHANGED));
        }
    }

    @Test
    void fixtureAndWorldMutationResetContactEvidenceAndOpenFramesRejectMutation() {
        try (Scene scene = new Scene("contact-evidence-rebind")) {
            Box2dContacts contacts = scene.registerContacts(
                    Box2dContactLimits.developmentDefaults(),
                    Box2dContactPolicy.developmentDefaults());
            scene.world.setContactListener(contacts.listener());
            scene.start();
            scene.tick(contacts);

            Fixture replacement = scene.createBallFixture();
            scene.runtime.beginFrame(0);
            assertThrows(AgentRuntimeException.class,
                    () -> scene.ballFixtureRegistration.rebind(replacement));
            scene.runtime.endFrame();
            assertFalse(list(property(scene.runtime.entity(CONTACTS_ID).orElseThrow(),
                    "activeContacts")).values().isEmpty());

            scene.ballFixtureRegistration.rebind(replacement);
            scene.tick(contacts);
            EntitySnapshot rebound = scene.runtime.entity(CONTACTS_ID).orElseThrow();
            assertDiagnostic(rebound, "ENDPOINT_CHANGED");

            scene.ballFixtureRegistration.close();
            scene.groundFixtureRegistration.close();
            scene.ballBodyRegistration.close();
            scene.groundBodyRegistration.close();
            World replacementWorld = new World(new Vector2(0, -10), true);
            try {
                scene.worldRegistration.rebind(replacementWorld);
                scene.runtime.simulation().tick(STEP_NANOS, supplied -> {
                    contacts.captureStep(() ->
                            replacementWorld.step(STEP_SECONDS, 6, 2));
                    return supplied;
                });
                EntitySnapshot worldRebound = scene.runtime.entity(CONTACTS_ID).orElseThrow();
                assertTrue(list(property(worldRebound, "activeContacts")).values().isEmpty());
                assertDiagnostic(worldRebound, "WORLD_REBOUND");
                scene.worldRegistration.close();
                scene.runtime.frame(0, () -> {});
                assertDiagnostic(scene.runtime.entity(CONTACTS_ID).orElseThrow(), "WORLD_REBOUND");
            } finally {
                replacementWorld.dispose();
            }
        }
    }

    @Test
    void callbacksAfterCloseAndMissingBeginCorrelationRemainStructuredAndIncomplete() {
        try (Scene scene = new Scene("contact-evidence-closed-callback")) {
            Box2dContacts closed = scene.registerContacts(
                    Box2dContactLimits.developmentDefaults(),
                    Box2dContactPolicy.developmentDefaults());
            var retainedListener = closed.listener();
            scene.world.setContactListener(retainedListener);
            scene.start();
            scene.tick(closed);
            closed.close();

            Box2dContacts replacement = scene.inspection.registerContacts("main",
                    Box2dContactLimits.developmentDefaults(),
                    Box2dContactPolicy.developmentDefaults());
            scene.separate();
            scene.world.step(STEP_SECONDS, 6, 2);
            scene.collide();
            scene.world.step(STEP_SECONDS, 6, 2);
            scene.world.setContactListener(replacement.listener());
            scene.tick(replacement);

            EntitySnapshot entity = scene.runtime.entity(CONTACTS_ID).orElseThrow();
            assertDiagnostic(entity, "CALLBACK_AFTER_CLOSE");
            assertEquals(RuntimeValues.bool(false), property(entity, "complete"));
        }

        try (Scene scene = new Scene("contact-evidence-missing-correlation")) {
            scene.world.step(STEP_SECONDS, 6, 2);
            Box2dContacts contacts = scene.registerContacts(
                    Box2dContactLimits.developmentDefaults(),
                    Box2dContactPolicy.developmentDefaults());
            scene.world.setContactListener(contacts.listener());
            scene.start();
            scene.tick(contacts);

            EntitySnapshot entity = scene.runtime.entity(CONTACTS_ID).orElseThrow();
            assertTrue(list(property(entity, "activeContacts")).values().isEmpty());
            assertDiagnostic(entity, "MISSING_CORRELATION");
            assertEquals(RuntimeValues.bool(false), property(entity, "complete"));
        }
    }

    @Test
    void nativeCallbacksRejectTheWrongThreadBeforeReadingContactState() throws Exception {
        try (Scene scene = new Scene("contact-evidence-callback-thread")) {
            Box2dContacts contacts = scene.registerContacts(
                    Box2dContactLimits.developmentDefaults(),
                    Box2dContactPolicy.developmentDefaults());
            scene.world.step(STEP_SECONDS, 6, 2);
            var nativeContact = scene.world.getContactList().first();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    contacts.listener().beginContact(nativeContact);
                } catch (Throwable value) {
                    failure.set(value);
                }
            });

            thread.start();
            thread.join();

            assertInstanceOf(IllegalStateException.class, failure.get());
        }
    }

    @Test
    void contactCloseIsRejectedWithoutMutationWhileAFrameIsOpen() {
        try (Scene scene = new Scene("contact-evidence-close-frame")) {
            Box2dContacts contacts = scene.registerContacts(
                    Box2dContactLimits.developmentDefaults(),
                    Box2dContactPolicy.developmentDefaults());
            scene.start();
            var listener = contacts.listener();
            scene.runtime.beginFrame(0);

            assertThrows(AgentRuntimeException.class, contacts::close);

            scene.runtime.endFrame();
            assertEquals(listener, contacts.listener());
            assertTrue(scene.runtime.entity(CONTACTS_ID).isPresent());
            contacts.close();
        }
    }

    @Test
    void retainsTrueCombinedSensorStatusFromCopiedEndpoints() {
        try (Scene scene = new Scene("contact-evidence-sensor")) {
            scene.ballFixture.setSensor(true);
            Box2dContacts contacts = scene.registerContacts(
                    Box2dContactLimits.developmentDefaults(),
                    Box2dContactPolicy.developmentDefaults());
            scene.world.setContactListener(contacts.listener());
            scene.start();
            scene.tick(contacts);

            Box2dContactTick tick = contacts.ticks(1, 1, 16).ticks().getFirst();
            assertTrue(tick.records().stream().anyMatch(Box2dContactRecord::sensor));
            assertTrue(tick.activeContacts().stream()
                    .anyMatch(Box2dContactTick.ActiveContact::sensor));
            EntitySnapshot entity = scene.runtime.entity(CONTACTS_ID).orElseThrow();
            assertTrue(list(property(entity, "records")).values().stream()
                    .anyMatch(value -> RuntimeValues.bool(true)
                            .equals(objectField(value, "sensor"))));
            assertTrue(scene.runtime.latestFrame().orElseThrow().events().stream()
                    .anyMatch(event -> RuntimeValues.bool(true)
                            .equals(field(event.attributes(), "sensor"))));
        }
    }

    @Test
    void retainsANonzeroNativeChainChildIndex() {
        World world = new World(new Vector2(0, -10), true);
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("contact-evidence-chain-child")).build();
        Box2dInspection inspection = new Box2dInspection(
                runtime, Box2dAdapterLimits.developmentDefaults());
        try {
            Body ground = world.createBody(new BodyDef());
            ChainShape chain = new ChainShape();
            chain.createChain(new float[] {-4, 0, -2, 0, 0, 0, 2, 0, 4, 0});
            Fixture chainFixture = ground.createFixture(chain, 0);
            chain.dispose();
            BodyDef boxDefinition = new BodyDef();
            boxDefinition.type = BodyDef.BodyType.DynamicBody;
            boxDefinition.position.set(1, 0.4f);
            Body box = world.createBody(boxDefinition);
            Fixture boxFixture = Scene.createBoxFixture(box);
            box.setLinearVelocity(0, -2);

            inspection.registerWorld("main", world, worldSpec());
            inspection.registerBody("chain-body", "main", ground);
            inspection.registerBody("box-body", "main", box);
            inspection.registerFixture("chain", "chain-body", chainFixture,
                    Box2dFixtureSpec.chainLoop(false));
            inspection.registerFixture("box", "box-body", boxFixture);
            Box2dContacts contacts = inspection.registerContacts("main",
                    Box2dContactLimits.developmentDefaults(),
                    Box2dContactPolicy.developmentDefaults());
            world.setContactListener(contacts.listener());
            runtime.start();
            runtime.simulation().tick(STEP_NANOS, supplied -> {
                contacts.captureStep(() -> world.step(STEP_SECONDS, 6, 2));
                return supplied;
            });

            Box2dContactRecord record = contacts.ticks(1, 1, 16).ticks().getFirst().records()
                    .stream().filter(value -> value.phase() == Box2dContactRecord.Phase.BEGIN)
                    .findFirst().orElseThrow();
            int chainChild = record.key().fixtureAId().equals("chain")
                    ? record.key().childIndexA() : record.key().childIndexB();
            assertTrue(chainChild > 0, "the native chain endpoint must retain its actual child");
        } finally {
            runtime.close();
            inspection.close();
            world.dispose();
        }
    }

    @Test
    void oldManifoldTruncationAloneMakesTheTickIncomplete() {
        try (Scene scene = new Scene("contact-evidence-old-manifold-bound")) {
            Box2dContacts contacts = scene.registerContacts(
                    new Box2dContactLimits(64, 8, 2, 2, 1, 8, 8, 8),
                    new Box2dContactPolicy(true, true, true, true));
            scene.world.setContactListener(contacts.listener());
            scene.start();
            scene.tick(contacts);
            scene.tick(contacts);

            Box2dContactTick tick = contacts.ticks(2, 2, 8).ticks().getFirst();
            assertFalse(tick.complete());
            assertTrue(tick.diagnostics().stream().anyMatch(value ->
                    value.code() == Box2dContactTick.DiagnosticCode.OLD_MANIFOLD_LIMIT_REACHED));
        }
    }

    private static void assertRecordFields(RuntimeValue value) {
        assertObjectFields(value, "availability", "enabled", "endpointA", "endpointB",
                "impulses", "key", "normal", "occurrence", "oldManifold", "phase",
                "points", "sensor", "touching", "truncations");
    }

    private static void assertKeyAndEndpoints(RuntimeValue record) {
        assertObjectFields(objectField(record, "key"),
                "childIndexA", "childIndexB", "fixtureAId", "fixtureBId");
        assertObjectFields(objectField(record, "endpointA"),
                "bodyId", "childIndex", "fixtureId", "sensor");
        assertObjectFields(objectField(record, "endpointB"),
                "bodyId", "childIndex", "fixtureId", "sensor");
    }

    private static void assertDiagnostic(EntitySnapshot entity, String code) {
        assertTrue(list(property(entity, "diagnostics")).values().stream().anyMatch(value ->
                RuntimeValues.enumValue(code).equals(objectField(value, "code"))));
    }

    private static RuntimeValue property(EntitySnapshot entity, String name) {
        return entity.property(name).orElseThrow();
    }

    private static RuntimeValue field(List<RuntimeValue.Field> fields, String name) {
        return fields.stream().filter(field -> field.name().equals(name))
                .map(RuntimeValue.Field::value).findFirst().orElseThrow();
    }

    private static RuntimeValue objectField(RuntimeValue value, String name) {
        return field(object(value).fields(), name);
    }

    private static RuntimeValue.ObjectValue object(RuntimeValue value) {
        return assertInstanceOf(RuntimeValue.ObjectValue.class, value);
    }

    private static RuntimeValue.ListValue list(RuntimeValue value) {
        return assertInstanceOf(RuntimeValue.ListValue.class, value);
    }

    private static long integer(RuntimeValue value) {
        return assertInstanceOf(RuntimeValue.IntegerValue.class, value).value();
    }

    private static void assertObjectFields(RuntimeValue value, String... names) {
        assertFieldNames(object(value).fields(), names);
    }

    private static void assertFieldNames(List<RuntimeValue.Field> fields, String... names) {
        assertEquals(List.of(names), fields.stream().map(RuntimeValue.Field::name).toList());
    }

    private static final class Scene implements AutoCloseable {
        private final World world = new World(new Vector2(0, -10), true);
        private final Body ground;
        private final Body ball;
        private Fixture groundFixture;
        private Fixture ballFixture;
        private final AgentRuntime runtime;
        private final Box2dInspection inspection;
        private Box2dRegistration<World> worldRegistration;
        private Box2dRegistration<Body> groundBodyRegistration;
        private Box2dRegistration<Body> ballBodyRegistration;
        private Box2dRegistration<Fixture> groundFixtureRegistration;
        private Box2dRegistration<Fixture> ballFixtureRegistration;
        private boolean started;

        Scene(String sessionId) {
            this(sessionId, MonotonicClock.system());
        }

        Scene(String sessionId, MonotonicClock clock) {
            BodyDef groundDefinition = new BodyDef();
            ground = world.createBody(groundDefinition);
            PolygonShape groundShape = new PolygonShape();
            groundShape.setAsBox(8, 0.5f);
            groundFixture = ground.createFixture(groundShape, 0);
            groundShape.dispose();

            BodyDef ballDefinition = new BodyDef();
            ballDefinition.type = BodyDef.BodyType.DynamicBody;
            ballDefinition.position.set(0, 0.9f);
            ball = world.createBody(ballDefinition);
            ballFixture = createBoxFixture(ball);
            ball.setLinearVelocity(0, -2);

            runtime = AgentRuntime.builder().sessionId(SessionId.of(sessionId))
                    .clock(clock).build();
            inspection = new Box2dInspection(runtime, Box2dAdapterLimits.developmentDefaults());
        }

        Box2dContacts registerContacts(Box2dContactLimits limits, Box2dContactPolicy policy) {
            registerAll();
            return inspection.registerContacts("main", limits, policy);
        }

        private void registerAll() {
            if (worldRegistration != null) {
                return;
            }
            worldRegistration = inspection.registerWorld("main", world, worldSpec());
            groundBodyRegistration = inspection.registerBody("ground-body", "main", ground);
            ballBodyRegistration = inspection.registerBody("ball-body", "main", ball);
            groundFixtureRegistration = inspection.registerFixture(
                    "ground", "ground-body", groundFixture);
            ballFixtureRegistration = inspection.registerFixture("ball", "ball-body", ballFixture);
        }

        void addSecondBox() {
            BodyDef definition = new BodyDef();
            definition.type = BodyDef.BodyType.DynamicBody;
            definition.position.set(2, 0.9f);
            Body second = world.createBody(definition);
            Fixture fixture = createBoxFixture(second);
            second.setLinearVelocity(0, -2);
            registerAll();
            inspection.registerBody("second-body", "main", second);
            inspection.registerFixture("second", "second-body", fixture);
        }

        Fixture createBallFixture() {
            return createBoxFixture(ball);
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
            ball.setTransform(0, 0.9f, 0);
            ball.setLinearVelocity(0, -2);
            ball.setAwake(true);
        }

        @Override public void close() {
            runtime.close();
            inspection.close();
            world.dispose();
        }

        private static Fixture createBoxFixture(Body body) {
            PolygonShape shape = new PolygonShape();
            shape.setAsBox(0.5f, 0.5f);
            Fixture fixture = body.createFixture(shape, 1);
            shape.dispose();
            return fixture;
        }
    }

    private static Box2dWorldSpec worldSpec() {
        return new Box2dWorldSpec(true, true, true, 6, 2,
                OptionalDouble.of(60), new Box2dUnitTransform(100));
    }
}
