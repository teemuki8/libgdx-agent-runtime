package io.github.teemuki8.libgdx.agent.runtime.libgdx;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepSimulationConfiguration;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepSimulationRegistry;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepUpdateReport;
import java.util.Objects;
import java.util.function.Consumer;

/** Thin libGDX float-time facade over the core fixed-step accumulator. */
public final class LibGdxFixedStepSimulation {
    private static final double NANOS_PER_SECOND = 1_000_000_000d;
    private final FixedStepSimulationRegistry delegate;
    private final LibGdxFixedStepTick tick;

    private LibGdxFixedStepSimulation(FixedStepSimulationRegistry delegate,
            FixedStepSimulationConfiguration configuration) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        tick = new LibGdxFixedStepTick(configuration.fixedStepNanos(),
                configuration.fixedStepNanos() / (float) NANOS_PER_SECOND);
    }

    /** Registers an acknowledged application callback before runtime start. */
    public static LibGdxFixedStepSimulation acknowledged(AgentRuntime runtime,
            FixedStepSimulationConfiguration configuration, LibGdxFixedStepCallback callback) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(callback, "callback");
        LibGdxFixedStepSimulation simulation = new LibGdxFixedStepSimulation(
                runtime.fixedStepSimulation(), configuration);
        simulation.delegate.register(configuration, supplied -> callback.simulate(simulation.tick));
        return simulation;
    }

    /** Registers a legacy callback when configuration permits unacknowledged timing. */
    public static LibGdxFixedStepSimulation unacknowledged(AgentRuntime runtime,
            FixedStepSimulationConfiguration configuration,
            Consumer<LibGdxFixedStepTick> callback) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(callback, "callback");
        LibGdxFixedStepSimulation simulation = new LibGdxFixedStepSimulation(
                runtime.fixedStepSimulation(), configuration);
        simulation.delegate.registerUnacknowledged(
                configuration, supplied -> callback.accept(simulation.tick));
        return simulation;
    }

    /** Converts one finite non-negative render delta in seconds and updates the core accumulator. */
    public FixedStepUpdateReport update(float renderDeltaSeconds) {
        if (!Float.isFinite(renderDeltaSeconds) || renderDeltaSeconds < 0) {
            throw new IllegalArgumentException("render delta seconds must be finite and non-negative");
        }
        double nanos = renderDeltaSeconds * NANOS_PER_SECOND;
        if (nanos > Long.MAX_VALUE) {
            throw new IllegalArgumentException("render delta seconds exceed nanosecond range");
        }
        return delegate.update(Math.round(nanos));
    }

    /** Updates using an already converted exact non-negative render delta in nanoseconds. */
    public FixedStepUpdateReport updateNanos(long renderDeltaNanos) {
        return delegate.update(renderDeltaNanos);
    }

    /** Returns the canonical float value supplied to every libGDX simulation callback. */
    public float fixedStepSeconds() {
        return tick.fixedStepSeconds();
    }

    /** Returns render interpolation alpha without changing authoritative simulation state. */
    public double interpolationAlpha() {
        return delegate.interpolationAlpha();
    }
}
