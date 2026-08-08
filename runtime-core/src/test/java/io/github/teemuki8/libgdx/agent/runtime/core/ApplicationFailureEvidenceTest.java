package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ApplicationFailureEvidenceTest {
    private static final String CATEGORY = "provider.property";
    private static final String EXCEPTION_CLASS = "java.lang.IllegalStateException";
    private static final String CORRELATION = "session-1|failure-7";

    @Test
    void rejectsBlankAndOversizedCategory() {
        assertThrows(IllegalArgumentException.class, () -> new ApplicationFailureEvidence(
                "  ", EXCEPTION_CLASS, CORRELATION, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationFailureEvidence(
                "c".repeat(ApplicationFailureEvidence.MAX_CATEGORY_LENGTH + 1),
                EXCEPTION_CLASS, CORRELATION, Optional.empty()));
    }

    @Test
    void acceptsCategoryAtExactBoundary() {
        ApplicationFailureEvidence evidence = new ApplicationFailureEvidence(
                "c".repeat(ApplicationFailureEvidence.MAX_CATEGORY_LENGTH),
                EXCEPTION_CLASS, CORRELATION, Optional.empty());
        assertEquals(ApplicationFailureEvidence.MAX_CATEGORY_LENGTH,
                evidence.category().length());
    }

    @Test
    void rejectsBlankAndOversizedCorrelationId() {
        assertThrows(IllegalArgumentException.class, () -> new ApplicationFailureEvidence(
                CATEGORY, EXCEPTION_CLASS, " ", Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationFailureEvidence(
                CATEGORY, EXCEPTION_CLASS,
                "x".repeat(ApplicationFailureEvidence.MAX_CORRELATION_ID_LENGTH + 1),
                Optional.empty()));
    }

    @Test
    void truncatesOversizedExceptionClassDeterministicallyWithStableSuffixHash() {
        String longName = "com.example.VeryLongExceptionClassName"
                + "x".repeat(ApplicationFailureEvidence.MAX_EXCEPTION_CLASS_LENGTH);
        ApplicationFailureEvidence first = new ApplicationFailureEvidence(
                CATEGORY, longName, CORRELATION, Optional.empty());
        ApplicationFailureEvidence second = new ApplicationFailureEvidence(
                CATEGORY, longName, CORRELATION, Optional.empty());
        assertEquals(first.exceptionClass(), second.exceptionClass(),
                "truncation must be reproducible across constructions");
        assertTrue(first.exceptionClass().length()
                <= ApplicationFailureEvidence.MAX_EXCEPTION_CLASS_LENGTH);
        assertTrue(first.exceptionClass().startsWith(longName.substring(0, 32)));
        assertNotEquals(longName, first.exceptionClass());
        assertTrue(first.legacyEnvelope().length()
                <= ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY);
    }

    @Test
    void rejectsOversizedSanitizedDetailAndRequiresNonNullOptional() {
        assertThrows(IllegalArgumentException.class, () -> new ApplicationFailureEvidence(
                CATEGORY, EXCEPTION_CLASS, CORRELATION,
                Optional.of("x".repeat(ApplicationFailureEvidence.MAX_SANITIZED_DETAIL_LENGTH + 1))));
        assertThrows(NullPointerException.class, () -> new ApplicationFailureEvidence(
                CATEGORY, EXCEPTION_CLASS, CORRELATION, null));
    }

    @Test
    void acceptsSanitizedDetailAtExactBoundary() {
        ApplicationFailureEvidence evidence = new ApplicationFailureEvidence(
                CATEGORY, EXCEPTION_CLASS, CORRELATION,
                Optional.of("x".repeat(ApplicationFailureEvidence.MAX_SANITIZED_DETAIL_LENGTH)));
        assertEquals(ApplicationFailureEvidence.MAX_SANITIZED_DETAIL_LENGTH,
                evidence.sanitizedDetail().orElseThrow().length());
    }

    @Test
    void legacyEnvelopeIsExactlyCorrelationCategoryClass() {
        ApplicationFailureEvidence evidence = new ApplicationFailureEvidence(
                CATEGORY, EXCEPTION_CLASS, CORRELATION, Optional.of("safe-detail"));
        assertEquals(CORRELATION + "|" + CATEGORY + "|" + EXCEPTION_CLASS,
                evidence.legacyEnvelope());
        assertEquals(CORRELATION.length() + 1 + CATEGORY.length() + 1 + EXCEPTION_CLASS.length(),
                evidence.legacyEnvelope().length());
        assertFalse(evidence.legacyEnvelope().contains("safe-detail"));
        assertTrue(evidence.legacyEnvelope().length()
                <= ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY);
    }
}
