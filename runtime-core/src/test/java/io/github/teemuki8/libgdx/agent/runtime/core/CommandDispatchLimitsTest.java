package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class CommandDispatchLimitsTest {
    @Test
    void rejectsDiagnosticTextBelowEnvelopeCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new CommandDispatchLimits(
                256, 1_000, 1_000, Duration.ofSeconds(30).toNanos(),
                ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY - 1));
    }

    @Test
    void acceptsEnvelopeCapacityAndAbove() {
        assertDoesNotThrow(() -> new CommandDispatchLimits(
                256, 1_000, 1_000, Duration.ofSeconds(30).toNanos(),
                ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY));
        assertDoesNotThrow(() -> new CommandDispatchLimits(
                256, 1_000, 1_000, Duration.ofSeconds(30).toNanos(), 1_024));
    }

    @Test
    void enforcesPerFieldMaxima() {
        assertThrows(IllegalArgumentException.class, () -> new CommandDispatchLimits(
                100_001, 1_000, 1_000, Duration.ofSeconds(30).toNanos(), 1_024));
        assertThrows(IllegalArgumentException.class, () -> new CommandDispatchLimits(
                256, 1_000, 1_000, Duration.ofSeconds(30).toNanos(),
                CommandDispatchLimits.MAX_DIAGNOSTIC_LENGTH + 1));
        assertThrows(IllegalArgumentException.class, () -> new CommandDispatchLimits(
                256, 1_000, 1_000, Duration.ofHours(24).plusSeconds(1).toNanos(), 1_024));
    }
}
