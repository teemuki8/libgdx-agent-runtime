package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Immutable versioned recording manifest metadata repeated on each bounded chunk.
 *
 * <p>{@code encodedBytes} is the exact size of the schema-versioned, type-tagged core encoding,
 * including UTF-8 string payloads. Protocol and MCP transport encodings are bounded separately.
 */
public record RecordingMetadata(int schemaVersion, String runtimeVersion, String protocolVersion,
        List<RecordingCapabilityVersion> capabilityVersions, String recordingId,
        SessionId sessionId, ExecutionEpochId startedExecutionEpochId,
        Optional<String> scenarioId, Optional<String> checkpointId, OptionalLong randomSeed,
        RuntimeValue.ObjectValue configuration, RecordingStopReason stopReason,
        List<RecordingTruncation> truncations, boolean reproductionEvidenceComplete,
        boolean replayGuaranteed, long observedItems, long retainedItems, long encodedBytes) {
    /** Validates and copies manifest metadata. */
    public RecordingMetadata {
        if (schemaVersion <= 0 || observedItems < 0 || retainedItems < 0
                || retainedItems > observedItems || encodedBytes < 0) {
            throw new IllegalArgumentException("invalid recording metadata counts");
        }
        IdentifierSupport.validate(runtimeVersion, "recording runtime version");
        IdentifierSupport.validate(protocolVersion, "recording protocol version");
        IdentifierSupport.validate(recordingId, "recording id");
        Objects.requireNonNull(runtimeVersion, "runtimeVersion");
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        capabilityVersions = List.copyOf(Objects.requireNonNull(
                capabilityVersions, "capabilityVersions"));
        Objects.requireNonNull(recordingId, "recordingId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(startedExecutionEpochId, "startedExecutionEpochId");
        scenarioId = Objects.requireNonNull(scenarioId, "scenarioId");
        checkpointId = Objects.requireNonNull(checkpointId, "checkpointId");
        randomSeed = Objects.requireNonNull(randomSeed, "randomSeed");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(stopReason, "stopReason");
        truncations = List.copyOf(Objects.requireNonNull(truncations, "truncations"));
        boolean incomplete = truncations.stream()
                .anyMatch(RecordingTruncation::reproductionEvidenceIncomplete);
        if (reproductionEvidenceComplete == incomplete) {
            throw new IllegalArgumentException(
                    "recording completeness and truncation evidence disagree");
        }
    }
}
