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
 * accepted. The legacy {@code exceptionClass} must be nonblank and at most 256 code units (the
 * {@code IdentifierSupport} bound the historic record enforced). When the {@code message} parses
 * as an envelope, a candidate evidence is constructed and validated first (category at most 64,
 * correlation identifier at most 320, exception class at most 256): an envelope whose components
 * are out of bounds is treated as historic raw text and decoded through the deterministic
 * {@code legacy.capture} synthesis, and only a valid candidate is checked for equality with the
 * separately validated wire {@code exceptionClass} — a mismatch is rejected with fixed text that
 * never echoes wire or envelope component values. Explicit token walking preserves strict
 * trailing-token and unknown-property handling.
 */
public final class CaptureDiagnosticLegacyDeserializer
        extends JsonDeserializer<CaptureDiagnostic> {
    private static final String LEGACY_CATEGORY = "legacy.capture";

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
        requireValidExceptionClass(exceptionClass, context);
        try {
            return new CaptureDiagnostic(provider, entityId, property,
                    readFailure(exceptionClass, message, context));
        } catch (IllegalArgumentException failure) {
            context.reportInputMismatch(
                    CaptureDiagnostic.class, "legacy diagnostic value is invalid");
            throw failure;
        }
    }

    private static void requireValidExceptionClass(String exceptionClass,
            DeserializationContext context) throws IOException {
        if (exceptionClass.isBlank()) {
            context.reportInputMismatch(
                    CaptureDiagnostic.class, "legacy exceptionClass must not be blank");
            throw new IllegalArgumentException("legacy exceptionClass must not be blank");
        }
        if (exceptionClass.length() > ApplicationFailureEvidence.MAX_EXCEPTION_CLASS_LENGTH) {
            context.reportInputMismatch(CaptureDiagnostic.class,
                    "legacy exceptionClass exceeds "
                            + ApplicationFailureEvidence.MAX_EXCEPTION_CLASS_LENGTH + " characters");
            throw new IllegalArgumentException("legacy exceptionClass exceeds "
                    + ApplicationFailureEvidence.MAX_EXCEPTION_CLASS_LENGTH + " characters");
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

    private static ApplicationFailureEvidence readFailure(String exceptionClass, String message,
            DeserializationContext context) throws IOException {
        LegacyEnvelope envelope = parseEnvelope(message);
        if (envelope != null) {
            ApplicationFailureEvidence candidate = candidateEvidence(envelope);
            if (candidate != null) {
                if (!exceptionClass.equals(candidate.exceptionClass())) {
                    context.reportInputMismatch(CaptureDiagnostic.class,
                            "legacy exceptionClass does not match the message envelope");
                    throw new IllegalArgumentException(
                            "legacy exceptionClass does not match the message envelope");
                }
                return candidate;
            }
            // Envelope components out of bounds; treat the whole message as historic raw text.
        }
        String correlationId = "legacy|" + stableHash(message);
        return new ApplicationFailureEvidence(
                LEGACY_CATEGORY, exceptionClass, correlationId, Optional.empty());
    }

    /**
     * Constructs and validates a candidate evidence from the parsed envelope components, or
     * returns {@code null} when any component is outside the public bounds. The exception class
     * bound is enforced here instead of relying on {@link ApplicationFailureEvidence}'s
     * deterministic truncation so no wire-derived value is silently rewritten.
     */
    private static ApplicationFailureEvidence candidateEvidence(LegacyEnvelope envelope) {
        if (envelope.category.length() > ApplicationFailureEvidence.MAX_CATEGORY_LENGTH
                || envelope.correlationId.length()
                        > ApplicationFailureEvidence.MAX_CORRELATION_ID_LENGTH
                || envelope.exceptionClass.length()
                        > ApplicationFailureEvidence.MAX_EXCEPTION_CLASS_LENGTH) {
            return null;
        }
        try {
            return new ApplicationFailureEvidence(
                    envelope.category, envelope.exceptionClass,
                    envelope.correlationId, Optional.empty());
        } catch (IllegalArgumentException invalid) {
            // parseEnvelope guarantees nonempty components, so this guards whitespace-only
            // components that the evidence record rejects as blank.
            return null;
        }
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
