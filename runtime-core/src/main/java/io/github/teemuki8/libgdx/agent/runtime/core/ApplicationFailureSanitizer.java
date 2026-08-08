package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Optional;

/**
 * Application-owned, optional translation of one callback failure into bounded public detail.
 *
 * <p>Return {@link Optional#empty()} to keep only the stable category, exception class, and
 * deterministic correlation identifier in queryable evidence. A present value is truncated to the
 * configured string bound before it becomes visible. The runtime invokes sanitizers defensively: a
 * throwing sanitizer fails closed, logs locally, and never exposes the raw failure message.
 */
@FunctionalInterface
public interface ApplicationFailureSanitizer {
    /**
     * Translates one failure into optional bounded public detail.
     *
     * @param context the stable category, exception class, and correlation identifier
     * @param failure the application callback failure; never serialized into public evidence
     * @return an empty optional for no detail, or bounded detail to append to the diagnostic
     */
    Optional<String> sanitize(ApplicationFailureContext context, Throwable failure);
}
