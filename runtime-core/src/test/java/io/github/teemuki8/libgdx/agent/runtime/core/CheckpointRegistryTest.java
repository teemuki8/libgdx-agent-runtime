package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
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
                .checkpointLimits(new CheckpointLimits(1, 8, 64))
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
        String diagnostic = failed.diagnostic().orElseThrow();
        assertTrue(diagnostic.contains("checkpoint.restore"));
        assertTrue(diagnostic.contains("failure-1"));
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
        String diagnostic = failed.diagnostic().orElseThrow();
        assertFalse(diagnostic.contains("token=secret-123"));
        assertFalse(diagnostic.contains("/home/private/save.dat"));
        assertTrue(diagnostic.contains("checkpoint.create"));
        assertTrue(diagnostic.contains("failure-1"));
    }

    @Test
    void failedEvictionDisposalDoesNotLeaveTheHandleRestorable() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("failed-disposal"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .checkpointLimits(new CheckpointLimits(1, 8, 64))
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

    private record StateHandle(int value) implements CheckpointHandle {}
}
