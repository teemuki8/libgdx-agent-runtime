package io.github.teemuki8.libgdx.agent.runtime.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntimeException;
import io.github.teemuki8.libgdx.agent.runtime.core.CommandState;
import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ControlledWorkflowExampleTest {
    @Test
    void publicWorkflowCoversExactTicksCheckpointRecordingAndDeterminism() {
        try (ControlledWorkflowExample example = ControlledWorkflowExample.createQueued()) {
            ControlledWorkflowExample.WorkflowResult result = example.runWorkflow();

            assertEquals(CommandState.SUCCEEDED, result.resetState());
            assertEquals(60, result.completedTicks());
            assertEquals(1, result.firstAppliedEpochTick());
            assertEquals("PASS", result.expectedPosition());
            assertEquals("FAIL", result.wrongPosition());
            assertTrue(result.recordedInputs() >= 1);
            assertEquals(60, result.recordedTicks());
            assertEquals(DeterminismStatus.EQUAL, result.replay());
            assertEquals(60, result.replayedTicks());
            assertEquals(DeterminismStatus.EQUAL, result.determinism());
            assertEquals(RuntimeValues.vector2(0, 0), result.restoredPosition());
            assertTrue(result.tickFrameCorrelated());
        }
    }

    @Test
    void queuedRetryIsIdempotentAndConflictingRequestUseIsRejected() {
        try (ControlledWorkflowExample example = ControlledWorkflowExample.createQueued()) {
            var first = example.runtime().scenarios().reset(
                    "walk", "same-request", Duration.ofSeconds(2));
            var retry = example.runtime().scenarios().reset(
                    "walk", "same-request", Duration.ofSeconds(2));
            assertEquals(CommandState.QUEUED,
                    first.command().status().orElseThrow().state());
            assertEquals(CommandState.QUEUED,
                    retry.command().status().orElseThrow().state());
            assertEquals(1, example.queuedCommands());
            assertThrows(IllegalArgumentException.class, () -> example.runtime().controls()
                    .control(true, "same-request", Duration.ofSeconds(2)));

            example.drainAll();
            assertEquals(CommandState.SUCCEEDED, example.runtime().scenarios().reset(
                    "walk", "same-request", Duration.ofSeconds(2))
                    .command().status().orElseThrow().state());
        }
    }

    @Test
    void directAccumulatorMutationRejectsWrongThreadAndOpenFrame() throws Exception {
        try (ControlledWorkflowExample example = ControlledWorkflowExample.createQueued()) {
            AtomicReference<Throwable> wrongThread = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    example.updateNanos(0);
                } catch (Throwable failure) {
                    wrongThread.set(failure);
                }
            });
            thread.start();
            thread.join();
            assertTrue(wrongThread.get() instanceof AgentRuntimeException);

            example.runtime().frame(1, () -> assertThrows(
                    AgentRuntimeException.class, () -> example.updateNanos(0)));
        }
    }
}
