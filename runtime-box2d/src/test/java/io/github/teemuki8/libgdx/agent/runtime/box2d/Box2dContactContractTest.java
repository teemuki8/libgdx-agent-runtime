package io.github.teemuki8.libgdx.agent.runtime.box2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.teemuki8.libgdx.agent.runtime.core.ExecutionEpochId;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTickId;
import io.github.teemuki8.libgdx.agent.runtime.core.Truncation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class Box2dContactContractTest {
    @Test
    void developmentDefaultsRetainUsefulPhasesWithinConservativeBounds() {
        assertEquals(new Box2dContactLimits(128, 256, 2, 2, 2, 8, 1_024, 256),
                Box2dContactLimits.developmentDefaults());
        assertEquals(new Box2dContactPolicy(true, true, false, true),
                Box2dContactPolicy.developmentDefaults());
    }

    @Test
    void limitsRejectEveryUnboundedOrUnsupportedDimension() {
        Box2dContactLimits valid = Box2dContactLimits.developmentDefaults();
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactLimits(
                0, valid.activeContactsPerTick(), valid.pointsPerContact(),
                valid.impulsesPerContact(), valid.oldManifoldPointsPerContact(),
                valid.diagnosticsPerTick(), valid.retainedContactTicks(), valid.queryPageSize()));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactLimits(
                valid.callbackRecordsPerTick(), 1_000_001, valid.pointsPerContact(),
                valid.impulsesPerContact(), valid.oldManifoldPointsPerContact(),
                valid.diagnosticsPerTick(), valid.retainedContactTicks(), valid.queryPageSize()));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactLimits(
                valid.callbackRecordsPerTick(), valid.activeContactsPerTick(), 65,
                valid.impulsesPerContact(), valid.oldManifoldPointsPerContact(),
                valid.diagnosticsPerTick(), valid.retainedContactTicks(), valid.queryPageSize()));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactLimits(
                valid.callbackRecordsPerTick(), valid.activeContactsPerTick(),
                valid.pointsPerContact(), valid.impulsesPerContact(),
                valid.oldManifoldPointsPerContact(), 0,
                valid.retainedContactTicks(), valid.queryPageSize()));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactLimits(
                valid.callbackRecordsPerTick(), valid.activeContactsPerTick(),
                valid.pointsPerContact(), valid.impulsesPerContact(),
                valid.oldManifoldPointsPerContact(), valid.diagnosticsPerTick(),
                valid.retainedContactTicks(), valid.retainedContactTicks() + 1));
    }

    @Test
    void recordsAreClosedFiniteAndDefensivelyCopied() {
        ArrayList<Box2dVector> points = new ArrayList<>(List.of(new Box2dVector(1, 2)));
        ArrayList<Box2dContactRecord.OldManifoldPoint> oldPoints = new ArrayList<>(
                List.of(new Box2dContactRecord.OldManifoldPoint(7, 5, -6)));
        Box2dContactRecord record = new Box2dContactRecord(
                Box2dContactRecord.Phase.PRE_SOLVE, key("a", "b"),
                endpoint("a"), endpoint("b"), true, true,
                Box2dContactRecord.Availability.CURRENT_AND_OLD_MANIFOLD,
                points, Optional.of(new Box2dVector(0, 1)), List.of(),
                Optional.of(new Box2dContactRecord.OldManifold(
                        Box2dContactRecord.ManifoldType.FACE_A, oldPoints)),
                1, List.of());
        ArrayList<Box2dContactRecord.Impulse> impulses = new ArrayList<>(
                List.of(new Box2dContactRecord.Impulse(3, -4)));
        Box2dContactRecord postSolve = new Box2dContactRecord(
                Box2dContactRecord.Phase.POST_SOLVE, key("a", "b"),
                endpoint("a"), endpoint("b"), true, true,
                Box2dContactRecord.Availability.CURRENT_MANIFOLD_AND_IMPULSES,
                List.of(), Optional.empty(), impulses, Optional.empty(), 1, List.of());

        points.clear();
        impulses.clear();
        oldPoints.clear();
        assertEquals(1, record.points().size());
        assertEquals(1, postSolve.impulses().size());
        assertEquals(1, record.oldManifold().orElseThrow().points().size());

        assertThrows(IllegalArgumentException.class,
                () -> new Box2dContactRecord.Impulse(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Box2dContactRecord.OldManifoldPoint(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactRecord(
                Box2dContactRecord.Phase.BEGIN, key("a", "b"),
                endpoint("a"), endpoint("b"), true, true,
                Box2dContactRecord.Availability.ENDPOINTS_ONLY,
                List.of(new Box2dVector(0, 0)), Optional.empty(), List.of(),
                Optional.empty(), 1, List.of()));
    }

    @Test
    void canonicalKeysAndTickCountersRejectDishonestEvidence() {
        assertThrows(IllegalArgumentException.class,
                () -> new Box2dContactRecord.Key("b", 0, "a", 0));
        Box2dContactRecord later = record(Box2dContactRecord.Phase.POST_SOLVE, "a", "c", 1);
        Box2dContactRecord earlier = record(Box2dContactRecord.Phase.BEGIN, "a", "b", 1);
        assertThrows(IllegalArgumentException.class, () -> tick(
                List.of(later, earlier), List.of(), 2, 2, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> tick(
                List.of(earlier), List.of(), 0, 1, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> tick(
                List.of(earlier), List.of(), 2, 1, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> tick(
                List.of(earlier), List.of(), 2, 1, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactTick(
                new SimulationTickId(1), new ExecutionEpochId(0), 1, new FrameId(1),
                List.of(earlier), List.of(), 2, 1, 128, 0, 0, 256, 0, List.of(),
                List.of(new Truncation("box2d.contact.records", 3, 1, 128)), false));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactTick(
                new SimulationTickId(1), new ExecutionEpochId(0), 1, new FrameId(1),
                List.of(earlier), List.of(), 1, 1, 128, 0, 0, 256, 0,
                List.of(new Box2dContactTick.Diagnostic(
                        Box2dContactTick.DiagnosticCode.STEP_FAILED, 1)),
                List.of(), true));
    }

    @Test
    void ticksAndPagesCopyInputsAndReportRangeHonestly() {
        Box2dContactRecord record = record(Box2dContactRecord.Phase.BEGIN, "a", "b", 1);
        ArrayList<Box2dContactRecord> records = new ArrayList<>(List.of(record));
        ArrayList<Box2dContactTick.Diagnostic> diagnostics = new ArrayList<>(List.of(
                new Box2dContactTick.Diagnostic(
                        Box2dContactTick.DiagnosticCode.UNMAPPED_ENDPOINT, 1)));
        Box2dContactTick tick = new Box2dContactTick(
                new SimulationTickId(1), new ExecutionEpochId(0), 1, new FrameId(1),
                records, List.of(), 2, 1, 128, 0, 0, 256, 1,
                diagnostics, List.of(new Truncation("box2d.contact.records", 2, 1, 128)), false);
        records.clear();
        diagnostics.clear();
        assertEquals(1, tick.records().size());
        assertEquals(1, tick.diagnostics().size());

        ArrayList<Box2dContactTick> ticks = new ArrayList<>(List.of(tick));
        Box2dContactTickPage page = new Box2dContactTickPage(ticks, false,
                Box2dContactTickPage.RangeStatus.COMPLETE,
                Optional.of(new SimulationTickId(1)), Optional.of(new SimulationTickId(1)));
        ticks.clear();
        assertEquals(1, page.ticks().size());
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactTickPage(
                List.of(), true, Box2dContactTickPage.RangeStatus.COMPLETE,
                Optional.empty(), Optional.empty()));
    }

    @Test
    void publicEvidenceRejectsOversizedAndOpenEndedNestedValues() {
        Box2dContactRecord.Endpoint first = endpoint("a");
        Box2dContactRecord.Endpoint second = endpoint("b");
        Box2dContactRecord.Key key = key("a", "b");
        List<Box2dVector> tooManyPoints = Collections.nCopies(
                65, new Box2dVector(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactRecord(
                Box2dContactRecord.Phase.PRE_SOLVE, key, first, second, true, true,
                Box2dContactRecord.Availability.CURRENT_AND_OLD_MANIFOLD,
                tooManyPoints, Optional.of(new Box2dVector(0, 1)), List.of(),
                Optional.of(new Box2dContactRecord.OldManifold(
                        Box2dContactRecord.ManifoldType.CIRCLES, List.of())),
                1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactRecord.OldManifold(
                Box2dContactRecord.ManifoldType.CIRCLES,
                Collections.nCopies(65,
                        new Box2dContactRecord.OldManifoldPoint(1, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactRecord(
                Box2dContactRecord.Phase.BEGIN, key, first, second, true, true,
                Box2dContactRecord.Availability.ENDPOINTS_ONLY, List.of(), Optional.empty(),
                List.of(), Optional.empty(), 1,
                List.of(new Truncation("application.value", 2, 1, 1))));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactTick.ActiveContact(
                key, first, second, true, true, List.of(), Optional.empty(), List.of(),
                List.of(new Truncation("application.value", 2, 1, 1))));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactTick(
                new SimulationTickId(1), new ExecutionEpochId(0), 1, new FrameId(1),
                List.of(), List.of(), 0, 0, 1_000_001, 0, 0, 1, 0,
                List.of(), List.of(), true));
    }

    @Test
    void completeTicksRejectNestedTruncation() {
        Box2dContactRecord.Endpoint first = endpoint("a");
        Box2dContactRecord.Endpoint second = endpoint("b");
        Box2dContactTick.ActiveContact active = new Box2dContactTick.ActiveContact(
                key("a", "b"), first, second, true, true,
                List.of(new Box2dVector(0, 0)), Optional.of(new Box2dVector(0, 1)), List.of(),
                List.of(new Truncation("box2d.contact.points", 2, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactTick(
                new SimulationTickId(1), new ExecutionEpochId(0), 1, new FrameId(1),
                List.of(), List.of(active), 0, 0, 128, 1, 1, 256, 0,
                List.of(), List.of(), true));
    }

    private static Box2dContactTick tick(List<Box2dContactRecord> records,
            List<Box2dContactTick.ActiveContact> active, long recordsObserved, int recordsRetained,
            long activeObserved, int activeRetained, boolean complete) {
        return new Box2dContactTick(new SimulationTickId(1), new ExecutionEpochId(0), 1,
                new FrameId(1), records, active, recordsObserved, recordsRetained, 128,
                activeObserved, activeRetained, 256, 0, List.of(), List.of(), complete);
    }

    private static Box2dContactRecord record(
            Box2dContactRecord.Phase phase, String fixtureA, String fixtureB, long occurrence) {
        Box2dContactRecord.Availability availability = switch (phase) {
            case BEGIN, END -> Box2dContactRecord.Availability.ENDPOINTS_ONLY;
            case PRE_SOLVE -> Box2dContactRecord.Availability.CURRENT_AND_OLD_MANIFOLD;
            case POST_SOLVE -> Box2dContactRecord.Availability.CURRENT_MANIFOLD_AND_IMPULSES;
        };
        Optional<Box2dContactRecord.OldManifold> old = phase == Box2dContactRecord.Phase.PRE_SOLVE
                ? Optional.of(new Box2dContactRecord.OldManifold(
                        Box2dContactRecord.ManifoldType.CIRCLES, List.of()))
                : Optional.empty();
        return new Box2dContactRecord(phase, key(fixtureA, fixtureB), endpoint(fixtureA),
                endpoint(fixtureB), true, true, availability, List.of(), Optional.empty(),
                List.of(), old, occurrence, List.of());
    }

    private static Box2dContactRecord.Key key(String fixtureA, String fixtureB) {
        return new Box2dContactRecord.Key(fixtureA, 0, fixtureB, 0);
    }

    private static Box2dContactRecord.Endpoint endpoint(String fixture) {
        return new Box2dContactRecord.Endpoint("body-" + fixture, fixture, 0, false);
    }
}
