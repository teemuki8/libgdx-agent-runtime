package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import io.github.teemuki8.libgdx.agent.runtime.core.CommandState;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepUpdateDiagnostic;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepUpdateReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Hidden LWJGL3 application proving the canonical helper in a real render loop. */
public final class FixedStepFixtureApplication extends ApplicationAdapter {
    private final Path evidencePath;
    private FixedStepFixtureSimulation fixture;
    private Phase phase = Phase.RUNNING;
    private int normalTicks;
    private long remainderBeforeControl;
    private boolean renderReports;
    private boolean pausedDiagnostic;

    private FixedStepFixtureApplication(Path evidencePath) {
        this.evidencePath = evidencePath;
    }

    /** Launches the hidden fixed-step native fixture. */
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("expected one fixture evidence path");
        }
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("libGDX Agent Runtime Fixed-Step Fixture");
        configuration.setWindowedMode(320, 180);
        configuration.setInitialVisible(false);
        configuration.setResizable(false);
        configuration.useVsync(false);
        configuration.setForegroundFPS(60);
        configuration.disableAudio(true);
        new Lwjgl3Application(new FixedStepFixtureApplication(
                Path.of(args[0]).toAbsolutePath().normalize()), configuration);
    }

    @Override public void create() {
        fixture = new FixedStepFixtureSimulation(Gdx.app::postRunnable);
    }

    @Override public void render() {
        FixedStepUpdateReport report = fixture.simulation().update(Gdx.graphics.getDeltaTime());
        renderReports |= fixture.runtime().fixedStepSimulation().state().retainedUpdateReports() > 0;
        pausedDiagnostic |= report.diagnostics().contains(
                FixedStepUpdateDiagnostic.PAUSED_RENDER_TIME_IGNORED);
        advancePhase();
        Gdx.gl.glClearColor(0.04f, 0.06f, 0.1f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    }

    @Override public void dispose() {
        if (fixture != null) {
            fixture.runtime().close();
        }
    }

    private void advancePhase() {
        if (phase == Phase.RUNNING && fixture.completedTicks() >= 2) {
            normalTicks = fixture.completedTicks();
            remainderBeforeControl = fixture.runtime().fixedStepSimulation().state()
                    .accumulatorRemainderNanos();
            fixture.runtime().controls().control(
                    true, "native-fixed-pause", Duration.ofSeconds(5));
            phase = Phase.PAUSING;
        } else if (phase == Phase.PAUSING && succeeded("native-fixed-pause")) {
            fixture.runtime().controls().advanceFixed(
                    "native-fixed-advance", 2, Duration.ofSeconds(5));
            phase = Phase.ADVANCING;
        } else if (phase == Phase.ADVANCING && succeeded("native-fixed-advance")) {
            writeEvidence();
            phase = Phase.COMPLETE;
            Gdx.app.exit();
        }
    }

    private boolean succeeded(String requestId) {
        return fixture.runtime().commands().orElseThrow().status(requestId).status()
                .map(status -> status.state() == CommandState.SUCCEEDED)
                .orElse(false);
    }

    private void writeEvidence() {
        long remainderAfterControl = fixture.runtime().fixedStepSimulation().state()
                .accumulatorRemainderNanos();
        long timelineTicks = fixture.runtime().simulation().state().completedEpochTicks();
        long fixedStepNanos = fixture.runtime().fixedStepSimulation().state()
                .configuration().orElseThrow().fixedStepNanos();
        String evidence = "session=" + fixture.runtime().sessionId().value()
                + "\nfixedStepNanos=" + fixedStepNanos
                + "\nnormalTicks=" + normalTicks
                + "\ncontrolledTicks=" + fixture.runtime().controls().currentTick()
                + "\nremainderPreserved=" + (remainderBeforeControl == remainderAfterControl)
                + "\ntimelineMatches=" + (timelineTicks == fixture.completedTicks())
                + "\nrenderReports=" + renderReports
                + "\npausedDiagnostic=" + pausedDiagnostic
                + "\n";
        try {
            Files.writeString(evidencePath, evidence, StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("could not write fixed-step fixture evidence", failure);
        }
    }

    private enum Phase {
        RUNNING, PAUSING, ADVANCING, COMPLETE
    }
}
