package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bounded application-dispatched lifecycle and immutable evidence store for scenario recordings. */
public final class RecordingRegistry {
    private static final int SCHEMA_VERSION = 1;
    private static final String RUNTIME_VERSION = "core-1";

    private static final int MAXIMUM_TRUNCATIONS = 2;
    private final AgentRuntime runtime;
    private final RecordingLimits limits;
    private final LinkedHashMap<String, RetainedRecording> recordings = new LinkedHashMap<>();
    private final LinkedHashMap<String, OperationEvidence> operations = new LinkedHashMap<>();
    private final ArrayDeque<String> evictedIds = new ArrayDeque<>();
    private MutableRecording active;

    RecordingRegistry(AgentRuntime runtime, RecordingLimits limits) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Starts or polls one at-most-once recording start operation. */
    public RecordingOperation start(RecordingSpec spec, String requestId, Duration timeout) {
        Objects.requireNonNull(spec, "spec");
        requireConfiguredLengths(spec);
        return submit(RecordingOperation.Kind.START, spec.id(), requestId, timeout,
                Optional.of(spec), () -> startNow(spec));
    }

    /** Stops or polls one at-most-once recording stop operation. */
    public RecordingOperation stop(String recordingId, String requestId, Duration timeout) {
        requireString(recordingId, "recording id");
        return submit(RecordingOperation.Kind.STOP, recordingId, requestId, timeout,
                Optional.empty(), () -> {
                    runtime.requireRecordingMutation();
                    stopNow(recordingId, RecordingStopReason.REQUESTED, null);
                });
    }

    public synchronized RecordingChunk get(String recordingId, int offset, int limit) {
        requireString(recordingId, "recording id");
        if (offset < 0) {
            throw new IllegalArgumentException("recording offset must be non-negative");
        }
        if (limit <= 0 || limit > limits.chunkItems()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "recording chunk limit is outside range");
        }
        RetainedRecording retained = recordings.get(recordingId);
        if (retained == null) {
            if (active != null && active.spec.id().equals(recordingId)) {
                throw new AgentRuntimeException(
                        RuntimeErrorCode.INVALID_LIFECYCLE, "recording is still active");
            }
            if (evictedIds.contains(recordingId)) {
                throw new AgentRuntimeException(
                        RuntimeErrorCode.RECORDING_EVICTED, "recording evidence was evicted");
            }
            throw new AgentRuntimeException(
                    RuntimeErrorCode.INVALID_QUERY, "recording does not exist");
        }
        if (offset > retained.entries.size()) {
            throw new IllegalArgumentException("recording offset exceeds retained items");
        }
        int next = Math.min(retained.entries.size(), offset + limit);
        return new RecordingChunk(retained.metadata,
                retained.entries.subList(offset, next), offset, next, next < retained.entries.size());
    }

    /** Returns configured effective recording limits. */
    public RecordingLimits limits() {
        return limits;
    }

    synchronized void recordAction(ActionInvocation invocation,
            RuntimeValue.ObjectValue parameters) {
        if (active == null) {
            return;
        }
        String key = "action:" + invocation.requestId();
        RecordingEntry prior = active.entries.get(key);
        long order = prior == null ? active.nextOrder : prior.order();
        put(key, new RecordingActionEntry(order, invocation, parameters));
        if (active != null) {
            active.actionRequestIds.add(invocation.requestId());
        }
    }

    synchronized void recordInput(InputInjection injection) {
        if (active == null) {
            return;
        }
        String key = "input:" + injection.requestId();
        RecordingEntry prior = active.entries.get(key);
        long order = prior == null ? active.nextOrder : prior.order();
        put(key, new RecordingInputEntry(order, injection));
        if (active != null) {
            active.inputRequestIds.add(injection.requestId());
        }
    }

    synchronized void recordFrame(FrameSnapshot frame) {
        if (active == null) {
            return;
        }
        long observedDuration = saturatedAdd(active.durationNanos, frame.deltaNanos());
        if (observedDuration > limits.maximumDurationNanos()) {
            stopNow(active.spec.id(), RecordingStopReason.DURATION_LIMIT,
                    new RecordingTruncation(RecordingTruncationDimension.DURATION_NANOS,
                            observedDuration, active.durationNanos,
                            limits.maximumDurationNanos(), true));
            return;
        }
        active.durationNanos = observedDuration;
        put("frame:" + frame.executionEpochId().value() + ':' + frame.frameId().value(),
                new RecordingFrameEntry(active.nextOrder, frame.executionEpochId(), frame.frameId(),
                        frame.deltaNanos(), frame.baselineKind(),
                        frame.stats().diagnostics().size(), frame.stats().truncations().size()));
    }

    synchronized void recordTick(long tick, long deltaNanos,
            ExecutionEpochId executionEpochId, FrameId resultingFrameId) {
        if (active == null) {
            return;
        }
        long first = active.firstTick.orElse(tick);
        long observedSpan = Math.addExact(Math.subtractExact(tick, first), 1);
        if (observedSpan > limits.maximumTickSpan()) {
            stopNow(active.spec.id(), RecordingStopReason.TICK_SPAN_LIMIT,
                    new RecordingTruncation(RecordingTruncationDimension.TICK_SPAN,
                            observedSpan, active.retainedTickSpan,
                            limits.maximumTickSpan(), true));
            return;
        }
        active.firstTick = Optional.of(first);
        active.retainedTickSpan = observedSpan;
        put("tick:" + tick, new RecordingTickEntry(active.nextOrder, tick, deltaNanos,
                executionEpochId, resultingFrameId));
    }

    synchronized void close() {
        if (active != null) {
            stopNow(active.spec.id(), RecordingStopReason.RUNTIME_CLOSED, null);
        }
        operations.clear();
    }

    /** Package-private close observation: number of retained pending recording operations. */
    synchronized int retainedOperations() {
        return operations.size();
    }

    private RecordingOperation submit(RecordingOperation.Kind kind, String recordingId,
            String requestId, Duration timeout, Optional<RecordingSpec> spec, Runnable callback) {
        runtime.requireSubmissionsOpen();
        requireString(requestId, "recording request id");
        CommandDispatch dispatch = runtime.commands().orElseThrow(() ->
                new IllegalStateException("recordings require application command dispatch"));
        requireValidTimeout(timeout, dispatch.limits().maximumTimeoutNanos());
        Signature signature = new Signature(kind, recordingId, spec);
        synchronized (this) {
            runtime.requireSubmissionsOpen();
            OperationEvidence existing = operations.get(requestId);
            if (existing != null) {
                if (!existing.signature.equals(signature)) {
                    throw new IllegalArgumentException(
                            "recording request id is bound to a different operation");
                }
                return operation(kind, recordingId, requestId, dispatch.status(requestId));
            }
            if (dispatch.status(requestId).kind() != CommandLookup.Kind.UNKNOWN) {
                throw new IllegalArgumentException(
                        "recording request correlation evidence is no longer retained");
            }
            makeOperationRoom();
            operations.put(requestId, new OperationEvidence(signature));
        }
        CommandLookup lookup = dispatch.submit(requestId, timeout, callback);
        return operation(kind, recordingId, requestId, lookup);
    }

    private synchronized RecordingOperation operation(RecordingOperation.Kind kind,
            String recordingId, String requestId, CommandLookup command) {
        RetainedRecording retained = recordings.get(recordingId);
        Optional<RecordingStopReason> reason = retained == null
                ? Optional.empty() : Optional.of(retained.metadata.stopReason());
        return new RecordingOperation(kind, recordingId, requestId, command, reason);
    }

    private synchronized void startNow(RecordingSpec spec) {
        runtime.requireRecordingMutation();
        if (active != null) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.INVALID_LIFECYCLE, "a recording is already active");
        }
        if (recordings.containsKey(spec.id()) || evictedIds.contains(spec.id())) {
            throw new IllegalArgumentException("recording id was already used");
        }
        MutableRecording candidate = new MutableRecording(spec, runtime.currentEpoch(), 0);
        RecordingMetadata initial = metadata(
                candidate, RecordingStopReason.REQUESTED, 0, 0);
        long baseBytes = saturatedAdd(
                RecordingCanonicalSize.manifest(initial, List.of()),
                RecordingCanonicalSize.truncationBytes() * MAXIMUM_TRUNCATIONS);
        if (baseBytes > limits.maximumEncodedBytes()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "recording metadata exceeds encoded size limit");
        }
        active = new MutableRecording(spec, runtime.currentEpoch(), baseBytes);
    }

    private synchronized void stopNow(String recordingId, RecordingStopReason reason,
            RecordingTruncation truncation) {
        if (active == null) {
            if (recordings.containsKey(recordingId)) {
                return;
            }
            throw new AgentRuntimeException(
                    RuntimeErrorCode.INVALID_LIFECYCLE, "no recording is active");
        }
        if (!active.spec.id().equals(recordingId)) {
            throw new IllegalArgumentException("another recording is active");
        }
        MutableRecording stopping = active;
        if (truncation != null) {
            stopping.truncations.add(truncation);
        }
        stopping.reconciling = true;
        reconcileRequests(stopping);
        stopping.reconciling = false;
        RecordingStopReason effectiveReason = stopping.forcedStopReason.orElse(reason);
        List<RecordingEntry> entries = List.copyOf(stopping.entries.values());
        RecordingMetadata preliminary = metadata(
                stopping, effectiveReason, entries.size(), 0);
        long encodedBytes = RecordingCanonicalSize.manifest(preliminary, entries);
        RecordingMetadata metadata = metadata(
                stopping, effectiveReason, entries.size(), encodedBytes);
        recordings.put(stopping.spec.id(), new RetainedRecording(metadata, entries));
        active = null;
        while (recordings.size() > limits.retainedRecordings()) {
            String evicted = recordings.keySet().iterator().next();
            recordings.remove(evicted);
            evictedIds.addLast(evicted);
            while (evictedIds.size() > limits.retainedRecordings()) {
                evictedIds.removeFirst();
            }
        }
    }

    private void reconcileRequests(MutableRecording recording) {
        for (String requestId : recording.actionRequestIds) {
            runtime.actions().recording(requestId).ifPresent(invocation -> {
                RecordingActionEntry prior =
                        (RecordingActionEntry) recording.entries.get("action:" + requestId);
                replace(recording, "action:" + requestId,
                        new RecordingActionEntry(prior.order(), invocation, prior.parameters()));
            });
        }
        for (String requestId : recording.inputRequestIds) {
            runtime.inputs().recording(requestId).ifPresent(injection ->
                    replace(recording, "input:" + requestId,
                            new RecordingInputEntry(
                                    recording.entries.get("input:" + requestId).order(), injection)));
        }
    }

    private synchronized void put(String key, RecordingEntry entry) {
        if (active == null) {
            return;
        }
        RecordingEntry prior = active.entries.get(key);
        if (prior != null) {
            replace(active, key, entry);
            return;
        }
        long observedItems = Math.addExact(active.observedItems, 1);
        if (observedItems > limits.itemsPerRecording()) {
            stopNow(active.spec.id(), RecordingStopReason.ITEM_LIMIT,
                    new RecordingTruncation(RecordingTruncationDimension.ITEM_COUNT,
                            observedItems, active.entries.size(), limits.itemsPerRecording(), true));
            return;
        }
        long itemBytes = RecordingCanonicalSize.entry(entry);
        long observedBytes = saturatedAdd(active.encodedBytes, itemBytes);
        if (observedBytes > limits.maximumEncodedBytes()) {
            stopNow(active.spec.id(), RecordingStopReason.ENCODED_SIZE_LIMIT,
                    new RecordingTruncation(RecordingTruncationDimension.ENCODED_SIZE,
                            observedBytes, active.encodedBytes,
                            limits.maximumEncodedBytes(), true));
            return;
        }
        active.entries.put(key, entry);
        active.observedItems = observedItems;
        active.encodedBytes = observedBytes;
        active.nextOrder++;
    }

    private void replace(MutableRecording recording, String key, RecordingEntry replacement) {
        RecordingEntry prior = recording.entries.get(key);
        long adjusted = recording.encodedBytes - RecordingCanonicalSize.entry(prior)
                + RecordingCanonicalSize.entry(replacement);
        if (adjusted > limits.maximumEncodedBytes()) {
            if (recording.forcedStopReason.isEmpty()) {
                recording.forcedStopReason = Optional.of(RecordingStopReason.ENCODED_SIZE_LIMIT);
                recording.truncations.add(new RecordingTruncation(
                        RecordingTruncationDimension.ENCODED_SIZE,
                        adjusted, recording.encodedBytes,
                        limits.maximumEncodedBytes(), true));
            }
            if (!recording.reconciling) {
                stopNow(recording.spec.id(), RecordingStopReason.ENCODED_SIZE_LIMIT, null);
            }
            return;
        }
        recording.entries.put(key, replacement);
        recording.encodedBytes = adjusted;
    }

    private RecordingMetadata metadata(MutableRecording recording, RecordingStopReason reason,
            long retainedItems, long encodedBytes) {
        return new RecordingMetadata(
                SCHEMA_VERSION, RUNTIME_VERSION, recording.spec.protocolVersion(),
                recording.spec.capabilityVersions(), recording.spec.id(), runtime.sessionId(),
                recording.startedEpoch, recording.spec.scenarioId(), recording.spec.checkpointId(),
                recording.spec.randomSeed(), recording.spec.configuration(), reason,
                recording.truncations, recording.truncations.isEmpty(),
                recording.spec.replayGuaranteed(), recording.observedItems,
                retainedItems, encodedBytes);
    }

    private void requireConfiguredLengths(RecordingSpec spec) {
        requireString(spec.id(), "recording id");
        requireString(spec.protocolVersion(), "recording protocol version");
        spec.scenarioId().ifPresent(value -> requireString(value, "recording scenario id"));
        spec.checkpointId().ifPresent(value -> requireString(value, "recording checkpoint id"));
        if (spec.capabilityVersions().size() > limits.itemsPerRecording()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED,
                    "recording capability versions exceed item limit");
        }
        spec.capabilityVersions().forEach(value -> {
            requireString(value.capabilityId(), "recording capability id");
            requireString(value.version(), "recording capability version");
        });
        if (spec.configuration().fields().size() > limits.itemsPerRecording()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "recording configuration exceeds item limit");
        }
        spec.configuration().fields().forEach(field -> {
            requireString(field.name(), "recording configuration name");
            validateConfigurationValue(field.value());
        });
    }

    private void validateConfigurationValue(RuntimeValue value) {
        switch (value) {
            case RuntimeValue.StringValue text ->
                    requireString(text.value(), "recording configuration string");
            case RuntimeValue.EnumValue enumValue ->
                    requireString(enumValue.value(), "recording configuration enum");
            case RuntimeValue.BooleanValue ignored -> { }
            case RuntimeValue.IntegerValue ignored -> { }
            case RuntimeValue.DecimalValue ignored -> { }
            default -> throw new IllegalArgumentException(
                    "recording configuration values must be closed scalars");
        }
    }

    private void requireString(String value, String name) {
        IdentifierSupport.validate(value, name);
        if (value.length() > limits.stringLength()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, name + " exceeds configured length");
        }
    }

    private synchronized void makeOperationRoom() {
        if (operations.size() < limits.retainedOperations()) {
            return;
        }
        String oldest = operations.keySet().iterator().next();
        CommandLookup lookup = runtime.commands().orElseThrow().status(oldest);
        if (lookup.kind() != CommandLookup.Kind.FOUND
                || lookup.status().orElseThrow().state() == CommandState.QUEUED
                || lookup.status().orElseThrow().state() == CommandState.EXECUTING) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "recording operation retention is full");
        }
        operations.remove(oldest);
    }

    private static void requireValidTimeout(Duration timeout, long maximumNanos) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        final long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("timeout exceeds the supported range", failure);
        }
        if (nanos > maximumNanos) {
            throw new IllegalArgumentException("timeout exceeds the configured limit");
        }
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException failure) {
            return Long.MAX_VALUE;
        }
    }

    private record Signature(RecordingOperation.Kind kind, String recordingId,
            Optional<RecordingSpec> spec) {}

    private record OperationEvidence(Signature signature) {}

    private record RetainedRecording(RecordingMetadata metadata, List<RecordingEntry> entries) {
        private RetainedRecording {
            entries = List.copyOf(entries);
        }
    }

    private static final class MutableRecording {
        private final RecordingSpec spec;
        private final ExecutionEpochId startedEpoch;
        private final LinkedHashMap<String, RecordingEntry> entries = new LinkedHashMap<>();
        private final LinkedHashSet<String> actionRequestIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> inputRequestIds = new LinkedHashSet<>();
        private final ArrayList<RecordingTruncation> truncations = new ArrayList<>();
        private long nextOrder;
        private long observedItems;
        private long encodedBytes;
        private long durationNanos;
        private Optional<Long> firstTick = Optional.empty();
        private long retainedTickSpan;
        private Optional<RecordingStopReason> forcedStopReason = Optional.empty();
        private boolean reconciling;

        private MutableRecording(RecordingSpec spec, ExecutionEpochId startedEpoch,
                long encodedBytes) {
            this.spec = spec;
            this.startedEpoch = startedEpoch;
            this.encodedBytes = encodedBytes;
        }
    }
}
