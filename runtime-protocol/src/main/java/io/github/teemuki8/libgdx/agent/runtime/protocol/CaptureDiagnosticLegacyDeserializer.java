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
 * identifier; the raw message and sanitized detail are never retained.
 *
 * <p>The legacy schema is enforced strictly: unknown top-level and {@code entityId} members are
 * rejected, {@code provider}/{@code property}/{@code exceptionClass}/{@code message}/
 * {@code entityId.value} require string tokens (with {@code null} permitted only for the optional
 * {@code entityId} and {@code property}), and no numeric, boolean, object, or array coercion is
 * accepted. Explicit token walking preserves strict trailing-token and unknown-property handling.
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
                case "provider" -> provider = requireString(parser, context, "provider");
                case "entityId" -> entityId = readEntityId(parser, context);
                case "property" -> property = readOptionalString(parser, context, "property");
                case "exceptionClass" -> exceptionClass =
                        requireString(parser, context, "exceptionClass");
                case "message" -> message = requireString(parser, context, "message");
                default -> context.reportInputMismatch(CaptureDiagnostic.class,
                        "unknown legacy diagnostic field '" + name + "'");
            }
        }
        if (provider == null) {
            context.reportInputMismatch(CaptureDiagnostic.class, "legacy provider is required");
        }
        if (exceptionClass == null) {
            context.reportInputMismatch(
                    CaptureDiagnostic.class, "legacy exceptionClass is required");
        }
        if (message == null) {
            context.reportInputMismatch(CaptureDiagnostic.class, "legacy message is required");
        }
        try {
            return new CaptureDiagnostic(provider, entityId, property,
                    readFailure(exceptionClass, message));
        } catch (IllegalArgumentException failure) {
            context.reportInputMismatch(
                    CaptureDiagnostic.class, "legacy diagnostic value is invalid");
            throw failure;
        }
    }

    private static String requireString(JsonParser parser, DeserializationContext context,
            String field) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            context.reportInputMismatch(CaptureDiagnostic.class,
                    "legacy " + field + " must be a string");
        }
        return parser.getText();
    }

    private static Optional<String> readOptionalString(JsonParser parser,
            DeserializationContext context, String field) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return Optional.empty();
        }
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            context.reportInputMismatch(CaptureDiagnostic.class,
                    "legacy " + field + " must be a string or null");
        }
        return Optional.of(parser.getText());
    }

    private static Optional<EntityId> readEntityId(
            JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return Optional.empty();
        }
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            context.reportInputMismatch(CaptureDiagnostic.class,
                    "legacy entityId must be an object or null");
        }
        String value = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String name = parser.currentName();
            parser.nextToken();
            if (!"value".equals(name)) {
                context.reportInputMismatch(CaptureDiagnostic.class,
                        "unknown legacy entityId member '" + name + "'");
            }
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                context.reportInputMismatch(CaptureDiagnostic.class,
                        "legacy entityId.value must be a string");
            }
            value = parser.getText();
        }
        if (value == null || value.isBlank()) {
            context.reportInputMismatch(
                    CaptureDiagnostic.class, "legacy entityId.value is required");
        }
        try {
            return Optional.of(EntityId.of(value));
        } catch (IllegalArgumentException failure) {
            context.reportInputMismatch(
                    CaptureDiagnostic.class, "legacy entityId.value is invalid");
            throw failure;
        }
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
        String klass = exceptionClass.isBlank() ? DEFAULT_EXCEPTION_CLASS : exceptionClass;
        String correlationId = "legacy|" + stableHash(message);
        return new ApplicationFailureEvidence(
                LEGACY_CATEGORY, klass, correlationId, Optional.empty());
    }

    private record LegacyEnvelope(String correlationId, String category, String exceptionClass) {}

    private static LegacyEnvelope parseEnvelope(String message) {
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
