package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Explicit origin and observable selection for one replay-ready recording capture. */
public record ReplayCaptureSpec(RecordingSpec recording, DeterminismProfile profile,
        List<SimulationConfigurationRequirement> configurationRequirements,
        List<SimulationEvidenceRequirement> evidenceRequirements, List<EventType> eventTypes) {
    /** Maximum exact configuration facts selected by one replay. */
    public static final int MAX_CONFIGURATION_REQUIREMENTS = 32;
    /** Maximum per-tick completeness facts selected by one replay. */
    public static final int MAX_EVIDENCE_REQUIREMENTS = 8;
    /** Maximum event types selected by one replay. */
    public static final int MAX_EVENT_TYPES = 16;

    /** Validates one explicit origin and deterministically copies every selector. */
    public ReplayCaptureSpec {
        Objects.requireNonNull(recording, "recording");
        Objects.requireNonNull(profile, "profile");
        if (recording.scenarioId().isPresent() == recording.checkpointId().isPresent()) {
            throw new IllegalArgumentException(
                    "replay capture requires exactly one scenario or checkpoint origin");
        }
        if (!recording.replayGuaranteed()) {
            throw new IllegalArgumentException(
                    "replay capture requires explicit replay testimony");
        }
        configurationRequirements = configuration(configurationRequirements);
        evidenceRequirements = evidence(evidenceRequirements);
        eventTypes = eventTypes(eventTypes);
        if (!profile.comparisonScope().includeEvents() && !eventTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    "event selectors require event comparison to be enabled");
        }
    }

    private static List<SimulationConfigurationRequirement> configuration(
            List<SimulationConfigurationRequirement> values) {
        Objects.requireNonNull(values, "configurationRequirements");
        requireLimit(values.size(), MAX_CONFIGURATION_REQUIREMENTS,
                "configuration requirement");
        ArrayList<SimulationConfigurationRequirement> copy = new ArrayList<>(values);
        copy.forEach(value -> Objects.requireNonNull(value, "configuration requirement"));
        copy.sort(Comparator.comparing((SimulationConfigurationRequirement value) ->
                value.entityId().value()).thenComparing(
                        SimulationConfigurationRequirement::property));
        rejectDuplicateFacts(copy.stream()
                .map(value -> value.entityId().value() + '\u0000' + value.property()).toList(),
                "configuration requirement");
        return List.copyOf(copy);
    }

    private static List<SimulationEvidenceRequirement> evidence(
            List<SimulationEvidenceRequirement> values) {
        Objects.requireNonNull(values, "evidenceRequirements");
        requireLimit(values.size(), MAX_EVIDENCE_REQUIREMENTS, "evidence requirement");
        ArrayList<SimulationEvidenceRequirement> copy = new ArrayList<>(values);
        copy.forEach(value -> Objects.requireNonNull(value, "evidence requirement"));
        copy.sort(Comparator.comparing((SimulationEvidenceRequirement value) ->
                value.entityId().value()).thenComparing(SimulationEvidenceRequirement::property));
        rejectDuplicateFacts(copy.stream()
                .map(value -> value.entityId().value() + '\u0000' + value.property()).toList(),
                "evidence requirement");
        return List.copyOf(copy);
    }

    private static List<EventType> eventTypes(List<EventType> values) {
        Objects.requireNonNull(values, "eventTypes");
        requireLimit(values.size(), MAX_EVENT_TYPES, "event type");
        ArrayList<EventType> copy = new ArrayList<>(values);
        copy.forEach(value -> Objects.requireNonNull(value, "event type"));
        copy.sort(Comparator.comparing(EventType::value));
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).equals(copy.get(index))) {
                throw new IllegalArgumentException("duplicate replay event type");
            }
        }
        return List.copyOf(copy);
    }

    private static void rejectDuplicateFacts(List<String> values, String name) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).equals(values.get(index))) {
                throw new IllegalArgumentException("duplicate " + name);
            }
        }
    }

    private static void requireLimit(int size, int limit, String name) {
        if (size > limit) {
            throw new IllegalArgumentException(name + " count exceeds the hard bound");
        }
    }
}
