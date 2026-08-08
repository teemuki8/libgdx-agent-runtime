package io.github.teemuki8.libgdx.agent.runtime.protocol;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import java.io.IOException;
import java.util.Optional;

/**
 * Serializes {@link CaptureDiagnostic} to the exact protocol 1.x wire shape.
 *
 * <p>Protocol 1.0–1.13 responses keep the legacy {@code provider}, {@code entityId},
 * {@code property}, {@code exceptionClass}, and {@code message} fields, re-derived from the
 * structured failure evidence: the exception class is taken from the evidence and the message is
 * the evidence's {@code ApplicationFailureEvidence#legacyEnvelope()}. Sanitized and raw detail
 * are never rendered.
 */
public final class CaptureDiagnosticLegacySerializer extends JsonSerializer<CaptureDiagnostic> {
    private record LegacyCaptureDiagnostic(String provider, Optional<EntityId> entityId,
            Optional<String> property, String exceptionClass, String message) {}

    @Override
    public void serialize(CaptureDiagnostic value, JsonGenerator gen,
            SerializerProvider serializers) throws IOException {
        LegacyCaptureDiagnostic legacy = new LegacyCaptureDiagnostic(
                value.provider(), value.entityId(), value.property(),
                value.failure().exceptionClass(), value.failure().legacyEnvelope());
        serializers.defaultSerializeValue(legacy, gen);
    }
}
