package io.github.teemuki8.libgdx.agent.runtime.protocol;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.github.teemuki8.libgdx.agent.runtime.core.ApplicationFailureEvidence;
import io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import java.io.IOException;
import java.util.Optional;

/**
 * Serializes {@link CaptureDiagnostic} to the closed protocol 2.0 wire shape.
 *
 * <p>Protocol 2.0 responses emit the structured {@code provider}, {@code entityId},
 * {@code property}, and {@code failure} fields carrying the full
 * {@link ApplicationFailureEvidence} (category, exception class, correlation identifier, optional
 * sanitized detail). Raw exception messages and stack traces never appear; an empty
 * {@code sanitizedDetail} is omitted.
 */
public final class CaptureDiagnosticStructuredSerializer
        extends JsonSerializer<CaptureDiagnostic> {
    private record StructuredCaptureDiagnostic(String provider, Optional<EntityId> entityId,
            Optional<String> property, ApplicationFailureEvidence failure) {}

    @Override
    public void serialize(CaptureDiagnostic value, JsonGenerator gen,
            SerializerProvider serializers) throws IOException {
        StructuredCaptureDiagnostic structured = new StructuredCaptureDiagnostic(
                value.provider(), value.entityId(), value.property(), value.failure());
        serializers.defaultSerializeValue(structured, gen);
    }
}
