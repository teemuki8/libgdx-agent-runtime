package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Explicit application-provided metadata and allowlisted configuration for one recording. */
public record RecordingSpec(String id, String protocolVersion,
        List<RecordingCapabilityVersion> capabilityVersions, Optional<String> scenarioId,
        Optional<String> checkpointId, OptionalLong randomSeed,
        RuntimeValue.ObjectValue configuration, boolean replayGuaranteed) {
    /** Validates and copies manifest inputs. */
    public RecordingSpec {
        IdentifierSupport.validate(id, "recording id");
        IdentifierSupport.validate(protocolVersion, "recording protocol version");
        var orderedCapabilities = new ArrayList<>(
                Objects.requireNonNull(capabilityVersions, "capabilityVersions"));
        orderedCapabilities.sort(Comparator.comparing(
                RecordingCapabilityVersion::capabilityId));
        for (int index = 1; index < orderedCapabilities.size(); index++) {
            if (orderedCapabilities.get(index - 1).capabilityId()
                    .equals(orderedCapabilities.get(index).capabilityId())) {
                throw new IllegalArgumentException("duplicate recording capability id");
            }
        }
        capabilityVersions = List.copyOf(orderedCapabilities);
        scenarioId = Objects.requireNonNull(scenarioId, "scenarioId");
        checkpointId = Objects.requireNonNull(checkpointId, "checkpointId");
        randomSeed = Objects.requireNonNull(randomSeed, "randomSeed");
        configuration = Objects.requireNonNull(configuration, "configuration");
        scenarioId.ifPresent(value -> IdentifierSupport.validate(value, "recording scenario id"));
        checkpointId.ifPresent(
                value -> IdentifierSupport.validate(value, "recording checkpoint id"));
    }
}
