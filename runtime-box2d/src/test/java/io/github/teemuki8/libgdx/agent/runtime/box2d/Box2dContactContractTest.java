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
    void developmentDefaultsRetainBox2d3EventPhasesWithinConservativeBounds() {
        assertEquals(new Box2dContactLimits(128, 256, 2, 2, 8, 1_024, 256),
                Box2dContactLimits.developmentDefaults());
        assertEquals(new Box2dContactPolicy(true, true, true),
                Box2dContactPolicy.developmentDefaults());
    }

    @Test
    void limitsRejectEveryUnboundedDimension() {
        Box2dContactLimits valid = Box2dContactLimits.developmentDefaults();
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactLimits(
                0, valid.activeContactsPerTick(), valid.pointsPerContact(),
                valid.impulsesPerContact(), valid.diagnosticsPerTick(),
                valid.retainedContactTicks(), valid.queryPageSize()));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactLimits(
                valid.callbackRecordsPerTick(), 1_000_001, valid.pointsPerContact(),
                valid.impulsesPerContact(), valid.diagnosticsPerTick(),
                valid.retainedContactTicks(), valid.queryPageSize()));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactLimits(
                valid.callbackRecordsPerTick(), valid.activeContactsPerTick(), 65,
                valid.impulsesPerContact(), valid.diagnosticsPerTick(),
                valid.retainedContactTicks(), valid.queryPageSize()));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactLimits(
                valid.callbackRecordsPerTick(), valid.activeContactsPerTick(),
                valid.pointsPerContact(), valid.impulsesPerContact(), 0,
                valid.retainedContactTicks(), valid.queryPageSize()));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactLimits(
                valid.callbackRecordsPerTick(), valid.activeContactsPerTick(),
                valid.pointsPerContact(), valid.impulsesPerContact(),
                valid.diagnosticsPerTick(), valid.retainedContactTicks(),
                valid.retainedContactTicks() + 1));
    }

    @Test
    void recordsAreClosedFiniteAndDefensivelyCopied() {
        ArrayList<Box2dVector> points = new ArrayList<>(List.of(new Box2dVector(1, 2)));
        ArrayList<Box2dContactRecord.Impulse> impulses = new ArrayList<>(
                List.of(new Box2dContactRecord.Impulse(3, -4)));
        Box2dContactRecord postSolve = new Box2dContactRecord(
                Box2dContactRecord.Phase.POST_SOLVE, key("a", "b"),
                endpoint("a"), endpoint("b"), true, true,
                Box2dContactRecord.Availability.CURRENT_MANIFOLD_AND_IMPULSES,
                points, Optional.of(new Box2dVector(0, 1)), impulses, 1, List.of());

        points.clear();
        impulses.clear();
        assertEquals(1, postSolve.points().size());
        assertEquals(1, postSolve.impulses().size());
        assertThrows(IllegalArgumentException.class,
                () -> new Box2dContactRecord.Impulse(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactRecord(
                Box2dContactRecord.Phase.BEGIN, key("a", "b"),
                endpoint("a"), endpoint("b"), true, true,
                Box2dContactRecord.Availability.ENDPOINTS_ONLY,
                List.of(new Box2dVector(0, 0)), Optional.empty(), List.of(), 1, List.of()));
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
    }

    @Test
    void ticksPagesAndNestedValuesRemainBoundedAndCopied() {
        Box2dContactRecord record = record(Box2dContactRecord.Phase.BEGIN, "a", "b", 1);
        ArrayList<Box2dContactRecord> records = new ArrayList<>(List.of(record));
        Box2dContactTick tick = new Box2dContactTick(
                new SimulationTickId(1), new ExecutionEpochId(0), 1, new FrameId(1),
                records, List.of(), 2, 1, 128, 0, 0, 256, 1,
                List.of(new Box2dContactTick.Diagnostic(
                        Box2dContactTick.DiagnosticCode.UNMAPPED_ENDPOINT, 1)),
                List.of(new Truncation("box2d.contact.records", 2, 1, 128)), false);
        records.clear();
        assertEquals(1, tick.records().size());

        ArrayList<Box2dContactTick> ticks = new ArrayList<>(List.of(tick));
        Box2dContactTickPage page = new Box2dContactTickPage(ticks, false,
                Box2dContactTickPage.RangeStatus.COMPLETE,
                Optional.of(new SimulationTickId(1)), Optional.of(new SimulationTickId(1)));
        ticks.clear();
        assertEquals(1, page.ticks().size());
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactTickPage(
                List.of(), true, Box2dContactTickPage.RangeStatus.COMPLETE,
                Optional.empty(), Optional.empty()));

        List<Box2dVector> tooManyPoints = Collections.nCopies(65, new Box2dVector(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Box2dContactRecord(
                Box2dContactRecord.Phase.POST_SOLVE, key("a", "b"),
                endpoint("a"), endpoint("b"), true, true,
                Box2dContactRecord.Availability.CURRENT_MANIFOLD_AND_IMPULSES,
                tooManyPoints, Optional.of(new Box2dVector(0, 1)), List.of(), 1, List.of()));
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
        Box2dContactRecord.Availability availability = phase == Box2dContactRecord.Phase.POST_SOLVE
                ? Box2dContactRecord.Availability.CURRENT_MANIFOLD_AND_IMPULSES
                : Box2dContactRecord.Availability.ENDPOINTS_ONLY;
        return new Box2dContactRecord(phase, key(fixtureA, fixtureB), endpoint(fixtureA),
                endpoint(fixtureB), true, true, availability, List.of(), Optional.empty(),
                List.of(), occurrence, List.of());
    }

    private static Box2dContactRecord.Key key(String fixtureA, String fixtureB) {
        return new Box2dContactRecord.Key(fixtureA, 0, fixtureB, 0);
    }

    private static Box2dContactRecord.Endpoint endpoint(String fixture) {
        return new Box2dContactRecord.Endpoint("body-" + fixture, fixture, 0, false);
    }
}
