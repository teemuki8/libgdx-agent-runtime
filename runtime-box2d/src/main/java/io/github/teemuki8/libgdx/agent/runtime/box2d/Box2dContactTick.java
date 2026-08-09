package io.github.teemuki8.libgdx.agent.runtime.box2d;

import io.github.teemuki8.libgdx.agent.runtime.core.ExecutionEpochId;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTickId;
import io.github.teemuki8.libgdx.agent.runtime.core.Truncation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable bounded Box2D contact evidence finalized for one simulation tick. */
public record Box2dContactTick(SimulationTickId simulationTickId,
        ExecutionEpochId executionEpochId, long epochTick, FrameId runtimeFrameId,
        List<Box2dContactRecord> records, List<ActiveContact> activeContacts,
        long callbackRecordsObserved, int callbackRecordsRetained, int callbackRecordLimit,
        long activeContactsObserved, int activeContactsRetained, int activeContactLimit,
        long unmappedContactsObserved, List<Diagnostic> diagnostics,
        List<Truncation> truncations, boolean complete) {
    /** Validates counters, stable order, completeness, and defensive copies. */
    public Box2dContactTick {
        Objects.requireNonNull(simulationTickId, "simulationTickId");
        Objects.requireNonNull(executionEpochId, "executionEpochId");
        Objects.requireNonNull(runtimeFrameId, "runtimeFrameId");
        records = List.copyOf(records);
        activeContacts = List.copyOf(activeContacts);
        diagnostics = List.copyOf(diagnostics);
        truncations = List.copyOf(truncations);
        if (epochTick <= 0 || callbackRecordsObserved < 0 || callbackRecordsRetained < 0
                || callbackRecordLimit <= 0 || callbackRecordsRetained != records.size()
                || callbackRecordsRetained > callbackRecordsObserved
                || callbackRecordsRetained > callbackRecordLimit || activeContactsObserved < 0
                || activeContactsRetained < 0 || activeContactLimit <= 0
                || activeContactsRetained != activeContacts.size()
                || activeContactsRetained > activeContactsObserved
                || activeContactsRetained > activeContactLimit || unmappedContactsObserved < 0) {
            throw new IllegalArgumentException("contact tick counters are inconsistent");
        }
        requireSorted(records);
        requireActiveSorted(activeContacts);
        requireDiagnosticsSorted(diagnostics);
        requireTruncationsSorted(truncations);
        boolean recordsTruncated = callbackRecordsObserved > callbackRecordsRetained;
        boolean activeTruncated = activeContactsObserved > activeContactsRetained;
        if (recordsTruncated != hasTruncation(truncations, "box2d.contact.records",
                        callbackRecordsObserved, callbackRecordsRetained, callbackRecordLimit)
                || activeTruncated != hasTruncation(truncations, "box2d.contact.active",
                        activeContactsObserved, activeContactsRetained, activeContactLimit)
                || unmappedContactsObserved > 0
                        && diagnostics.stream().noneMatch(value ->
                                value.code() == DiagnosticCode.UNMAPPED_ENDPOINT)
                || complete && (recordsTruncated || activeTruncated
                        || unmappedContactsObserved > 0 || !diagnostics.isEmpty()
                        || !truncations.isEmpty())) {
            throw new IllegalArgumentException("contact tick completeness is inconsistent");
        }
    }

    /** Latest bounded facts retained for one active contact. */
    public record ActiveContact(Box2dContactRecord.Key key, boolean touching, boolean enabled,
            List<Box2dVector> points, Optional<Box2dVector> normal,
            List<Box2dContactRecord.Impulse> impulses, List<Truncation> truncations)
            implements Comparable<ActiveContact> {
        /** Defensively copies active-contact values. */
        public ActiveContact {
            Objects.requireNonNull(key, "key");
            points = List.copyOf(points);
            normal = Objects.requireNonNull(normal, "normal");
            impulses = List.copyOf(impulses);
            truncations = List.copyOf(truncations);
        }

        @Override public int compareTo(ActiveContact other) {
            return key.compareTo(other.key);
        }
    }

    /** Closed agent-facing contact diagnostic code. */
    public enum DiagnosticCode {
        /** A callback endpoint was not explicitly registered. */ UNMAPPED_ENDPOINT,
        /** A callback arrived outside a captured simulation tick. */ CALLBACK_OUTSIDE_TICK,
        /** A callback arrived after the contact adapter closed. */ CALLBACK_AFTER_CLOSE,
        /** Callback evidence exceeded its record limit. */ RECORD_LIMIT_REACHED,
        /** The active set exceeded its retention limit. */ ACTIVE_LIMIT_REACHED,
        /** Contact points exceeded their limit. */ POINT_LIMIT_REACHED,
        /** Contact impulses exceeded their limit. */ IMPULSE_LIMIT_REACHED,
        /** Old-manifold points exceeded their limit. */ OLD_MANIFOLD_LIMIT_REACHED,
        /** A registered endpoint changed while evidence was active. */ ENDPOINT_CHANGED,
        /** No active simulation tick/frame correlation was available. */ MISSING_CORRELATION,
        /** The application listener threw and the failure was rethrown. */
        APPLICATION_LISTENER_FAILED,
        /** A value is unavailable in this callback phase. */ PHASE_VALUE_UNAVAILABLE,
        /** The application-owned world step failed. */ STEP_FAILED,
        /** A new execution epoch cleared active contacts. */ EPOCH_RESET,
        /** A replacement world cleared active contacts. */ WORLD_REBOUND
    }

    /** Saturating count for one diagnostic code. */
    public record Diagnostic(DiagnosticCode code, long observed) implements Comparable<Diagnostic> {
        /** Requires a positive occurrence count. */
        public Diagnostic {
            Objects.requireNonNull(code, "code");
            if (observed <= 0) {
                throw new IllegalArgumentException("diagnostic count must be positive");
            }
        }

        @Override public int compareTo(Diagnostic other) {
            return code.compareTo(other.code);
        }
    }

    private static <T extends Comparable<? super T>> void requireSorted(List<T> values) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).compareTo(values.get(index)) >= 0) {
                throw new IllegalArgumentException("contact evidence is not strictly sorted");
            }
        }
    }

    private static void requireActiveSorted(List<ActiveContact> values) {
        requireSorted(values);
    }

    private static void requireDiagnosticsSorted(List<Diagnostic> values) {
        requireSorted(values);
    }

    private static void requireTruncationsSorted(List<Truncation> values) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).dimension().compareTo(values.get(index).dimension()) >= 0) {
                throw new IllegalArgumentException("contact truncations are not strictly sorted");
            }
        }
    }

    private static boolean hasTruncation(List<Truncation> values, String dimension,
            long observed, long retained, long limit) {
        return values.stream().anyMatch(value -> value.dimension().equals(dimension)
                && value.observed() == observed && value.retained() == retained
                && value.limit() == limit);
    }
}
