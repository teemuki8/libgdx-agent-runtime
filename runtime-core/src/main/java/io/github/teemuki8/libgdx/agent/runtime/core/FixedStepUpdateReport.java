package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable evidence for one application call to the fixed-step accumulator. */
public record FixedStepUpdateReport(long updateSequence, long suppliedRenderDeltaNanos,
        long acceptedRenderDeltaNanos, long clampedRenderTimeNanos,
        long pausedIgnoredTimeNanos, long accumulatorLimitDroppedTimeNanos,
        long catchUpDroppedTimeNanos, long droppedTicks,
        int ticksAttempted, int ticksCompleted, long accumulatorRemainderNanos,
        double interpolationAlpha, Optional<SimulationTickId> firstSimulationTickId,
        Optional<SimulationTickId> finalSimulationTickId, Optional<FrameId> firstRuntimeFrameId,
        Optional<FrameId> finalRuntimeFrameId, boolean paused,
        List<FixedStepUpdateDiagnostic> diagnostics) {
    /** Copies and validates bounded update evidence. */
    public FixedStepUpdateReport {
        if (updateSequence <= 0 || suppliedRenderDeltaNanos < 0
                || acceptedRenderDeltaNanos < 0 || clampedRenderTimeNanos < 0
                || pausedIgnoredTimeNanos < 0 || accumulatorLimitDroppedTimeNanos < 0
                || catchUpDroppedTimeNanos < 0 || droppedTicks < 0
                || ticksAttempted < 0 || ticksCompleted < 0 || ticksCompleted > ticksAttempted
                || accumulatorRemainderNanos < 0 || !Double.isFinite(interpolationAlpha)
                || interpolationAlpha < 0.0 || interpolationAlpha >= 1.0) {
            throw new IllegalArgumentException("invalid fixed-step update evidence");
        }
        long accounted;
        try {
            accounted = Math.addExact(Math.addExact(
                    acceptedRenderDeltaNanos, clampedRenderTimeNanos), pausedIgnoredTimeNanos);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("render-time evidence overflowed", failure);
        }
        if (accounted != suppliedRenderDeltaNanos || paused && acceptedRenderDeltaNanos != 0) {
            throw new IllegalArgumentException("render-time evidence is inconsistent");
        }
        firstSimulationTickId = Objects.requireNonNull(
                firstSimulationTickId, "firstSimulationTickId");
        finalSimulationTickId = Objects.requireNonNull(
                finalSimulationTickId, "finalSimulationTickId");
        firstRuntimeFrameId = Objects.requireNonNull(firstRuntimeFrameId, "firstRuntimeFrameId");
        finalRuntimeFrameId = Objects.requireNonNull(finalRuntimeFrameId, "finalRuntimeFrameId");
        validateRange(firstSimulationTickId, finalSimulationTickId, "simulation tick");
        validateRange(firstRuntimeFrameId, finalRuntimeFrameId, "runtime frame");
        diagnostics = List.copyOf(diagnostics);
        if (diagnostics.size() > FixedStepUpdateDiagnostic.values().length
                || new HashSet<>(diagnostics).size() != diagnostics.size()) {
            throw new IllegalArgumentException("fixed-step diagnostics are invalid");
        }
    }

    private static <T extends Comparable<T>> void validateRange(
            Optional<T> first, Optional<T> last, String name) {
        if (first.isPresent() != last.isPresent()
                || first.isPresent() && first.orElseThrow().compareTo(last.orElseThrow()) > 0) {
            throw new IllegalArgumentException(name + " correlation is inconsistent");
        }
    }
}
