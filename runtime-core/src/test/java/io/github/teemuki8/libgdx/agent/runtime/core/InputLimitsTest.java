package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class InputLimitsTest {
    @Test
    void rejectsStringTextBelowEnvelopeCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new InputLimits(
                128, 32, 256, 256, 1_000,
                ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY - 1));
    }

    @Test
    void acceptsEnvelopeCapacityAndAbove() {
        assertDoesNotThrow(() -> new InputLimits(
                128, 32, 256, 256, 1_000,
                ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY));
        assertDoesNotThrow(() -> new InputLimits(128, 32, 256, 256, 1_000, 1_024));
    }

    @Test
    void enforcesPerFieldMaxima() {
        assertThrows(IllegalArgumentException.class, () -> new InputLimits(
                1_001, 32, 256, 256, 1_000, 642));
        assertThrows(IllegalArgumentException.class, () -> new InputLimits(
                128, 101, 256, 256, 1_000, 642));
        assertThrows(IllegalArgumentException.class, () -> new InputLimits(
                128, 32, 10_001, 256, 1_000, 642));
        assertThrows(IllegalArgumentException.class, () -> new InputLimits(
                128, 32, 256, 10_001, 1_000, 642));
        assertThrows(IllegalArgumentException.class, () -> new InputLimits(
                128, 32, 256, 256, 1_000_001, 642));
        assertThrows(IllegalArgumentException.class, () -> new InputLimits(
                128, 32, 256, 256, 1_000, 16_385));
    }
}
