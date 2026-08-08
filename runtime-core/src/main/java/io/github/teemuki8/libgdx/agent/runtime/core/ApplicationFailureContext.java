package io.github.teemuki8.libgdx.agent.runtime.core;

/**
 * Immutable, validated context for one application callback failure diagnostic.
 *
 * <p>Application sanitizers receive this context instead of raw exception messages. The category
 * is a stable runtime-owned label, the exception class names the thrown type, and the correlation
 * identifier is a deterministic per-runtime ordinal ({@code failure-1}, {@code failure-2}, ...).
 */
public record ApplicationFailureContext(
        String category, String exceptionClass, String correlationId) {
    /** Validates that every context field is a bounded non-blank identifier. */
    public ApplicationFailureContext {
        IdentifierSupport.validate(category, "category");
        IdentifierSupport.validate(exceptionClass, "exceptionClass");
        IdentifierSupport.validate(correlationId, "correlationId");
    }
}
