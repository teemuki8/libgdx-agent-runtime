package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SimulationDeterminismContractTest {
    @Test
    void specificationOrdersBoundedSelectorsAndDefensivelyCopiesInputs() {
        ArrayList<SimulationDeterminismInput> inputs = new ArrayList<>(List.of(
                new SimulationDeterminismInput(2, "right", RuntimeValues.object()),
                new SimulationDeterminismInput(1, "jump", RuntimeValues.object()),
                new SimulationDeterminismInput(2, "left", RuntimeValues.object())));
        ArrayList<SimulationConfigurationRequirement> configuration = new ArrayList<>(List.of(
                new SimulationConfigurationRequirement(EntityId.of("world"), "iterations",
                        RuntimeValues.integer(8)),
                new SimulationConfigurationRequirement(EntityId.of("world"), "fixedStep",
                        RuntimeValues.integer(16))));
        ArrayList<SimulationEvidenceRequirement> completeness = new ArrayList<>(List.of(
                new SimulationEvidenceRequirement(EntityId.of("contacts"), "complete")));
        ArrayList<EventType> eventTypes = new ArrayList<>(List.of(
                EventType.of("box2d.contact.end"), EventType.of("box2d.contact.begin")));

        SimulationDeterminismSpec spec = new SimulationDeterminismSpec(execution(), inputs,
                configuration, completeness, eventTypes);
        inputs.clear();
        configuration.clear();
        completeness.clear();
        eventTypes.clear();

        assertEquals(List.of("jump", "right", "left"), spec.inputs().stream()
                .map(SimulationDeterminismInput::inputId).toList());
        assertEquals(List.of("fixedStep", "iterations"), spec.configurationRequirements().stream()
                .map(SimulationConfigurationRequirement::property).toList());
        assertEquals(List.of("box2d.contact.begin", "box2d.contact.end"), spec.eventTypes().stream()
                .map(EventType::value).toList());
        assertEquals(1, spec.evidenceRequirements().size());
        assertThrows(UnsupportedOperationException.class, () -> spec.inputs().clear());
    }

    @Test
    void specificationRejectsInvalidTicksDuplicatesAndHardBounds() {
        assertThrows(IllegalArgumentException.class, () -> new SimulationDeterminismInput(
                0, "jump", RuntimeValues.object()));
        assertThrows(IllegalArgumentException.class, () -> new SimulationDeterminismSpec(
                execution(), List.of(new SimulationDeterminismInput(
                        4, "jump", RuntimeValues.object())), List.of(), List.of(), List.of()));
        SimulationConfigurationRequirement duplicate = new SimulationConfigurationRequirement(
                EntityId.of("world"), "fixedStep", RuntimeValues.integer(16));
        assertThrows(IllegalArgumentException.class, () -> new SimulationDeterminismSpec(
                execution(), List.of(), List.of(duplicate, duplicate), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SimulationDeterminismSpec(
                execution(false), List.of(), List.of(), List.of(),
                List.of(EventType.of("box2d.contact.begin"))));

        ArrayList<SimulationDeterminismInput> tooMany = new ArrayList<>();
        for (int index = 0; index <= SimulationDeterminismSpec.MAX_INPUTS; index++) {
            tooMany.add(new SimulationDeterminismInput(1, "jump", RuntimeValues.object()));
        }
        assertThrows(IllegalArgumentException.class, () -> new SimulationDeterminismSpec(
                execution(), tooMany, List.of(), List.of(), List.of()));
    }

    @Test
    void divergenceAndResultRequireClosedConsistentEvidence() {
        DeterminismDifference difference = new DeterminismDifference(
                DeterminismDifferenceKind.PROPERTY, Optional.of("box2d.body.ball:position"),
                Optional.of(RuntimeValues.vector2(1, 2)),
                Optional.of(RuntimeValues.vector2(2, 2)));
        SimulationDeterminismDivergence divergence = new SimulationDeterminismDivergence(
                2, new SimulationTickId(4), new SimulationTickId(7),
                new ExecutionEpochId(1), new ExecutionEpochId(2),
                new FrameId(3), new FrameId(6), difference);
        SimulationDeterminismResult result = new SimulationDeterminismResult(
                DeterminismStatus.DIVERGED, "first divergence in selected simulation evidence",
                execution().profile(), Optional.of(divergence),
                new DeterminismBounds(2, 3, 2, 6, 128, 1_000), Optional.empty());

        assertEquals(2, result.divergence().orElseThrow().epochTick());
        assertNotSame(difference, new DeterminismDifference(difference.kind(), difference.fact(),
                difference.left(), difference.right()));
        assertThrows(IllegalArgumentException.class, () -> new SimulationDeterminismResult(
                DeterminismStatus.EQUAL, "equal", execution().profile(), Optional.of(divergence),
                result.bounds(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new SimulationDeterminismResult(
                DeterminismStatus.DIVERGED, "diverged", execution().profile(), Optional.empty(),
                result.bounds(), Optional.empty()));
    }

    private static DeterminismSpec execution() {
        return execution(true);
    }

    private static DeterminismSpec execution(boolean includeEvents) {
        return new DeterminismSpec("ball-drop", 7, RuntimeValues.object(), 2, 3, 16,
                new DeterminismProfile(new SnapshotComparisonScope(
                        List.of(EntityId.of("world")), List.of("position"), List.of(),
                        includeEvents, false), false));
    }
}
