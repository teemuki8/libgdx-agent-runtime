package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ObservableEvidenceComparatorTest {
    @Test
    void normalizesVolatileIdentifiersAndFindsEachStableDifferenceInPriorityOrder() {
        long[] state = {10};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("comparator-differences"))
                .clock(() -> 1)
                .build();
        runtime.entities().register(EntityId.of("world"), EntityType.of("state"),
                () -> "World", inspector -> inspector
                        .property("complete", () -> true)
                        .property("state", () -> state[0]));
        runtime.start();
        ObservableEvidenceComparator comparator = new ObservableEvidenceComparator(runtime);
        ObservableEvidenceComparator.Counters counters =
                new ObservableEvidenceComparator.Counters();
        DeterminismProfile profile = profile(true, true, true);

        FrameSnapshot reference = frame(runtime, state, 10,
                "simulation.event", "simulation.decision", "ui-frame", 1);
        FrameSnapshot normalizedEqual = frame(runtime, state, 10,
                "simulation.event", "simulation.decision", "ui-frame", 2);
        ObservableEvidenceComparator.FrameEvidence referenceEvidence = comparator.capture(
                reference, profile, List.of(), limits(), counters).orElseThrow();
        ObservableEvidenceComparator.FrameEvidence equalEvidence = comparator.capture(
                normalizedEqual, profile, List.of(), limits(), counters).orElseThrow();

        assertTrue(comparator.difference(referenceEvidence, equalEvidence).isEmpty());

        assertDifference(comparator, referenceEvidence,
                comparator.capture(frame(runtime, state, 11,
                                "other.event", "other.decision", "other-ui", 3),
                        profile, List.of(), limits(), counters).orElseThrow(),
                DeterminismDifferenceKind.PROPERTY, "world:state");
        assertDifference(comparator, referenceEvidence,
                comparator.capture(frame(runtime, state, 10,
                                "other.event", "simulation.decision", "ui-frame", 4),
                        profile, List.of(), limits(), counters).orElseThrow(),
                DeterminismDifferenceKind.EVENT, "event:0");
        assertDifference(comparator, referenceEvidence,
                comparator.capture(frame(runtime, state, 10,
                                "simulation.event", "other.decision", "ui-frame", 5),
                        profile, List.of(), limits(), counters).orElseThrow(),
                DeterminismDifferenceKind.DECISION, "decision:0");
        assertDifference(comparator, referenceEvidence,
                comparator.capture(frame(runtime, state, 10,
                                "simulation.event", "simulation.decision", "other-ui", 6),
                        profile, List.of(), limits(), counters).orElseThrow(),
                DeterminismDifferenceKind.UI_CORRELATION, "uiCorrelation:0");
    }

    @Test
    void validatesConfigurationCompletenessAndSelectedFactsWithStableReasons() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("comparator-requirements"))
                .clock(() -> 1)
                .build();
        runtime.entities().register(EntityId.of("world"), EntityType.of("state"),
                () -> "World", inspector -> inspector
                        .property("complete", () -> false)
                        .property("fixedStep", () -> 16L)
                        .property("state", () -> 10L));
        runtime.start();
        FrameSnapshot frame = runtime.latestFrame().orElseThrow();
        ObservableEvidenceComparator comparator = new ObservableEvidenceComparator(runtime);

        assertTrue(comparator.configurationProblem(frame, List.of(
                new SimulationConfigurationRequirement(
                        EntityId.of("world"), "fixedStep", RuntimeValues.integer(16)))).isEmpty());
        assertEquals(Optional.of("configuration requirement does not match baseline: world:fixedStep"),
                comparator.configurationProblem(frame, List.of(
                        new SimulationConfigurationRequirement(
                                EntityId.of("world"), "fixedStep", RuntimeValues.integer(17)))));
        assertEquals(Optional.of("selected simulation evidence is incomplete: world:complete"),
                comparator.evidenceProblem(frame, List.of(
                        new SimulationEvidenceRequirement(EntityId.of("world"), "complete"))));
        assertEquals(Optional.of("selected simulation entity is missing: missing"),
                comparator.selectionProblem(frame, new SnapshotComparisonScope(
                        List.of(EntityId.of("missing")), List.of(), List.of(), false, false)));
        assertEquals(Optional.of("selected simulation property is missing: absent"),
                comparator.selectionProblem(frame, new SnapshotComparisonScope(
                        List.of(EntityId.of("world")), List.of("absent"), List.of(), false, false)));
    }

    @Test
    void stopsAtEntityFactAndEncodedByteLimitsWithSaturatingEvidence() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("comparator-limits"))
                .clock(() -> 1)
                .build();
        runtime.entities().register(EntityId.of("first"), EntityType.of("state"),
                () -> "First", inspector -> inspector.property("value", () -> 1L));
        runtime.entities().register(EntityId.of("second"), EntityType.of("state"),
                () -> "Second", inspector -> inspector.property("value", () -> 2L));
        runtime.start();
        FrameSnapshot frame = runtime.latestFrame().orElseThrow();
        ObservableEvidenceComparator comparator = new ObservableEvidenceComparator(runtime);
        DeterminismProfile profile = new DeterminismProfile(new SnapshotComparisonScope(
                List.of(), List.of("value"), List.of(), false, false), false);

        ObservableEvidenceComparator.Counters entities =
                new ObservableEvidenceComparator.Counters();
        assertTrue(comparator.capture(frame, profile, List.of(),
                new ObservableEvidenceComparator.Limits(1, 10, 10_000), entities).isEmpty());
        assertEquals(1, entities.observedEntities());
        assertEquals(Optional.of("determinism entity count limit exceeded"),
                entities.incompleteReason());

        ObservableEvidenceComparator.Counters facts =
                new ObservableEvidenceComparator.Counters();
        assertTrue(comparator.capture(frame, profile, List.of(),
                new ObservableEvidenceComparator.Limits(10, 1, 10_000), facts).isEmpty());
        assertEquals(1, facts.observedFacts());
        assertEquals(Optional.of("determinism fact count limit exceeded"),
                facts.incompleteReason());

        ObservableEvidenceComparator.Counters bytes =
                new ObservableEvidenceComparator.Counters();
        assertTrue(comparator.capture(frame, profile, List.of(),
                new ObservableEvidenceComparator.Limits(10, 10, 1), bytes).isEmpty());
        assertEquals(Optional.of("encoded determinism evidence limit exceeded"),
                bytes.incompleteReason());
    }

    @Test
    void retainsComparableEvidenceButMarksCaptureDiagnosticsIncomplete() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("comparator-diagnostics"))
                .clock(() -> 1)
                .build();
        runtime.entities().register(EntityId.of("broken"), EntityType.of("state"),
                () -> "Broken", inspector -> inspector.property("value",
                        (java.util.function.LongSupplier) () -> {
                    throw new IllegalStateException("private diagnostic");
                }));
        runtime.start();
        ObservableEvidenceComparator comparator = new ObservableEvidenceComparator(runtime);
        ObservableEvidenceComparator.Counters counters =
                new ObservableEvidenceComparator.Counters();
        DeterminismProfile profile = new DeterminismProfile(new SnapshotComparisonScope(
                List.of(EntityId.of("broken")), List.of(), List.of(), false, false), false);

        assertTrue(comparator.capture(runtime.latestFrame().orElseThrow(), profile, List.of(),
                limits(), counters).isPresent());
        assertEquals(Optional.of("capture diagnostics or truncation could hide a divergence"),
                counters.incompleteReason());
    }

    private static FrameSnapshot frame(AgentRuntime runtime, long[] state, long value,
            String eventType, String decisionType, String uiFrameId, long volatileFrameId) {
        state[0] = value;
        runtime.frame(1, () -> {
            runtime.emit(EventSpec.type(eventType)
                    .subject(EntityId.of("world"))
                    .attribute("frameId", RuntimeValues.integer(volatileFrameId))
                    .attribute("stable", RuntimeValues.string("value")));
            try (DecisionScope decision = runtime.beginDecision(
                    DecisionType.of(decisionType), EntityId.of("world"))) {
                decision.id();
            }
        });
        FrameSnapshot frame = runtime.latestFrame().orElseThrow();
        runtime.uiCorrelations().recordFrame(new UiFrameCorrelation(
                frame.executionEpochId(), frame.frameId(), "ui-session",
                Optional.of(uiFrameId), Optional.empty()));
        return frame;
    }

    private static DeterminismProfile profile(
            boolean events, boolean decisions, boolean ui) {
        return new DeterminismProfile(new SnapshotComparisonScope(
                List.of(EntityId.of("world")), List.of("state"), List.of(),
                events, decisions), ui);
    }

    private static ObservableEvidenceComparator.Limits limits() {
        return new ObservableEvidenceComparator.Limits(100, 100, 1_000_000);
    }

    private static void assertDifference(ObservableEvidenceComparator comparator,
            ObservableEvidenceComparator.FrameEvidence reference,
            ObservableEvidenceComparator.FrameEvidence candidate,
            DeterminismDifferenceKind expectedKind, String expectedFact) {
        DeterminismDifference difference = comparator.difference(
                reference, candidate).orElseThrow();
        assertEquals(expectedKind, difference.kind());
        assertEquals(Optional.of(expectedFact), difference.fact());
    }
}
