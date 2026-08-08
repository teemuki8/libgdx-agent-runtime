package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class CheckpointRegistryTest {
    @Test
    void createsRestoresAndEvictsOpaqueApplicationHandlesOnTheCaptureThread() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        int[] state = {1};
        List<Integer> disposed = new ArrayList<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("checkpoints"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .checkpointLimits(new CheckpointLimits(1, 8, 642))
                .build();
        runtime.entities().register(EntityId.of("state"), EntityType.of("fixture"),
                () -> "State", inspector -> inspector.property("value", () -> state[0]));
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override
            public CheckpointHandle create() {
                return new StateHandle(state[0]);
            }

            @Override
            public void restore(CheckpointHandle handle) {
                state[0] = ((StateHandle) handle).value();
            }

            @Override
            public void dispose(CheckpointHandle handle) {
                disposed.add(((StateHandle) handle).value());
            }
        });
        runtime.start();

        runtime.checkpoints().create(
                "first", "Initial state", "create-first", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        CheckpointOperation first = runtime.checkpoints().create(
                "first", "Initial state", "create-first", Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> runtime.checkpoints().create(
                "first", null, "duplicate-create", Duration.ofSeconds(1)));
        assertEquals(CommandState.SUCCEEDED, first.command().status().orElseThrow().state());
        assertEquals(new FrameId(0), first.descriptor().orElseThrow().sourceFrameId());
        assertEquals(new ExecutionEpochId(0), first.descriptor().orElseThrow().sourceEpochId());

        state[0] = 2;
        runtime.frame(1, () -> {});
        runtime.checkpoints().restore("first", "restore-first", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        CheckpointOperation restored = runtime.checkpoints().restore(
                "first", "restore-first", Duration.ofSeconds(1));
        assertEquals(1, state[0]);
        assertEquals(new ExecutionEpochId(1), restored.baselineEpochId().orElseThrow());
        assertEquals(new FrameId(2), restored.baselineFrameId().orElseThrow());
        assertEquals(BaselineKind.CHECKPOINT_RESTORE,
                runtime.frame(new FrameId(2)).orElseThrow().baselineKind().orElseThrow());
        assertFalse(restored.applicationStateMayBePartiallyChanged());

        runtime.checkpoints().create(
                "second", null, "create-second", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        assertEquals(List.of(1), disposed);
        assertEquals(List.of("second"), runtime.checkpoints().list().stream()
                .map(CheckpointDescriptor::id).toList());
        runtime.close();
        assertEquals(List.of(1, 1), disposed);
    }

    @Test
    void failedRestoreClaimsNoBaselineAndReportsPossiblePartialState() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("failed-checkpoint"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .build();
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override
            public CheckpointHandle create() {
                return new StateHandle(1);
            }

            @Override
            public void restore(CheckpointHandle handle) {
                throw new IllegalStateException("restore failed after mutation");
            }

            @Override
            public void dispose(CheckpointHandle handle) {}
        });
        runtime.start();
        runtime.checkpoints().create("save", null, "create", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        runtime.checkpoints().restore("save", "restore", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        CheckpointOperation failed = runtime.checkpoints().restore(
                "save", "restore", Duration.ofSeconds(1));
        assertEquals(CommandState.FAILED, failed.command().status().orElseThrow().state());
        assertTrue(failed.applicationStateMayBePartiallyChanged());
        assertTrue(failed.baselineFrameId().isEmpty());
        assertEquals(new ExecutionEpochId(0), runtime.currentEpoch());
        ApplicationFailureEvidence failure = failed.applicationFailure().orElseThrow();
        assertEquals("checkpoint.restore", failure.category());
        String diagnostic = failed.diagnostic().orElseThrow();
        assertEquals(failure.legacyEnvelope(), diagnostic);
        assertTrue(diagnostic.startsWith("failed-checkpoint|failure-"));
        assertTrue(diagnostic.endsWith("|checkpoint.restore|java.lang.IllegalStateException"));
        assertFalse(diagnostic.contains("restore failed"));
    }

    @Test
    void failedCheckpointEvidenceOmitsRawApplicationMessages() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("checkpoint-leak"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .build();
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override
            public CheckpointHandle create() {
                throw new IllegalStateException("token=secret-123 /home/private/save.dat");
            }

            @Override
            public void restore(CheckpointHandle handle) {}

            @Override
            public void dispose(CheckpointHandle handle) {}
        });
        runtime.start();
        runtime.checkpoints().create("save", null, "create", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        CheckpointOperation failed = runtime.checkpoints().create(
                "save", null, "create", Duration.ofSeconds(1));
        assertEquals(CommandState.FAILED, failed.command().status().orElseThrow().state());
        ApplicationFailureEvidence failure = failed.applicationFailure().orElseThrow();
        assertEquals("checkpoint.create", failure.category());
        String diagnostic = failed.diagnostic().orElseThrow();
        assertEquals(failure.legacyEnvelope(), diagnostic);
        assertTrue(diagnostic.startsWith("checkpoint-leak|failure-"));
        assertTrue(diagnostic.endsWith("|checkpoint.create|java.lang.IllegalStateException"));
        assertFalse(diagnostic.contains("token=secret-123"));
        assertFalse(diagnostic.contains("/home/private/save.dat"));
        assertTrue(failure.sanitizedDetail().isEmpty());
        assertEquals(failure.correlationId(), failed.command().status().orElseThrow()
                .applicationFailure().orElseThrow().correlationId(),
                "feature failure must reuse the admitted command correlation");
    }

    @Test
    void checkpointDiagnosticRespectsDescriptionLength() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("checkpoint-bound"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .checkpointLimits(new CheckpointLimits(1, 8, 642))
                .build();
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override
            public CheckpointHandle create() {
                throw new IllegalStateException("token=secret-123 /home/private/save.dat");
            }

            @Override
            public void restore(CheckpointHandle handle) {}

            @Override
            public void dispose(CheckpointHandle handle) {}
        });
        runtime.start();
        runtime.checkpoints().create("save", null, "create", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        CheckpointOperation failed = runtime.checkpoints().create(
                "save", null, "create", Duration.ofSeconds(1));
        String diagnostic = failed.diagnostic().orElseThrow();
        assertTrue(diagnostic.length() <= 642);
        assertTrue(diagnostic.startsWith("checkpoint-bound|failure-"));
        assertTrue(diagnostic.endsWith("|checkpoint.create|java.lang.IllegalStateException"));
        assertFalse(diagnostic.contains("token=secret-123"));
        assertFalse(diagnostic.contains("/home/private/save.dat"));
    }

    @Test
    void failedEvictionDisposalDoesNotLeaveTheHandleRestorable() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("failed-disposal"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .checkpointLimits(new CheckpointLimits(1, 8, 642))
                .build();
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override public CheckpointHandle create() {
                return new StateHandle(1);
            }
            @Override public void restore(CheckpointHandle handle) {}
            @Override public void dispose(CheckpointHandle handle) {
                throw new IllegalStateException("disposal failed");
            }
        });
        runtime.start();
        runtime.checkpoints().create("first", null, "create-first", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        runtime.checkpoints().create("second", null, "create-second", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        CheckpointOperation failed = runtime.checkpoints().create(
                "second", null, "create-second", Duration.ofSeconds(1));
        assertEquals(CommandState.FAILED, failed.command().status().orElseThrow().state());
        assertTrue(runtime.checkpoints().list().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> runtime.checkpoints().restore(
                "first", "restore-first", Duration.ofSeconds(1)));
    }

    @Test
    void closeDisposesEveryRetainedHandleAndRejectsNewMutations() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        List<Integer> disposed = new ArrayList<>();
        AtomicInteger created = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("checkpoint-close"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .checkpointLimits(new CheckpointLimits(2, 8, 642))
                .build();
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override
            public CheckpointHandle create() {
                return new StateHandle(created.incrementAndGet());
            }

            @Override
            public void restore(CheckpointHandle handle) {}

            @Override
            public void dispose(CheckpointHandle handle) {
                disposed.add(((StateHandle) handle).value());
            }
        });
        runtime.start();
        runtime.checkpoints().create("one", null, "create-one", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        runtime.checkpoints().create("two", null, "create-two", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        assertEquals(List.of("one", "two"), runtime.checkpoints().list().stream()
                .map(CheckpointDescriptor::id).toList());

        runtime.close();

        assertEquals(List.of(1, 2), disposed);
        assertEquals(0, runtime.checkpoints().retainedCheckpoints());
        assertEquals(0, runtime.checkpoints().retainedOperations());
        AgentRuntimeException closed = assertThrows(AgentRuntimeException.class,
                () -> runtime.checkpoints().create(
                        "three", null, "create-three", Duration.ofSeconds(1)));
        assertEquals(RuntimeErrorCode.RUNTIME_CLOSED, closed.code());
    }

    private record StateHandle(int value) implements CheckpointHandle {}
}
