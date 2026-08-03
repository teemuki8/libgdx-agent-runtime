package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Optional application testimony attached explicitly to a runtime fact. */
public record FactMetadata(
        Optional<String> sourceSubsystem,
        Optional<String> sourceLocation,
        Optional<String> correlationId) {
    public static final int MAX_SOURCE_LOCATION_LENGTH = 512;
    private static final FactMetadata EMPTY = new FactMetadata(
            Optional.empty(), Optional.empty(), Optional.empty());

    public FactMetadata {
        sourceSubsystem = Objects.requireNonNull(sourceSubsystem, "sourceSubsystem");
        sourceLocation = Objects.requireNonNull(sourceLocation, "sourceLocation");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        sourceSubsystem.ifPresent(value ->
                IdentifierSupport.validate(value, "source subsystem"));
        correlationId.ifPresent(value -> IdentifierSupport.validate(value, "correlation id"));
        sourceLocation.ifPresent(value -> {
            if (value.isBlank() || value.length() > MAX_SOURCE_LOCATION_LENGTH) {
                throw new IllegalArgumentException(
                        "source location must be non-blank and at most "
                                + MAX_SOURCE_LOCATION_LENGTH + " characters");
            }
        });
    }

    public static FactMetadata empty() {
        return EMPTY;
    }

    public FactMetadata withSourceSubsystem(String value) {
        return new FactMetadata(Optional.of(value), sourceLocation, correlationId);
    }

    public FactMetadata withSourceLocation(String value) {
        return new FactMetadata(sourceSubsystem, Optional.of(value), correlationId);
    }

    public FactMetadata withCorrelationId(String value) {
        return new FactMetadata(sourceSubsystem, sourceLocation, Optional.of(value));
    }
}
