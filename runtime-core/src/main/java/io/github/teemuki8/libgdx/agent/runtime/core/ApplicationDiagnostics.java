package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime-owned bounded diagnostic boundary for application callback failures.
 *
 * <p>Assigns deterministic per-category correlation identifiers ({@code failure-1},
 * {@code failure-2}, ...), routes raw throwables to a non-stdout {@link System.Logger} at WARNING,
 * and composes public text with the correlation identifier first so per-feature configured
 * truncation preserves it. Raw application exception messages and stack traces never enter the
 * public text; each caller applies its own configured bound to the complete message.
 */
final class ApplicationDiagnostics {
    private static final System.Logger LOGGER = System.getLogger(
            "io.github.teemuki8.libgdx.agent.runtime.core.ApplicationDiagnostics");

    private final ApplicationFailureSanitizer sanitizer;
    private final int maxDetailLength;
    private final Map<String, Long> correlations = new HashMap<>();

    ApplicationDiagnostics(ApplicationFailureSanitizer sanitizer, int maxDetailLength) {
        this.sanitizer = sanitizer;
        this.maxDetailLength = maxDetailLength;
    }

    /**
     * Reserves the next deterministic correlation identifier for one category.
     *
     * <p>Callers use this when correlation must follow a stable owning order that is not the
     * calling thread of {@link #describe(String, Throwable)} (for example command dispatch order).
     */
    synchronized String nextCorrelationId(String category) {
        long next = correlations.merge(category, 1L, Long::sum);
        return "failure-" + next;
    }

    /**
     * Records one application callback failure locally and returns its public diagnostic.
     *
     * @param category the stable runtime-owned category
     * @param failure the raw application failure; never serialized into the returned text
     * @return public text with correlation identifier, category, exception class, and optional
     *     sanitized detail
     */
    String describe(String category, Throwable failure) {
        return describe(category, failure, nextCorrelationId(category));
    }

    /**
     * Records one application callback failure locally and returns its public diagnostic using a
     * caller-reserved correlation identifier.
     *
     * @param category the stable runtime-owned category
     * @param failure the raw application failure; never serialized into the returned text
     * @param correlationId the deterministic correlation identifier reserved by the caller
     * @return public text with correlation identifier, category, exception class, and optional
     *     sanitized detail
     */
    String describe(String category, Throwable failure, String correlationId) {
        Objects.requireNonNull(failure, "failure");
        ApplicationFailureContext context = new ApplicationFailureContext(
                category, failure.getClass().getName(), correlationId);
        LOGGER.log(System.Logger.Level.WARNING,
                context.category() + " " + context.correlationId(), failure);
        Optional<String> detail = sanitizedDetail(context, failure);
        String text = context.correlationId() + ": " + context.category()
                + ": " + context.exceptionClass();
        if (detail.isPresent()) {
            text = text + ": " + detail.orElseThrow();
        }
        return text;
    }

    private Optional<String> sanitizedDetail(
            ApplicationFailureContext context, Throwable failure) {
        if (sanitizer == null) {
            return Optional.empty();
        }
        try {
            Optional<String> value = sanitizer.sanitize(context, failure);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            String detail = value.orElseThrow();
            return Optional.of(detail.length() <= maxDetailLength
                    ? detail : detail.substring(0, maxDetailLength));
        } catch (RuntimeException | Error sanitizerFailure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "application failure sanitizer failed " + context.correlationId(),
                    sanitizerFailure);
            return Optional.empty();
        }
    }
}
