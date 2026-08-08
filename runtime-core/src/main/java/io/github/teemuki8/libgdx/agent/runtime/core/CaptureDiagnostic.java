package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/**
 * Remotely safe capture failure retained without a stack trace.
 *
 * <p>Carries the structured {@link ApplicationFailureEvidence}; the protocol 1.x projection
 * renders {@link ApplicationFailureEvidence#legacyEnvelope()} as the legacy message and the
 * exception class from the evidence. Raw exception messages never appear here.
 */
public record CaptureDiagnostic(
        String provider,
        Optional<EntityId> entityId,
        Optional<String> property,
        ApplicationFailureEvidence failure) {
    /** Validates bounded diagnostic fields. */
    public CaptureDiagnostic {
        IdentifierSupport.validate(provider, "provider");
        entityId = Objects.requireNonNull(entityId, "entityId");
        property = Objects.requireNonNull(property, "property");
        property.ifPresent(value -> IdentifierSupport.validate(value, "property"));
        failure = Objects.requireNonNull(failure, "failure");
    }
}
