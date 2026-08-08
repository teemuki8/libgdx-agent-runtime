package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class RuntimeLimitsTest {
    @Test
    void rejectsDiagnosticTextBelowEnvelopeCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeLimits(
                240, 2_000, 5_000, 128, 256, 256, 64,
                ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY - 1, 256, 16, 1_000));
    }

    @Test
    void acceptsEnvelopeCapacityAndAbove() {
        assertDoesNotThrow(() -> new RuntimeLimits(
                240, 2_000, 5_000, 128, 256, 256, 64,
                ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY, 256, 16, 1_000));
        assertDoesNotThrow(() -> new RuntimeLimits(
                240, 2_000, 5_000, 128, 256, 256, 64, 4_096, 256, 16, 1_000));
    }

    @Test
    void enforcesPositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeLimits(
                0, 2_000, 5_000, 128, 256, 256, 64, 642, 256, 16, 1_000));
    }
}
