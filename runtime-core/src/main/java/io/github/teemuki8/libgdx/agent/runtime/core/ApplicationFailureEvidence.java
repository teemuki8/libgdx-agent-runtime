package io.github.teemuki8.libgdx.agent.runtime.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, validated structured evidence for one application callback failure.
 *
 * <p>Carries the stable category, the thrown exception class, a deterministic session-scoped
 * correlation identifier, and optional application-sanitized detail. Raw exception messages and
 * stack traces never appear here. The {@link #legacyEnvelope()} view is the only value rendered by
 * protocol 1.x projections and never contains sanitized or raw detail.
 */
public record ApplicationFailureEvidence(
        String category,
        String exceptionClass,
        String correlationId,
        Optional<String> sanitizedDetail) {
    /** Maximum category length in UTF-16 code units. */
    public static final int MAX_CATEGORY_LENGTH = 64;
    /** Maximum exception class length in UTF-16 code units. */
    public static final int MAX_EXCEPTION_CLASS_LENGTH = 256;
    /** Maximum correlation identifier length in UTF-16 code units. */
    public static final int MAX_CORRELATION_ID_LENGTH = 320;
    /** Maximum sanitized detail length in UTF-16 code units. */
    public static final int MAX_SANITIZED_DETAIL_LENGTH = 1_024;
    /** Capacity of {@link #legacyEnvelope()}: 320 + 1 + 64 + 1 + 256. */
    public static final int LEGACY_ENVELOPE_CAPACITY = 642;
    private static final int HASH_SUFFIX_LENGTH = 8;

    /** Validates and bounds every component. */
    public ApplicationFailureEvidence {
        Objects.requireNonNull(category, "category");
        if (category.isBlank()) {
            throw new IllegalArgumentException("category must not be blank");
        }
        if (category.length() > MAX_CATEGORY_LENGTH) {
            throw new IllegalArgumentException(
                    "category exceeds " + MAX_CATEGORY_LENGTH + " characters");
        }
        Objects.requireNonNull(exceptionClass, "exceptionClass");
        if (exceptionClass.isBlank()) {
            throw new IllegalArgumentException("exceptionClass must not be blank");
        }
        if (exceptionClass.length() > MAX_EXCEPTION_CLASS_LENGTH) {
            exceptionClass = truncateWithStableHash(exceptionClass);
        }
        Objects.requireNonNull(correlationId, "correlationId");
        if (correlationId.isBlank() || correlationId.length() > MAX_CORRELATION_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "correlationId is outside the public bound");
        }
        sanitizedDetail = Objects.requireNonNull(sanitizedDetail, "sanitizedDetail");
        if (sanitizedDetail.isPresent()) {
            String detail = sanitizedDetail.orElseThrow();
            if (detail.length() > MAX_SANITIZED_DETAIL_LENGTH) {
                throw new IllegalArgumentException(
                        "sanitizedDetail exceeds " + MAX_SANITIZED_DETAIL_LENGTH + " characters");
            }
            sanitizedDetail = Optional.of(detail);
        }
    }

    /**
     * Returns the bounded legacy projection: {@code correlationId + "|" + category + "|" +
     * exceptionClass}, at most {@value #LEGACY_ENVELOPE_CAPACITY} code units. Sanitized and raw
     * detail never appear in it.
     */
    public String legacyEnvelope() {
        return correlationId + "|" + category + "|" + exceptionClass;
    }

    private static String truncateWithStableHash(String value) {
        String hash = stableHash(value);
        int prefix = MAX_EXCEPTION_CLASS_LENGTH - hash.length();
        return value.substring(0, prefix) + hash;
    }

    private static String stableHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, HASH_SUFFIX_LENGTH / 2);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
