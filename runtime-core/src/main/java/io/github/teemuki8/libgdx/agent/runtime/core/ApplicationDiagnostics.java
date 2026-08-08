package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/**
 * Runtime-owned structured diagnostic boundary for application callback failures.
 *
 * <p>Assigns deterministic session-scoped correlation identifiers
 * ({@code sessionId.value() + "|failure-" + N}) in capture or command admission order, routes raw
 * throwables to a non-stdout {@link System.Logger} at WARNING, and produces the structured
 * {@link ApplicationFailureEvidence} plus its {@link ApplicationFailureEvidence#legacyEnvelope()}
 * view. Raw application exception messages and stack traces never enter public evidence.
 */
final class ApplicationDiagnostics {
    private static final System.Logger LOGGER = System.getLogger(
            "io.github.teemuki8.libgdx.agent.runtime.core.ApplicationDiagnostics");

    private final ApplicationFailureSanitizer sanitizer;
    private final String sessionId;
    private long nextCorrelation;

    ApplicationDiagnostics(ApplicationFailureSanitizer sanitizer, String sessionId) {
        this.sanitizer = sanitizer;
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    /**
     * Reserves the next deterministic correlation identifier in admission order.
     *
     * <p>Callers use this when correlation must follow a stable owning order that is not the
     * calling thread of {@link #describe(String, Throwable)} (for example command dispatch order).
     * The counter saturates fail-closed before overflow or before a composed identifier could
     * exceed the public bound.
     */
    synchronized String nextCorrelationId() {
        if (nextCorrelation == Long.MAX_VALUE) {
            throw new IllegalStateException("application failure correlation space is exhausted");
        }
        nextCorrelation++;
        String correlationId = sessionId + "|failure-" + nextCorrelation;
        if (correlationId.length() > ApplicationFailureEvidence.MAX_CORRELATION_ID_LENGTH) {
            throw new IllegalStateException("correlation id exceeds the public bound");
        }
        return correlationId;
    }

    /**
     * Records one application callback failure locally and returns its structured public evidence.
     *
     * @param category the stable runtime-owned category
     * @param failure the raw application failure; never serialized into public evidence
     * @return structured evidence with category, exception class, session-scoped correlation
     *     identifier, and optional sanitized detail
     */
    ApplicationFailureEvidence describe(String category, Throwable failure) {
        return describe(category, failure, nextCorrelationId());
    }

    /**
     * Records one application callback failure locally and returns its structured public evidence
     * using a caller-reserved correlation identifier.
     *
     * @param category the stable runtime-owned category
     * @param failure the raw application failure; never serialized into public evidence
     * @param correlationId the deterministic correlation identifier reserved by the caller
     * @return structured evidence with category, exception class, session-scoped correlation
     *     identifier, and optional sanitized detail
     */
    ApplicationFailureEvidence describe(String category, Throwable failure, String correlationId) {
        Objects.requireNonNull(failure, "failure");
        LOGGER.log(System.Logger.Level.WARNING, category + " " + correlationId, failure);
        Optional<String> detail = sanitizedDetail(category, correlationId, failure);
        return new ApplicationFailureEvidence(
                category, failure.getClass().getName(), correlationId, detail);
    }

    private Optional<String> sanitizedDetail(
            String category, String correlationId, Throwable failure) {
        if (sanitizer == null) {
            return Optional.empty();
        }
        try {
            ApplicationFailureContext context = new ApplicationFailureContext(
                    category, failure.getClass().getName(), correlationId);
            Optional<String> value = sanitizer.sanitize(context, failure);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            String detail = value.orElseThrow();
            return Optional.of(detail.length() <= ApplicationFailureEvidence
                    .MAX_SANITIZED_DETAIL_LENGTH
                    ? detail : detail.substring(0,
                            ApplicationFailureEvidence.MAX_SANITIZED_DETAIL_LENGTH));
        } catch (RuntimeException | Error sanitizerFailure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "application failure sanitizer failed " + correlationId, sanitizerFailure);
            return Optional.empty();
        }
    }
}
