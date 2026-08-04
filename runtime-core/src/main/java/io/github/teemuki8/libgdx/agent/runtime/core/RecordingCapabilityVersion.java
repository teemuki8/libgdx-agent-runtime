package io.github.teemuki8.libgdx.agent.runtime.core;

/** One stable capability/version claim embedded in a recording manifest. */
public record RecordingCapabilityVersion(String capabilityId, String version) {
    /** Validates bounded identifiers before registry-specific limits are applied. */
    public RecordingCapabilityVersion {
        IdentifierSupport.validate(capabilityId, "recording capability id");
        IdentifierSupport.validate(version, "recording capability version");
    }
}
