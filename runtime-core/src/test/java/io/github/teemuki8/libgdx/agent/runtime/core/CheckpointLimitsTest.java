package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class CheckpointLimitsTest {
    @Test
    void rejectsDescriptionTextBelowEnvelopeCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new CheckpointLimits(
                32, 256, ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY - 1));
    }

    @Test
    void acceptsEnvelopeCapacityAndAbove() {
        assertDoesNotThrow(() -> new CheckpointLimits(
                32, 256, ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY));
        assertDoesNotThrow(() -> new CheckpointLimits(32, 256, 1_024));
    }

    @Test
    void enforcesPerFieldMaxima() {
        assertThrows(IllegalArgumentException.class, () -> new CheckpointLimits(
                1_001, 256, 642));
        assertThrows(IllegalArgumentException.class, () -> new CheckpointLimits(
                32, 100_001, 642));
        assertThrows(IllegalArgumentException.class, () -> new CheckpointLimits(
                32, 256, 16_385));
    }
}
