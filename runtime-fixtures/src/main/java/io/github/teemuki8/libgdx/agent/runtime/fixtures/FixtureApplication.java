package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.CommandState;
import io.github.teemuki8.libgdx.agent.runtime.core.ChangeQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EventQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameRange;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Hidden real LWJGL3 application that writes compact semantic smoke evidence and exits. */
public final class FixtureApplication extends ApplicationAdapter {
    private final Path evidencePath;
    private final DeterministicSimulation simulation = new DeterministicSimulation();
    private AgentRuntime runtime;
    private int frame;
    private Thread applicationThread;
    private boolean dispatchThreadCorrect = true;
    private Phase phase = Phase.PAUSING;

    private FixtureApplication(Path evidencePath) {
        this.evidencePath = evidencePath;
    }

    /** Launches the hidden native fixture. */
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("expected one fixture evidence path");
        }
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("libGDX Agent Runtime Fixture");
        configuration.setWindowedMode(320, 180);
        configuration.setInitialVisible(false);
        configuration.setResizable(false);
        configuration.useVsync(false);
        configuration.setForegroundFPS(60);
        configuration.disableAudio(true);
        new Lwjgl3Application(
                new FixtureApplication(Path.of(args[0]).toAbsolutePath().normalize()),
                configuration);
    }

    @Override public void create() {
        applicationThread = Thread.currentThread();
        runtime = simulation.startRuntime(command -> Gdx.app.postRunnable(() -> {
            dispatchThreadCorrect &= Thread.currentThread() == applicationThread;
            command.run();
        }));
        runtime.controls().control(true, "native-pause", Duration.ofSeconds(5));
    }

    @Override public void render() {
        advanceControlConformance();
        if (phase != Phase.RUNNING) {
            clear();
            return;
        }
        frame++;
        simulation.advance(runtime, frame);
        clear();
        if (frame == 45) {
            writeEvidence();
            Gdx.app.exit();
        }
    }

    @Override public void dispose() {
        if (runtime != null) {
            runtime.close();
        }
    }

    private void advanceControlConformance() {
        if (phase == Phase.PAUSING
                && succeeded("native-pause")) {
            runtime.controls().advance(
                    "native-advance", 2, 16_000_000, Duration.ofSeconds(5));
            phase = Phase.ADVANCING;
        } else if (phase == Phase.ADVANCING
                && succeeded("native-advance")) {
            runtime.controls().control(false, "native-resume", Duration.ofSeconds(5));
            phase = Phase.RESUMING;
        } else if (phase == Phase.RESUMING
                && succeeded("native-resume")) {
            frame = 2;
            phase = Phase.RUNNING;
        }
    }

    private boolean succeeded(String requestId) {
        return runtime.commands().orElseThrow().status(requestId).status()
                .map(status -> status.state() == CommandState.SUCCEEDED)
                .orElse(false);
    }

    private static void clear() {
        Gdx.gl.glClearColor(0.04f, 0.06f, 0.1f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    }

    private void writeEvidence() {
        FrameRange range = FrameRange.of(0, 45);
        int healthChanges = runtime.changes(new ChangeQuery(
                range, Optional.of(EntityId.of("enemy-2")), Optional.empty(),
                Optional.of("health"), 100)).items().size();
        int events = runtime.events(new EventQuery(
                range, Optional.empty(), false, Optional.empty(), Optional.empty(), 100))
                .items().size();
        int decisions = runtime.decisions(new DecisionQuery(
                range, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("out-of-range"), 100)).items().size();
        String evidence = "session=" + runtime.sessionId().value()
                + "\nlatestFrame=" + runtime.latestFrame().orElseThrow().frameId().value()
                + "\nhealthChanges=" + healthChanges
                + "\nevents=" + events
                + "\ndecisions=" + decisions
                + "\nenemy2Present=" + runtime.entity(EntityId.of("enemy-2")).isPresent()
                + "\ncontrolledFrames=2"
                + "\ndispatchThreadCorrect=" + dispatchThreadCorrect
                + "\nmutationThreadCorrect="
                + (simulation.lastMutationThread() == applicationThread)
                + "\ncontrolledDeltaNanos=" + simulation.controlledDeltaNanos()
                + "\n";
        try {
            Files.writeString(evidencePath, evidence, StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("could not write fixture evidence", failure);
        }
    }

    private enum Phase {
        PAUSING, ADVANCING, RESUMING, RUNNING
    }
}
