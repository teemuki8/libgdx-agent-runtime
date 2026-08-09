package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.ApplicationCommandDispatcher;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepDropPolicy;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepSimulationConfiguration;
import io.github.teemuki8.libgdx.agent.runtime.core.InputSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.libgdx.LibGdxAgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.libgdx.LibGdxFixedStepSimulation;
import java.util.Objects;

/** Small fixed-step fixture covering normal updates and exact paused control. */
public final class FixedStepFixtureSimulation {
    /** Stable fixed-step fixture session ID. */
    public static final SessionId SESSION_ID = SessionId.of("fixed-step-fixture");
    private final AgentRuntime runtime;
    private final LibGdxFixedStepSimulation simulation;
    private int completedTicks;
    private boolean boostApplied;
    private boolean controlledTickObservedInput;

    /** Creates and starts the fixture using the application's explicit command dispatcher. */
    public FixedStepFixtureSimulation(ApplicationCommandDispatcher dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        runtime = LibGdxAgentRuntime.builder()
                .captureThread(Thread.currentThread())
                .sessionId(SESSION_ID)
                .commandDispatcher(dispatcher)
                .build();
        runtime.inputs().register(InputSpec.builder("boost")
                .description("Marks boost input before the next controlled fixed step")
                .handler(parameters -> boostApplied = true)
                .build());
        simulation = LibGdxFixedStepSimulation.acknowledged(runtime,
                new FixedStepSimulationConfiguration(
                        10_000_000L, 30_000_000L, 30_000_000L, 3, 16,
                        FixedStepDropPolicy.DROP_WHOLE_TICKS_KEEP_REMAINDER, true), tick -> {
                            completedTicks++;
                            if (runtime.controls().paused() && boostApplied) {
                                controlledTickObservedInput = true;
                            }
                            return tick.fixedStepNanos();
                        });
        runtime.start();
    }

    /** Returns the started runtime. */
    public AgentRuntime runtime() {
        return runtime;
    }

    /** Returns the canonical libGDX fixed-step facade. */
    public LibGdxFixedStepSimulation simulation() {
        return simulation;
    }

    /** Returns the application callback count. */
    public int completedTicks() {
        return completedTicks;
    }

    /** Reports whether controlled simulation observed scheduled input first. */
    public boolean controlledTickObservedInput() {
        return controlledTickObservedInput;
    }
}
