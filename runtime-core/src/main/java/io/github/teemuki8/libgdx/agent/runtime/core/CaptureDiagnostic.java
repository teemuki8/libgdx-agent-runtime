package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/**
 * Remotely safe capture failure retained without a stack trace.
 *
 * <p>The message is runtime-composed bounded text: a stable category, exception class,
 * deterministic correlation identifier, and optional application-sanitized detail. Raw exception
 * messages never appear here.
 */
public record CaptureDiagnostic(
        String provider,
        Optional<EntityId> entityId,
        Optional<String> property,
        String exceptionClass,
        String message) {
    /** Validates bounded diagnostic fields. */
    public CaptureDiagnostic {
        IdentifierSupport.validate(provider, "provider");
        entityId = Objects.requireNonNull(entityId, "entityId");
        property = Objects.requireNonNull(property, "property");
        property.ifPresent(value -> IdentifierSupport.validate(value, "property"));
        IdentifierSupport.validate(exceptionClass, "exceptionClass");
        message = Objects.requireNonNull(message, "message");
    }
}
