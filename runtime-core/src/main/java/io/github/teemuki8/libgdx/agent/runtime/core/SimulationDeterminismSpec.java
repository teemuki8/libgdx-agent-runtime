package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Additive exact-tick determinism request over selected immutable simulation evidence. */
public record SimulationDeterminismSpec(DeterminismSpec execution,
        List<SimulationDeterminismInput> inputs,
        List<SimulationConfigurationRequirement> configurationRequirements,
        List<SimulationEvidenceRequirement> evidenceRequirements,
        List<EventType> eventTypes) {
    /** Maximum repeated registered inputs in one request. */
    public static final int MAX_INPUTS = 256;
    /** Maximum exact configuration facts in one request. */
    public static final int MAX_CONFIGURATION_REQUIREMENTS = 32;
    /** Maximum per-tick completeness facts in one request. */
    public static final int MAX_EVIDENCE_REQUIREMENTS = 8;
    /** Maximum selected event types in one request. */
    public static final int MAX_EVENT_TYPES = 16;

    /** Validates, deterministically orders, and defensively copies the closed request. */
    public SimulationDeterminismSpec {
        Objects.requireNonNull(execution, "execution");
        inputs = inputs(inputs, execution.ticksPerRepeat());
        configurationRequirements = configuration(configurationRequirements);
        evidenceRequirements = evidence(evidenceRequirements);
        eventTypes = eventTypes(eventTypes);
        if (!execution.profile().comparisonScope().includeEvents() && !eventTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    "event selectors require event comparison to be enabled");
        }
    }

    private static List<SimulationDeterminismInput> inputs(
            List<SimulationDeterminismInput> values, int maximumTick) {
        Objects.requireNonNull(values, "inputs");
        requireLimit(values.size(), MAX_INPUTS, "determinism input");
        ArrayList<SimulationDeterminismInput> copy = new ArrayList<>(values.size());
        values.forEach(value -> {
            SimulationDeterminismInput input = Objects.requireNonNull(value, "input");
            if (input.epochTick() > maximumTick) {
                throw new IllegalArgumentException("determinism input tick exceeds the run");
            }
            copy.add(input);
        });
        copy.sort(Comparator.comparingLong(SimulationDeterminismInput::epochTick));
        return List.copyOf(copy);
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
                throw new IllegalArgumentException("duplicate determinism event type");
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
