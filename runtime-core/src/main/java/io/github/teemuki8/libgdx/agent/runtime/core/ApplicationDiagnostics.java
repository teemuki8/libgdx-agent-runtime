package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime-owned bounded diagnostic boundary for application callback failures.
 *
 * <p>Assigns deterministic correlation identifiers {@code failure-1}, {@code failure-2}, ...,
 * routes raw throwables to a non-stdout {@link System.Logger} at WARNING, and composes bounded
 * public text containing the stable category, exception class, correlation identifier, and
 * optional sanitized detail. Raw application exception messages and stack traces never enter the
 * public text.
 */
final class ApplicationDiagnostics {
    private static final int MAX_TEXT_LENGTH = 1024;
    private static final System.Logger LOGGER = System.getLogger(
            "io.github.teemuki8.libgdx.agent.runtime.core.ApplicationDiagnostics");

    private final ApplicationFailureSanitizer sanitizer;
    private final int maxDetailLength;
    private final AtomicLong nextCorrelation = new AtomicLong();

    ApplicationDiagnostics(ApplicationFailureSanitizer sanitizer, int maxDetailLength) {
        this.sanitizer = sanitizer;
        this.maxDetailLength = maxDetailLength;
    }

    /**
     * Records one application callback failure locally and returns its bounded public diagnostic.
     *
     * @param category the stable runtime-owned category
     * @param failure the raw application failure; never serialized into the returned text
     * @return bounded text with category, exception class, correlation identifier, and optional
     *     sanitized detail
     */
    String describe(String category, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        ApplicationFailureContext context = new ApplicationFailureContext(
                category, failure.getClass().getName(),
                "failure-" + nextCorrelation.incrementAndGet());
        LOGGER.log(System.Logger.Level.WARNING,
                context.category() + " " + context.correlationId(), failure);
        Optional<String> detail = sanitizedDetail(context, failure);
        String text = context.category() + ": " + context.exceptionClass()
                + " (" + context.correlationId() + ")";
        if (detail.isPresent()) {
            text = text + ": " + detail.orElseThrow();
        }
        return text.length() <= MAX_TEXT_LENGTH
                ? text : text.substring(0, MAX_TEXT_LENGTH);
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
