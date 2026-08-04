package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class RecordingRegistryTest {
    @Test
    void recordsOrderedBoundedEvidenceAndReportsRetentionEviction() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("recording"))
                .commandDispatcher(queue::addLast)
                .recordingLimits(new RecordingLimits(1, 8, 16, 16, 1_000_000,
                        64_000, 4, 64))
                .build();
        runtime.actions().register(ActionSpec.builder("mark")
                .requiredString("label")
                .handler(parameters -> {})
                .build());
        runtime.start();

        RecordingSpec first = new RecordingSpec(
                "run-1", "1.12", List.of(new RecordingCapabilityVersion("actions", "1.6")),
                Optional.of("scenario-1"), Optional.empty(), OptionalLong.of(42),
                RuntimeValues.object(RuntimeValues.field(
                        "difficulty", RuntimeValues.enumValue("HARD"))), false);
        runtime.recordings().start(first, "start-1", Duration.ofSeconds(1));
        queue.removeFirst().run();
        assertEquals(CommandState.SUCCEEDED, runtime.recordings()
                .start(first, "start-1", Duration.ofSeconds(1))
                .command().status().orElseThrow().state());

        RuntimeValue.ObjectValue markParameters = RuntimeValues.object(
                RuntimeValues.field("label", RuntimeValues.string("checkpoint")));
        runtime.frame(7, () -> {});
        runtime.actions().invoke("mark", "action-1", markParameters,
                Optional.of("recording-action"), Duration.ofSeconds(1));
        queue.removeFirst().run();
        runtime.actions().invoke("mark", "action-1", markParameters,
                Optional.of("recording-action"), Duration.ofSeconds(1));
        runtime.recordings().stop("run-1", "stop-1", Duration.ofSeconds(1));
        queue.removeFirst().run();

        RecordingChunk firstPage = runtime.recordings().get("run-1", 0, 1);
        assertEquals(RecordingStopReason.REQUESTED, firstPage.metadata().stopReason());
        assertEquals(OptionalLong.of(42), firstPage.metadata().randomSeed());
        assertEquals(RuntimeValues.enumValue("HARD"),
                firstPage.metadata().configuration().fields().getFirst().value());
        assertEquals(1, firstPage.entries().size());
        assertTrue(firstPage.hasMore());
        RecordingChunk remainder = runtime.recordings().get(
                "run-1", firstPage.nextOffset(), 4);
        RecordingActionEntry action = remainder.entries().stream()
                .filter(RecordingActionEntry.class::isInstance)
                .map(RecordingActionEntry.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(markParameters, action.parameters());
        assertTrue(remainder.entries().stream()
                .map(RecordingEntry::order).reduce((left, right) -> {
                    assertTrue(left < right);
                    return right;
                }).isPresent());
        assertFalse(firstPage.metadata().replayGuaranteed());

        RecordingSpec second = new RecordingSpec(
                "run-2", "1.12", List.of(), Optional.empty(), Optional.empty(),
                OptionalLong.empty(), RuntimeValues.object(), false);
        runtime.recordings().start(second, "start-2", Duration.ofSeconds(1));
        queue.removeFirst().run();
        runtime.recordings().stop("run-2", "stop-2", Duration.ofSeconds(1));
        queue.removeFirst().run();
        assertEquals(RuntimeErrorCode.RECORDING_EVICTED,
                assertThrows(AgentRuntimeException.class,
                        () -> runtime.recordings().get("run-1", 0, 1)).code());
    }

    @Test
    void itemLimitStopsRecordingWithExactIncompleteEvidence() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("recording-limit"))
                .commandDispatcher(Runnable::run)
                .recordingLimits(new RecordingLimits(2, 8, 1, 16, 1_000_000,
                        64_000, 4, 64))
                .build();
        runtime.start();
        RecordingSpec spec = new RecordingSpec(
                "limited", "1.12", List.of(), Optional.empty(), Optional.empty(),
                OptionalLong.empty(), RuntimeValues.object(), false);
        runtime.recordings().start(spec, "start", Duration.ofSeconds(1));
        runtime.frame(1, () -> {});
        runtime.frame(1, () -> {});

        RecordingChunk chunk = runtime.recordings().get("limited", 0, 4);
        assertEquals(RecordingStopReason.ITEM_LIMIT, chunk.metadata().stopReason());
        RecordingTruncation truncation = chunk.metadata().truncations().getFirst();
        assertEquals(RecordingTruncationDimension.ITEM_COUNT, truncation.dimension());
        assertEquals(2, truncation.observed());
        assertEquals(1, truncation.retained());
        assertEquals(1, truncation.limit());
        assertTrue(truncation.reproductionEvidenceIncomplete());
    }

    @Test
    void durationTickSpanAndCloseStopsRemainQueryable() {
        AgentRuntime duration = runtimeWithLimits(
                "recording-duration", new RecordingLimits(
                        2, 8, 16, 16, 1, 64_000, 4, 64));
        duration.recordings().start(spec("duration"), "start-duration", Duration.ofSeconds(1));
        duration.frame(2, () -> {});
        RecordingChunk durationChunk = duration.recordings().get("duration", 0, 4);
        assertEquals(RecordingStopReason.DURATION_LIMIT,
                durationChunk.metadata().stopReason());
        assertEquals(RecordingTruncationDimension.DURATION_NANOS,
                durationChunk.metadata().truncations().getFirst().dimension());

        AgentRuntime ticks = runtimeWithLimits(
                "recording-ticks", new RecordingLimits(
                        2, 8, 16, 2, 1_000_000, 64_000, 4, 64));
        ticks.recordings().start(spec("ticks"), "start-ticks", Duration.ofSeconds(1));
        ticks.recordings().recordTick(10, 1, ticks.currentEpoch(), new FrameId(1));
        ticks.recordings().recordTick(12, 1, ticks.currentEpoch(), new FrameId(2));
        RecordingChunk tickChunk = ticks.recordings().get("ticks", 0, 4);
        assertEquals(RecordingStopReason.TICK_SPAN_LIMIT, tickChunk.metadata().stopReason());
        assertEquals(3, tickChunk.metadata().truncations().getFirst().observed());
        assertEquals(1, tickChunk.entries().size());

        AgentRuntime closed = runtimeWithLimits(
                "recording-close", RecordingLimits.developmentDefaults());
        closed.recordings().start(spec("closed"), "start-close", Duration.ofSeconds(1));
        closed.close();
        assertEquals(RecordingStopReason.RUNTIME_CLOSED,
                closed.recordings().get("closed", 0, 4).metadata().stopReason());
    }

    @Test
    void encodedSizeLimitStopsBeforePublishingAnOversizedEntry() {
        AgentRuntime runtime = runtimeWithLimits(
                "recording-size", new RecordingLimits(
                        2, 8, 16, 16, 1_000_000, 180, 4, 64));
        runtime.recordings().start(spec("s"), "start-size", Duration.ofSeconds(1));
        runtime.frame(1, () -> {});

        RecordingChunk chunk = runtime.recordings().get("s", 0, 4);
        assertEquals(RecordingStopReason.ENCODED_SIZE_LIMIT, chunk.metadata().stopReason());
        assertTrue(chunk.entries().isEmpty());
        assertEquals(RecordingTruncationDimension.ENCODED_SIZE,
                chunk.metadata().truncations().getFirst().dimension());
        assertEquals(140, chunk.metadata().encodedBytes());
    }

    @Test
    void manifestCapabilitiesAreCanonicalAndConfigurationIsScalarOnly() {
        RecordingSpec ordered = new RecordingSpec(
                "canonical", "1.12", List.of(
                        new RecordingCapabilityVersion("zeta", "1"),
                        new RecordingCapabilityVersion("alpha", "2")),
                Optional.empty(), Optional.empty(), OptionalLong.empty(),
                RuntimeValues.object(), false);
        assertEquals(List.of("alpha", "zeta"), ordered.capabilityVersions().stream()
                .map(RecordingCapabilityVersion::capabilityId).toList());
        assertThrows(IllegalArgumentException.class, () -> new RecordingSpec(
                "duplicate", "1.12", List.of(
                        new RecordingCapabilityVersion("same", "1"),
                        new RecordingCapabilityVersion("same", "2")),
                Optional.empty(), Optional.empty(), OptionalLong.empty(),
                RuntimeValues.object(), false));

        AgentRuntime runtime = runtimeWithLimits(
                "recording-scalars", RecordingLimits.developmentDefaults());
        RecordingSpec nested = new RecordingSpec(
                "nested", "1.12", List.of(), Optional.empty(), Optional.empty(),
                OptionalLong.empty(), RuntimeValues.object(RuntimeValues.field(
                        "nested", RuntimeValues.list(RuntimeValues.integer(1)))), false);
        assertThrows(IllegalArgumentException.class, () -> runtime.recordings()
                .start(nested, "start-nested", Duration.ofSeconds(1)));
    }

    @Test
    void lifecycleMutationsFailThroughCommandEvidenceOnWrongThreadOrMissingRecording()
            throws InterruptedException {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("recording-lifecycle"))
                .commandDispatcher(queue::addLast)
                .build();
        runtime.start();
        runtime.recordings().start(
                spec("wrong-thread"), "start-wrong-thread", Duration.ofSeconds(1));
        Thread wrongThread = new Thread(queue.removeFirst());
        wrongThread.start();
        wrongThread.join();
        assertEquals(CommandState.FAILED, runtime.recordings().start(
                spec("wrong-thread"), "start-wrong-thread", Duration.ofSeconds(1))
                .command().status().orElseThrow().state());

        runtime.recordings().stop(
                "missing", "stop-missing", Duration.ofSeconds(1));
        queue.removeFirst().run();
        assertEquals(CommandState.FAILED, runtime.recordings().stop(
                "missing", "stop-missing", Duration.ofSeconds(1))
                .command().status().orElseThrow().state());
    }

    @Test
    void retentionRejectionDoesNotStrandAnUnsubmittedRequestId() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("recording-operation-retention"))
                .commandDispatcher(queue::addLast)
                .recordingLimits(new RecordingLimits(
                        2, 1, 16, 16, 1_000_000, 64_000, 4, 64))
                .build();
        runtime.start();
        runtime.recordings().start(spec("retention"), "start-retention", Duration.ofSeconds(1));
        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED,
                assertThrows(AgentRuntimeException.class, () -> runtime.recordings().stop(
                        "retention", "stop-retention", Duration.ofSeconds(1))).code());
        assertEquals(1, queue.size());

        queue.removeFirst().run();
        runtime.recordings().stop(
                "retention", "stop-retention", Duration.ofSeconds(1));
        assertEquals(1, queue.size());
        queue.removeFirst().run();
        assertEquals(CommandState.SUCCEEDED, runtime.recordings().stop(
                "retention", "stop-retention", Duration.ofSeconds(1))
                .command().status().orElseThrow().state());
    }

    @Test
    void concurrentActionSubmissionsRetainDispatcherAdmissionOrder()
            throws InterruptedException {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        CountDownLatch firstActionEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstAction = new CountDownLatch(1);
        AtomicInteger actionSubmissions = new AtomicInteger();
        boolean[] blockActions = {false};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("recording-action-order"))
                .commandDispatcher(command -> {
                    if (blockActions[0] && actionSubmissions.getAndIncrement() == 0) {
                        firstActionEntered.countDown();
                        try {
                            assertTrue(releaseFirstAction.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        }
                    }
                    queue.addLast(command);
                })
                .build();
        runtime.actions().register(
                ActionSpec.builder("ordered-action").handler(parameters -> {}).build());
        runtime.start();
        runtime.recordings().start(
                spec("action-order"), "start-action-order", Duration.ofSeconds(1));
        queue.removeFirst().run();
        blockActions[0] = true;

        Thread first = new Thread(() -> runtime.actions().invoke(
                "ordered-action", "action-first", RuntimeValues.object(),
                Optional.empty(), Duration.ofSeconds(1)));
        Thread second = new Thread(() -> runtime.actions().invoke(
                "ordered-action", "action-second", RuntimeValues.object(),
                Optional.empty(), Duration.ofSeconds(1)));
        first.start();
        assertTrue(firstActionEntered.await(5, TimeUnit.SECONDS));
        second.start();
        releaseFirstAction.countDown();
        first.join();
        second.join();
        queue.removeFirst().run();
        queue.removeFirst().run();
        runtime.recordings().stop(
                "action-order", "stop-action-order", Duration.ofSeconds(1));
        queue.removeFirst().run();

        List<String> requests = runtime.recordings().get("action-order", 0, 4).entries().stream()
                .map(RecordingActionEntry.class::cast)
                .map(entry -> entry.invocation().requestId())
                .toList();
        assertEquals(List.of("action-first", "action-second"), requests);
    }

    @Test
    void boundaryStopKeepsGrowingReconciledOutcomeWithinEncodedLimit() {
        String sessionId = "recording-reconcile";
        RecordingSpec spec = spec("reconcile");
        RecordingMetadata initial = new RecordingMetadata(
                1, "core-1", spec.protocolVersion(), spec.capabilityVersions(), spec.id(),
                SessionId.of(sessionId), new ExecutionEpochId(0), spec.scenarioId(),
                spec.checkpointId(), spec.randomSeed(), spec.configuration(),
                RecordingStopReason.REQUESTED, List.of(), true, false, 0, 0, 0);
        CommandStatus queuedStatus = new CommandStatus(
                "grow-request", CommandState.QUEUED, 1, 1_000_000_001,
                Optional.empty(), Optional.empty(), false, Optional.empty());
        RecordingActionEntry queuedEntry = new RecordingActionEntry(
                0, new ActionInvocation(
                        "grow", "grow-request", CommandLookup.found(queuedStatus),
                        Optional.of(new FrameId(0)), Optional.empty(), Optional.empty()),
                RuntimeValues.object());
        long maximumBytes = RecordingCanonicalSize.manifest(initial, List.of())
                + RecordingCanonicalSize.truncationBytes() * 2
                + RecordingCanonicalSize.entry(queuedEntry);
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of(sessionId))
                .clock(() -> 1)
                .commandDispatcher(queue::addLast)
                .recordingLimits(new RecordingLimits(
                        2, 8, 16, 16, 1, Math.toIntExact(maximumBytes), 4, 64))
                .build();
        runtime.actions().register(
                ActionSpec.builder("grow").handler(parameters -> {}).build());
        runtime.start();
        runtime.recordings().start(spec, "start-reconcile", Duration.ofSeconds(1));
        queue.removeFirst().run();
        runtime.actions().invoke(
                "grow", "grow-request", RuntimeValues.object(),
                Optional.empty(), Duration.ofSeconds(1));
        queue.removeFirst().run();

        runtime.frame(2, () -> {});

        RecordingChunk chunk = runtime.recordings().get("reconcile", 0, 4);
        assertEquals(RecordingStopReason.ENCODED_SIZE_LIMIT, chunk.metadata().stopReason());
        assertEquals(2, chunk.metadata().truncations().size());
        assertEquals(maximumBytes, chunk.metadata().encodedBytes());
        RecordingActionEntry retained = (RecordingActionEntry) chunk.entries().getFirst();
        assertEquals(CommandState.QUEUED,
                retained.invocation().command().status().orElseThrow().state());
    }

    private static AgentRuntime runtimeWithLimits(String sessionId, RecordingLimits limits) {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of(sessionId))
                .commandDispatcher(Runnable::run)
                .recordingLimits(limits)
                .build();
        runtime.start();
        return runtime;
    }

    private static RecordingSpec spec(String id) {
        return new RecordingSpec(id, "1.12", List.of(), Optional.empty(), Optional.empty(),
                OptionalLong.empty(), RuntimeValues.object(), false);
    }
}
