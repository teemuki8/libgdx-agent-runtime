package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.OptionalLong;

/** Exact bounded canonical byte accounting for retained replay-sidecar metadata. */
final class ReplayCanonicalSize {
    private ReplayCanonicalSize() {}

    static long captureMetadata(ReplayCaptureSpec spec, long fixedStepNanos) {
        if (fixedStepNanos <= 0) {
            throw new IllegalArgumentException("fixedStepNanos must be positive");
        }
        RecordingSpec recording = spec.recording();
        long size = Integer.BYTES;
        size = add(size, DeterminismCanonicalSize.string(recording.id()));
        size = add(size, DeterminismCanonicalSize.string(recording.protocolVersion()));
        size = add(size, DeterminismCanonicalSize.listPrefix());
        for (RecordingCapabilityVersion capability : recording.capabilityVersions()) {
            size = add(size, DeterminismCanonicalSize.string(capability.capabilityId()));
            size = add(size, DeterminismCanonicalSize.string(capability.version()));
        }
        size = add(size, 1);
        String origin = recording.scenarioId().orElseGet(
                () -> recording.checkpointId().orElseThrow());
        size = add(size, DeterminismCanonicalSize.string(origin));
        size = add(size, optionalLong(recording.randomSeed()));
        size = add(size, DeterminismCanonicalSize.value(recording.configuration()));
        size = add(size, Long.BYTES);

        SnapshotComparisonScope scope = spec.profile().comparisonScope();
        size = add(size, DeterminismCanonicalSize.listPrefix());
        for (EntityId entityId : scope.entityIds()) {
            size = add(size, DeterminismCanonicalSize.string(entityId.value()));
        }
        size = add(size, DeterminismCanonicalSize.listPrefix());
        for (String property : scope.properties()) {
            size = add(size, DeterminismCanonicalSize.string(property));
        }
        size = add(size, DeterminismCanonicalSize.listPrefix());
        for (String property : scope.excludedProperties()) {
            size = add(size, DeterminismCanonicalSize.string(property));
        }
        size = add(size, 3);

        size = add(size, DeterminismCanonicalSize.listPrefix());
        for (SimulationConfigurationRequirement requirement
                : spec.configurationRequirements()) {
            size = add(size, DeterminismCanonicalSize.simulationConfiguration(requirement));
        }
        size = add(size, DeterminismCanonicalSize.listPrefix());
        for (SimulationEvidenceRequirement requirement : spec.evidenceRequirements()) {
            size = add(size, DeterminismCanonicalSize.simulationEvidence(requirement));
        }
        size = add(size, DeterminismCanonicalSize.listPrefix());
        for (EventType eventType : spec.eventTypes()) {
            size = add(size, DeterminismCanonicalSize.simulationEventType(eventType));
        }
        return size;
    }

    static long add(long left, long right) {
        return DeterminismCanonicalSize.add(left, right);
    }

    private static long optionalLong(OptionalLong value) {
        return 1 + (value.isPresent() ? Long.BYTES : 0);
    }
}
