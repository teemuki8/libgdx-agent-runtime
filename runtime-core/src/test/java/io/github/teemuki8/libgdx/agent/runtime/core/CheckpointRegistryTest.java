package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
    void stagingFaultBeforePublicationDisposesOnlyTheNewHandleAndKeepsTheOriginalRegistry() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        List<Integer> disposed = new ArrayList<>();
        AtomicInteger created = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("atomic-staging-fault"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .checkpointLimits(new CheckpointLimits(1, 8, 642))
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
        runtime.checkpoints().create("first", null, "create-first", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        runtime.checkpoints().injectStagingFault(() -> {
            throw new AssertionError("staging fault sentinel");
        });
        runtime.checkpoints().create("second", null, "create-second", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        CheckpointOperation failed = runtime.checkpoints().create(
                "second", null, "create-second", Duration.ofSeconds(1));

        assertEquals(CommandState.FAILED, failed.command().status().orElseThrow().state());
        ApplicationFailureEvidence failure = failed.applicationFailure().orElseThrow();
        assertEquals("checkpoint.create", failure.category());
        assertEquals(List.of("first"), runtime.checkpoints().list().stream()
                .map(CheckpointDescriptor::id).toList(),
                "the original descriptor catalog must be unchanged");
        assertEquals(1, runtime.checkpoints().retainedCheckpoints(),
                "the original registry keeps exactly one retained checkpoint");
        assertEquals(List.of(2), disposed,
                "only the new handle is disposed exactly once, with no old-handle disposal");
        assertTrue(failed.diagnostic().orElseThrow().endsWith(
                "|checkpoint.create|java.lang.AssertionError"),
                "the primary staging fault is retained as the command failure");
        runtime.checkpoints().restore("first", "restore-first", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        CheckpointOperation restored = runtime.checkpoints().restore(
                "first", "restore-first", Duration.ofSeconds(1));
        assertEquals(CommandState.SUCCEEDED, restored.command().status().orElseThrow().state());
        assertEquals(List.of(2), disposed, "the failed replacement must not double-dispose");
    }

    @Test
    void reentrantNestedCreateEvictsTheCurrentOldestAndCommitsTheOuter() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        List<Integer> disposed = new ArrayList<>();
        AtomicInteger created = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("atomic-reentrant"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .checkpointLimits(new CheckpointLimits(1, 8, 642))
                .build();
        runtime.checkpoints().register(new CheckpointProvider() {
            private int calls;

            @Override
            public CheckpointHandle create() {
                if (++calls == 2) {
                    // The outer replacement's create reentrantly completes the queued nested
                    // create before returning its own handle.
                    dispatch.removeFirst().run();
                }
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
        runtime.checkpoints().create("first", null, "create-first", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        runtime.checkpoints().create("outer", null, "create-outer", Duration.ofSeconds(1));
        runtime.checkpoints().create("nested", null, "create-nested", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        CheckpointOperation outer = runtime.checkpoints().create(
                "outer", null, "create-outer", Duration.ofSeconds(1));

        assertEquals(CommandState.SUCCEEDED, outer.command().status().orElseThrow().state());
        assertEquals(List.of("outer"), runtime.checkpoints().list().stream()
                .map(CheckpointDescriptor::id).toList(),
                "the outer create commits as the single retained checkpoint");
        assertEquals(1, runtime.checkpoints().retainedCheckpoints());
        assertEquals(List.of(1, 2), disposed,
                "the nested eviction disposes the first handle once and the outer commit "
                        + "disposes the nested handle once, never the first twice");
        assertThrows(IllegalArgumentException.class, () -> runtime.checkpoints().restore(
                "first", "restore-first", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> runtime.checkpoints().restore(
                "nested", "restore-nested", Duration.ofSeconds(1)));
        runtime.checkpoints().restore("outer", "restore-outer", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        CheckpointOperation restored = runtime.checkpoints().restore(
                "outer", "restore-outer", Duration.ofSeconds(1));
        assertEquals(CommandState.SUCCEEDED, restored.command().status().orElseThrow().state());
        assertEquals(List.of(1, 2), disposed, "no handle is ever disposed twice");
    }

    @Test
    void reentrantNestedCreateFailureBeforePublicationPreservesTheNestedCreate() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        List<Integer> disposed = new ArrayList<>();
        AtomicInteger created = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("atomic-reentrant-failure"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .checkpointLimits(new CheckpointLimits(1, 8, 642))
                .build();
        runtime.checkpoints().register(new CheckpointProvider() {
            private int calls;

            @Override
            public CheckpointHandle create() {
                if (++calls == 2) {
                    dispatch.removeFirst().run();
                    // Arm the publication fault only after the nested create committed, so the
                    // outer publication is the one that fails.
                    runtime.checkpoints().injectStagingFault(() -> {
                        throw new AssertionError("staging fault sentinel");
                    });
                }
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
        runtime.checkpoints().create("first", null, "create-first", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        runtime.checkpoints().create("outer", null, "create-outer", Duration.ofSeconds(1));
        runtime.checkpoints().create("nested", null, "create-nested", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        CheckpointOperation failed = runtime.checkpoints().create(
                "outer", null, "create-outer", Duration.ofSeconds(1));

        assertEquals(CommandState.FAILED, failed.command().status().orElseThrow().state());
        ApplicationFailureEvidence failure = failed.applicationFailure().orElseThrow();
        assertEquals("checkpoint.create", failure.category());
        assertEquals(List.of("nested"), runtime.checkpoints().list().stream()
                .map(CheckpointDescriptor::id).toList(),
                "the nested create survives the failed outer publication, not stale pre-callback state");
        assertEquals(1, runtime.checkpoints().retainedCheckpoints());
        assertEquals(List.of(1, 3), disposed,
                "the nested eviction disposes the first handle once and the failed outer "
                        + "disposes its new handle once");
        assertTrue(failed.diagnostic().orElseThrow().endsWith(
                "|checkpoint.create|java.lang.AssertionError"),
                "the primary staging fault is retained as the command failure");
        runtime.checkpoints().restore("nested", "restore-nested", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        CheckpointOperation restored = runtime.checkpoints().restore(
                "nested", "restore-nested", Duration.ofSeconds(1));
        assertEquals(CommandState.SUCCEEDED, restored.command().status().orElseThrow().state());
        assertEquals(List.of(1, 3), disposed, "no handle is ever disposed twice");
    }

    @Test
    void closeClearsInjectedStagingFault() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("staging-fault-close"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .build();
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override
            public CheckpointHandle create() {
                return new StateHandle(1);
            }

            @Override
            public void restore(CheckpointHandle handle) {}

            @Override
            public void dispose(CheckpointHandle handle) {}
        });
        runtime.start();
        assertFalse(runtime.checkpoints().hasStagingFault());
        runtime.checkpoints().injectStagingFault(() -> {
            throw new AssertionError("sentinel");
        });
        assertTrue(runtime.checkpoints().hasStagingFault());
        runtime.close();
        assertFalse(runtime.checkpoints().hasStagingFault(),
                "close must drop the injected staging fault callback");
    }

    @Test
    void failedEvictionDisposalDoesNotFailTheCommittedReplacement() {
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

        CheckpointOperation replaced = runtime.checkpoints().create(
                "second", null, "create-second", Duration.ofSeconds(1));
        assertEquals(CommandState.SUCCEEDED, replaced.command().status().orElseThrow().state());
        // The replacement is installed before the evicted handle is released, so a disposal
        // failure never rolls back the committed create and never leaves the old handle restorable.
        assertEquals(List.of("second"), runtime.checkpoints().list().stream()
                .map(CheckpointDescriptor::id).toList());
        assertThrows(IllegalArgumentException.class, () -> runtime.checkpoints().restore(
                "first", "restore-first", Duration.ofSeconds(1)));
    }

    @Test
    void failedReplacementPreservesThePriorCheckpoint() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        int[] state = {1};
        List<Integer> disposed = new ArrayList<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("atomic-failed-create"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .checkpointLimits(new CheckpointLimits(1, 8, 642))
                .build();
        runtime.checkpoints().register(new CheckpointProvider() {
            private int calls;

            @Override
            public CheckpointHandle create() {
                if (++calls > 1) {
                    throw new IllegalStateException("create failed");
                }
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
        runtime.checkpoints().create("first", null, "create-first", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        runtime.checkpoints().create("second", null, "create-second", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        CheckpointOperation failed = runtime.checkpoints().create(
                "second", null, "create-second", Duration.ofSeconds(1));

        assertEquals(CommandState.FAILED, failed.command().status().orElseThrow().state());
        ApplicationFailureEvidence failure = failed.applicationFailure().orElseThrow();
        assertEquals("checkpoint.create", failure.category());
        assertEquals(List.of("first"), runtime.checkpoints().list().stream()
                .map(CheckpointDescriptor::id).toList());
        assertTrue(disposed.isEmpty(), "a failed replacement must not dispose any handle");
        state[0] = 2;
        runtime.frame(1, () -> {});
        runtime.checkpoints().restore("first", "restore-first", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        CheckpointOperation restored = runtime.checkpoints().restore(
                "first", "restore-first", Duration.ofSeconds(1));
        assertEquals(CommandState.SUCCEEDED, restored.command().status().orElseThrow().state());
        assertEquals(1, state[0], "the preserved handle must remain restorable");
    }

    @Test
    void successfulReplacementDisposesExactlyTheEvictedHandle() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        List<Integer> disposed = new ArrayList<>();
        AtomicInteger created = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("atomic-replace"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .checkpointLimits(new CheckpointLimits(1, 8, 642))
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
        runtime.checkpoints().create("first", null, "create-first", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        runtime.checkpoints().create("second", null, "create-second", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        CheckpointOperation replacement = runtime.checkpoints().create(
                "second", null, "create-second", Duration.ofSeconds(1));

        assertEquals(CommandState.SUCCEEDED, replacement.command().status().orElseThrow().state());
        assertEquals(List.of(1), disposed, "exactly the evicted handle is disposed once");
        assertEquals(List.of("second"), runtime.checkpoints().list().stream()
                .map(CheckpointDescriptor::id).toList());
        assertThrows(IllegalArgumentException.class, () -> runtime.checkpoints().restore(
                "first", "restore-first", Duration.ofSeconds(1)));
    }

    @Test
    void failedInstallationDisposesOnlyTheNewHandleAndKeepsTheRegistryValid() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        List<Integer> disposed = new ArrayList<>();
        AtomicInteger created = new AtomicInteger();
        boolean[] failWallClock = {false};
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("atomic-install-failure"))
                .clock(() -> 1)
                .wallClock(new Clock() {
                    @Override
                    public ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        if (failWallClock[0]) {
                            throw new IllegalStateException("wall clock failed");
                        }
                        return Clock.systemUTC().instant();
                    }
                })
                .commandDispatcher(dispatch::addLast)
                .checkpointLimits(new CheckpointLimits(1, 8, 642))
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
                int value = ((StateHandle) handle).value();
                disposed.add(value);
                if (value == 2) {
                    throw new IllegalArgumentException("dispose failed");
                }
            }
        });
        runtime.start();
        runtime.checkpoints().create("first", null, "create-first", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        failWallClock[0] = true;
        runtime.checkpoints().create("second", null, "create-second", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        failWallClock[0] = false;

        CheckpointOperation failed = runtime.checkpoints().create(
                "second", null, "create-second", Duration.ofSeconds(1));
        assertEquals(CommandState.FAILED, failed.command().status().orElseThrow().state());
        ApplicationFailureEvidence failure = failed.applicationFailure().orElseThrow();
        assertEquals("checkpoint.create", failure.category());
        assertEquals(List.of(2), disposed,
                "only the newly created handle is disposed after an installation failure");
        assertEquals(List.of("first"), runtime.checkpoints().list().stream()
                .map(CheckpointDescriptor::id).toList(),
                "the original registry must keep the prior checkpoint");
        runtime.checkpoints().restore("first", "restore-first", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        CheckpointOperation restored = runtime.checkpoints().restore(
                "first", "restore-first", Duration.ofSeconds(1));
        assertEquals(CommandState.SUCCEEDED, restored.command().status().orElseThrow().state());
        assertTrue(restored.baselineEpochId().isPresent());
        assertTrue(failed.diagnostic().orElseThrow().endsWith(
                "|checkpoint.create|java.lang.IllegalStateException"),
                "the primary installation failure is retained over the suppressed cleanup failure");
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

    @Test
    void closeRetainsCompletedDescriptorsAndReleasesPendingCheckpointWork() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        List<Integer> disposed = new ArrayList<>();
        AtomicInteger created = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("checkpoint-close-evidence"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .checkpointLimits(new CheckpointLimits(4, 8, 642))
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
        runtime.checkpoints().create("done", null, "create-done", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        runtime.checkpoints().create("pending", null, "create-pending", Duration.ofSeconds(1));
        assertEquals(2, runtime.checkpoints().retainedOperations());
        assertEquals(List.of("done"), runtime.checkpoints().list().stream()
                .map(CheckpointDescriptor::id).toList());

        runtime.close();

        // Completed immutable descriptor evidence remains queryable.
        assertEquals(List.of("done"), runtime.checkpoints().list().stream()
                .map(CheckpointDescriptor::id).toList());
        // Pending work, provider, and closures are released; only the completed handle was disposed.
        assertEquals(List.of(1), disposed);
        assertEquals(0, runtime.checkpoints().retainedCheckpoints());
        assertEquals(0, runtime.checkpoints().retainedOperations());
        dispatch.forEach(Runnable::run);
        assertEquals(List.of(1), disposed,
                "the pending create must not execute after close");
        assertEquals(1, created.get());
        AgentRuntimeException closed = assertThrows(AgentRuntimeException.class,
                () -> runtime.checkpoints().create(
                        "after", null, "create-after", Duration.ofSeconds(1)));
        assertEquals(RuntimeErrorCode.RUNTIME_CLOSED, closed.code());
    }

    private record StateHandle(int value) implements CheckpointHandle {}
}
