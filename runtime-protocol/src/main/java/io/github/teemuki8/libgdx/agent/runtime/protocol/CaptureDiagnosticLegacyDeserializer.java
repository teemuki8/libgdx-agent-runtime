package io.github.teemuki8.libgdx.agent.runtime.protocol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import io.github.teemuki8.libgdx.agent.runtime.core.ApplicationFailureEvidence;
import io.github.teemuki8.libgdx.agent.runtime.core.CaptureDiagnostic;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Decodes the protocol 1.x legacy {@link CaptureDiagnostic} wire shape into structured evidence.
 *
 * <p>The legacy {@code message} field is split at the rightmost two delimiters (the correlation
 * identifier itself contains {@code |}) to reconstruct {@code correlationId|category|
 * exceptionClass}. Historic 1.0 responses whose message was arbitrary raw text are decoded
 * deterministically into a stable {@code legacy.capture} category and a hash-derived correlation
 * identifier; the raw message and sanitized detail are never retained. The fields are parsed with
 * explicit token walking so strict trailing-token and unknown-property handling is preserved.
 */
public final class CaptureDiagnosticLegacyDeserializer
        extends JsonDeserializer<CaptureDiagnostic> {
    private static final String LEGACY_CATEGORY = "legacy.capture";
    private static final String DEFAULT_EXCEPTION_CLASS = "java.lang.Exception";

    @Override
    public CaptureDiagnostic deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        String provider = null;
        Optional<EntityId> entityId = Optional.empty();
        Optional<String> property = Optional.empty();
        String exceptionClass = null;
        String message = null;
        if (parser.currentToken() == null) {
            parser.nextToken();
        }
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            context.reportInputMismatch(
                    CaptureDiagnostic.class, "expected a JSON object");
        }
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String name = parser.currentName();
            parser.nextToken();
            switch (name) {
                case "provider" -> provider = parser.getValueAsString();
                case "entityId" -> entityId = readEntityId(parser);
                case "property" -> property = Optional.ofNullable(parser.getValueAsString());
                case "exceptionClass" -> exceptionClass = parser.getValueAsString();
                case "message" -> message = parser.getValueAsString();
                default -> parser.skipChildren();
            }
        }
        return new CaptureDiagnostic(provider, entityId, property,
                readFailure(exceptionClass, message));
    }

    private static Optional<EntityId> readEntityId(JsonParser parser) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return Optional.empty();
        }
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return Optional.empty();
        }
        String value = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if ("value".equals(parser.currentName())) {
                parser.nextToken();
                value = parser.getText();
            } else {
                parser.skipChildren();
            }
        }
        return value == null || value.isBlank()
                ? Optional.empty() : Optional.of(EntityId.of(value));
    }

    private static ApplicationFailureEvidence readFailure(String exceptionClass, String message) {
        LegacyEnvelope envelope = parseEnvelope(message);
        if (envelope != null) {
            try {
                return new ApplicationFailureEvidence(
                        envelope.category, envelope.exceptionClass,
                        envelope.correlationId, Optional.empty());
            } catch (IllegalArgumentException failure) {
                // Not a valid envelope; fall through to the deterministic legacy synthesis.
            }
        }
        String klass = exceptionClass == null || exceptionClass.isBlank()
                ? DEFAULT_EXCEPTION_CLASS : exceptionClass;
        String correlationId = "legacy|" + stableHash(message == null ? "" : message);
        return new ApplicationFailureEvidence(
                LEGACY_CATEGORY, klass, correlationId, Optional.empty());
    }

    private record LegacyEnvelope(String correlationId, String category, String exceptionClass) {}

    private static LegacyEnvelope parseEnvelope(String message) {
        if (message == null) {
            return null;
        }
        int last = message.lastIndexOf('|');
        if (last <= 0) {
            return null;
        }
        int secondLast = message.lastIndexOf('|', last - 1);
        if (secondLast < 0) {
            return null;
        }
        String exceptionClass = message.substring(last + 1);
        String category = message.substring(secondLast + 1, last);
        String correlationId = message.substring(0, secondLast);
        if (exceptionClass.isEmpty() || category.isEmpty() || correlationId.isEmpty()) {
            return null;
        }
        return new LegacyEnvelope(correlationId, category, exceptionClass);
    }

    private static String stableHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
