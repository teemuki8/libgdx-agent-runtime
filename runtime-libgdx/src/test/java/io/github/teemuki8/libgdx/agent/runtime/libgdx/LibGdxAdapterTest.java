package io.github.teemuki8.libgdx.agent.runtime.libgdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.Vector2;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepDropPolicy;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepSimulationConfiguration;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;

final class LibGdxAdapterTest {
    @Test
    void vectorConversionCopiesMutableState() {
        Vector2 vector = new Vector2(2.5f, 4f);
        var value = LibGdxValues.vector2(vector);
        vector.set(9, 9);
        assertEquals(RuntimeValues.vector2(2.5, 4), value);
        assertNotSame(vector, value);
    }

    @Test
    void renderGuardRejectsAnotherThread() throws Exception {
        RenderThreadGuard guard = RenderThreadGuard.currentThread();
        guard.check();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> executor.submit(guard::check).get());
            assertEquals(IllegalStateException.class, failure.getCause().getClass());
        }
    }

    @Test
    void adapterForwardsExplicitApplicationCommandDispatcher() {
        ArrayDeque<Runnable> applicationQueue = new ArrayDeque<>();
        var runtime = LibGdxAgentRuntime.builder()
                .commandDispatcher(applicationQueue::addLast)
                .build();
        assertTrue(runtime.commands().isPresent());
    }

    @Test
    void fixedStepFacadeConvertsFiniteFloatOnceAndDelegatesToCoreAccumulator() {
        var runtime = LibGdxAgentRuntime.builder().build();
        var configuration = new FixedStepSimulationConfiguration(
                10_000_000L, 30_000_000L, 30_000_000L, 3, 4,
                FixedStepDropPolicy.DROP_WHOLE_TICKS_KEEP_REMAINDER, true);
        float[] observedSeconds = {0};
        LibGdxFixedStepSimulation simulation = LibGdxFixedStepSimulation.acknowledged(
                runtime, configuration, tick -> {
                    observedSeconds[0] = tick.fixedStepSeconds();
                    return tick.fixedStepNanos();
                });
        runtime.start();

        var report = simulation.update(0.025f);

        assertEquals(2, report.ticksCompleted());
        assertEquals(5_000_000L, report.accumulatorRemainderNanos());
        assertEquals(0.01f, simulation.fixedStepSeconds());
        assertEquals(simulation.fixedStepSeconds(), observedSeconds[0]);
        assertEquals(0.5, simulation.interpolationAlpha());
        assertThrows(IllegalArgumentException.class, () -> simulation.update(Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> simulation.update(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> simulation.update(-0.1f));
    }
}
