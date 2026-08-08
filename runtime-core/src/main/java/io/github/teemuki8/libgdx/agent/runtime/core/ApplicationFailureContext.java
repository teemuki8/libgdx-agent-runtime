package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Optional;

/**
 * Immutable, validated context for one application callback failure diagnostic.
 *
 * <p>Application sanitizers receive this context instead of raw exception messages. The category
 * is a stable runtime-owned label, the exception class names the thrown type, and the correlation
 * identifier is a deterministic per-session ordinal ({@code sessionId|failure-N}). Every
 * component enforces the exact {@link ApplicationFailureEvidence} bounds, including deterministic
 * exception-class truncation, so a configured sanitizer always runs for valid evidence.
 */
public record ApplicationFailureContext(
        String category, String exceptionClass, String correlationId) {
    /** Validates every component against the exact structured-evidence bounds. */
    public ApplicationFailureContext {
        ApplicationFailureEvidence normalized = new ApplicationFailureEvidence(
                category, exceptionClass, correlationId, Optional.empty());
        category = normalized.category();
        exceptionClass = normalized.exceptionClass();
        correlationId = normalized.correlationId();
    }
}
