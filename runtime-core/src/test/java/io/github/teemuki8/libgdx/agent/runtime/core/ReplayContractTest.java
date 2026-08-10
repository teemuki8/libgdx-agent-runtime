package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

final class ReplayContractTest {
    @Test
    void limitsProvideConservativeDefaultsAndRejectUnsupportedBounds() {
        ReplayLimits defaults = ReplayLimits.developmentDefaults();

        assertEquals(32, defaults.retainedOperations());
        assertEquals(4_096, defaults.maximumInputs());
        assertEquals(600, defaults.maximumTicks());
        assertEquals(10_000, defaults.maximumEntitiesPerFrame());
        assertEquals(100_000, defaults.maximumFactsPerFrame());
        assertEquals(1_048_576, defaults.maximumEncodedEvidenceBytes());
        assertEquals(Duration.ofSeconds(30).toNanos(), defaults.maximumExecutionNanos());

        assertThrows(IllegalArgumentException.class, () -> limits(0, 1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 0, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, 1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, 1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> limits(
                100_001, 1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(
                1, 100_001, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(
                1, 1, 100_001, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(
                1, 1, 1, 100_001, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(
                1, 1, 1, 1, 1_000_001, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(
                1, 1, 1, 1, 1, 16_777_217, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(
                1, 1, 1, 1, 1, 1, Duration.ofMinutes(5).toNanos() + 1));
    }

    @Test
    void captureSpecificationRequiresOneReplayOriginAndOrdersSelectors() {
        ArrayList<SimulationConfigurationRequirement> configuration = new ArrayList<>(List.of(
                configuration("iterations", 8), configuration("fixedStep", 16)));
        ArrayList<SimulationEvidenceRequirement> evidence = new ArrayList<>(List.of(
                new SimulationEvidenceRequirement(EntityId.of("world"), "contactsComplete"),
                new SimulationEvidenceRequirement(EntityId.of("world"), "bodyComplete")));
        ArrayList<EventType> events = new ArrayList<>(List.of(
                EventType.of("box2d.contact.end"), EventType.of("box2d.contact.begin")));

        ReplayCaptureSpec spec = new ReplayCaptureSpec(
                recording(Optional.of("ball-drop"), Optional.empty(), true), profile(true),
                configuration, evidence, events);
        configuration.clear();
        evidence.clear();
        events.clear();

        assertEquals(List.of("fixedStep", "iterations"), spec.configurationRequirements().stream()
                .map(SimulationConfigurationRequirement::property).toList());
        assertEquals(List.of("bodyComplete", "contactsComplete"), spec.evidenceRequirements().stream()
                .map(SimulationEvidenceRequirement::property).toList());
        assertEquals(List.of("box2d.contact.begin", "box2d.contact.end"), spec.eventTypes().stream()
                .map(EventType::value).toList());
        assertThrows(UnsupportedOperationException.class, () -> spec.eventTypes().clear());

        assertThrows(IllegalArgumentException.class, () -> new ReplayCaptureSpec(
                recording(Optional.empty(), Optional.empty(), true), profile(false),
                List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ReplayCaptureSpec(
                recording(Optional.of("ball-drop"), Optional.of("before-drop"), true),
                profile(false), List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ReplayCaptureSpec(
                recording(Optional.of("ball-drop"), Optional.empty(), false), profile(false),
                List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ReplayCaptureSpec(
                recording(Optional.of("ball-drop"), Optional.empty(), true), profile(false),
                List.of(), List.of(), List.of(EventType.of("box2d.contact.begin"))));

        RecordingSpec nestedConfiguration = new RecordingSpec(
                "nested-configuration", "2.5", List.of(), Optional.of("ball-drop"),
                Optional.empty(), OptionalLong.empty(), RuntimeValues.object(
                        RuntimeValues.field("nested", RuntimeValues.list(
                                RuntimeValues.integer(1)))), true);
        assertThrows(IllegalArgumentException.class, () -> new ReplayCaptureSpec(
                nestedConfiguration, profile(false), List.of(), List.of(), List.of()));
    }

    @Test
    void captureSpecificationRejectsDuplicateAndOverLimitSelectors() {
        SimulationConfigurationRequirement configuration = configuration("fixedStep", 16);
        SimulationEvidenceRequirement evidence = new SimulationEvidenceRequirement(
                EntityId.of("world"), "complete");
        EventType event = EventType.of("box2d.contact.begin");

        assertThrows(IllegalArgumentException.class, () -> capture(
                List.of(configuration, configuration), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> capture(
                List.of(), List.of(evidence, evidence), List.of()));
        assertThrows(IllegalArgumentException.class, () -> capture(
                List.of(), List.of(), List.of(event, event)));
        assertThrows(IllegalArgumentException.class, () -> capture(
                repeatedConfiguration(ReplayCaptureSpec.MAX_CONFIGURATION_REQUIREMENTS + 1),
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> capture(
                List.of(), repeatedEvidence(ReplayCaptureSpec.MAX_EVIDENCE_REQUIREMENTS + 1),
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> capture(
                List.of(), List.of(), repeatedEvents(ReplayCaptureSpec.MAX_EVENT_TYPES + 1)));
    }

    @Test
    void divergenceRequiresEvidenceConsistentWithItsPhase() {
        DeterminismDifference difference = difference();
        ReplayDivergence baseline = new ReplayDivergence(
                ReplayPhase.BASELINE, OptionalLong.empty(), Optional.empty(), Optional.empty(),
                new ExecutionEpochId(1), new ExecutionEpochId(2), new FrameId(4), new FrameId(8),
                difference);
        ReplayDivergence tick = new ReplayDivergence(
                ReplayPhase.SIMULATION_TICK, OptionalLong.of(3),
                Optional.of(new SimulationTickId(5)), Optional.of(new SimulationTickId(9)),
                new ExecutionEpochId(1), new ExecutionEpochId(2), new FrameId(7), new FrameId(11),
                difference);

        assertTrue(baseline.epochTick().isEmpty());
        assertEquals(3, tick.epochTick().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new ReplayDivergence(
                ReplayPhase.BASELINE, OptionalLong.of(1), Optional.empty(), Optional.empty(),
                new ExecutionEpochId(1), new ExecutionEpochId(2), new FrameId(4), new FrameId(8),
                difference));
        assertThrows(IllegalArgumentException.class, () -> new ReplayDivergence(
                ReplayPhase.SIMULATION_TICK, OptionalLong.empty(),
                Optional.of(new SimulationTickId(5)), Optional.of(new SimulationTickId(9)),
                new ExecutionEpochId(1), new ExecutionEpochId(2), new FrameId(7), new FrameId(11),
                difference));
        assertThrows(IllegalArgumentException.class, () -> new ReplayDivergence(
                ReplayPhase.SIMULATION_TICK, OptionalLong.of(0),
                Optional.of(new SimulationTickId(5)), Optional.of(new SimulationTickId(9)),
                new ExecutionEpochId(1), new ExecutionEpochId(2), new FrameId(7), new FrameId(11),
                difference));
        assertThrows(IllegalArgumentException.class, () -> new ReplayDivergence(
                ReplayPhase.SIMULATION_TICK, OptionalLong.of(3), Optional.empty(),
                Optional.of(new SimulationTickId(9)), new ExecutionEpochId(1),
                new ExecutionEpochId(2), new FrameId(7), new FrameId(11), difference));
    }

    @Test
    void resultRequiresStatusConsistentWithDivergenceAndApplicationFailure() {
        ReplayDivergence divergence = new ReplayDivergence(
                ReplayPhase.BASELINE, OptionalLong.empty(), Optional.empty(), Optional.empty(),
                new ExecutionEpochId(1), new ExecutionEpochId(2), new FrameId(4), new FrameId(8),
                difference());
        ReplayBounds bounds = new ReplayBounds(4, 0, 2, 6, 8, 128, 1_000);
        ReplayResult result = new ReplayResult(
                DeterminismStatus.DIVERGED, "baseline differs", "recording",
                Optional.of(profile(false)),
                Optional.of(divergence), bounds, Optional.empty());

        assertEquals(ReplayPhase.BASELINE, result.divergence().orElseThrow().phase());
        assertThrows(IllegalArgumentException.class, () -> new ReplayResult(
                DeterminismStatus.EQUAL, "equal", "recording", Optional.of(profile(false)),
                Optional.of(divergence), bounds, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ReplayResult(
                DeterminismStatus.DIVERGED, "diverged", "recording", Optional.of(profile(false)),
                Optional.empty(), bounds, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ReplayResult(
                DeterminismStatus.EQUAL, "equal", "recording", Optional.of(profile(false)),
                Optional.empty(), bounds, Optional.of(applicationFailure())));
        assertThrows(IllegalArgumentException.class, () -> new ReplayResult(
                DeterminismStatus.EQUAL, "equal", "recording", Optional.empty(),
                Optional.empty(), bounds, Optional.empty()));
        ReplayResult unavailable = new ReplayResult(
                DeterminismStatus.INCONCLUSIVE, "replay evidence unavailable", "recording",
                Optional.empty(), Optional.empty(), bounds, Optional.empty());
        assertTrue(unavailable.profile().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ReplayBounds(
                3, 4, 0, 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReplayBounds(
                1, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void operationsValidateIdentifiersAndPairBaselineEvidence() {
        CommandLookup queued = CommandLookup.found(new CommandStatus(
                "replay-start", CommandState.QUEUED, 1, 2, Optional.empty(), Optional.empty(),
                false, Optional.empty(), Optional.empty()));
        ReplayCaptureOperation capture = new ReplayCaptureOperation(
                "recording", "replay-start", queued, Optional.empty(), Optional.empty());
        ReplayOperation replay = new ReplayOperation(
                "recording", "replay-start", queued, Optional.empty());

        assertTrue(capture.baselineExecutionEpochId().isEmpty());
        assertTrue(replay.result().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ReplayCaptureOperation(
                "recording", "replay-start", queued, Optional.of(new ExecutionEpochId(2)),
                Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ReplayOperation(
                " ", "replay-start", queued, Optional.empty()));
    }

    private static ReplayLimits limits(int operations, int inputs, int ticks, int entities,
            int facts, int bytes, long executionNanos) {
        return new ReplayLimits(operations, inputs, ticks, entities, facts, bytes, executionNanos);
    }

    private static ReplayCaptureSpec capture(
            List<SimulationConfigurationRequirement> configuration,
            List<SimulationEvidenceRequirement> evidence, List<EventType> events) {
        return new ReplayCaptureSpec(
                recording(Optional.of("ball-drop"), Optional.empty(), true), profile(true),
                configuration, evidence, events);
    }

    private static RecordingSpec recording(Optional<String> scenarioId,
            Optional<String> checkpointId, boolean replayGuaranteed) {
        return new RecordingSpec("recording", "2.5", List.of(), scenarioId, checkpointId,
                OptionalLong.of(7), RuntimeValues.object(), replayGuaranteed);
    }

    private static DeterminismProfile profile(boolean includeEvents) {
        return new DeterminismProfile(new SnapshotComparisonScope(
                List.of(EntityId.of("world")), List.of("position"), List.of(),
                includeEvents, false), false);
    }

    private static SimulationConfigurationRequirement configuration(String property, long expected) {
        return new SimulationConfigurationRequirement(
                EntityId.of("world"), property, RuntimeValues.integer(expected));
    }

    private static List<SimulationConfigurationRequirement> repeatedConfiguration(int count) {
        ArrayList<SimulationConfigurationRequirement> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(configuration("property-" + index, index));
        }
        return values;
    }

    private static List<SimulationEvidenceRequirement> repeatedEvidence(int count) {
        ArrayList<SimulationEvidenceRequirement> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(new SimulationEvidenceRequirement(
                    EntityId.of("entity-" + index), "complete"));
        }
        return values;
    }

    private static List<EventType> repeatedEvents(int count) {
        ArrayList<EventType> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(EventType.of("event-" + index));
        }
        return values;
    }

    private static DeterminismDifference difference() {
        return new DeterminismDifference(
                DeterminismDifferenceKind.PROPERTY, Optional.of("world:position"),
                Optional.of(RuntimeValues.vector2(1, 2)),
                Optional.of(RuntimeValues.vector2(2, 2)));
    }

    private static ApplicationFailureEvidence applicationFailure() {
        return new ApplicationFailureEvidence(
                "replay.execute", "java.lang.IllegalStateException", "session|failure-1",
                Optional.empty());
    }
}
