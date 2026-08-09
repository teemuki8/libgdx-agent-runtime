package io.github.teemuki8.libgdx.agent.runtime.examples;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.EventQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.EventSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameRange;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.libgdx.LibGdxAgentRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Minimal complete libGDX application for explicit state and semantic-event inspection. */
public final class BasicInspectionApplication extends ApplicationAdapter {
    private static final EntityId PLAYER_ID = EntityId.of("player");
    private static final long FRAME_DELTA_NANOS = 16_666_667L;

    private final Path evidencePath;
    private AgentRuntime runtime;
    private Thread captureThread;
    private long health = 100;
    private boolean captured;

    private BasicInspectionApplication(Path evidencePath) {
        this.evidencePath = evidencePath;
    }

    /** Starts a hidden-capable desktop application and writes one bounded evidence summary. */
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("expected one evidence output path");
        }
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("libGDX Agent Runtime Basic Inspection Example");
        configuration.setWindowedMode(320, 180);
        configuration.setInitialVisible(false);
        configuration.setResizable(false);
        configuration.useVsync(false);
        configuration.setForegroundFPS(60);
        configuration.disableAudio(true);
        new Lwjgl3Application(new BasicInspectionApplication(
                Path.of(args[0]).toAbsolutePath().normalize()), configuration);
    }

    @Override public void create() {
        captureThread = Thread.currentThread();
        runtime = LibGdxAgentRuntime.builder()
                .captureThread(captureThread)
                .sessionId(SessionId.of("basic-inspection-example"))
                .commandDispatcher(Gdx.app::postRunnable)
                .build();
        runtime.entities().register(PLAYER_ID, EntityType.of("player"), () -> "Player",
                inspector -> inspector.property("health", () -> health));
        runtime.start();
    }

    @Override public void render() {
        if (!captured) {
            captured = true;
            captureExampleFrame();
            Gdx.app.exit();
        }
        Gdx.gl.glClearColor(0.04f, 0.06f, 0.1f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    }

    @Override public void dispose() {
        if (runtime != null) {
            runtime.close();
        }
    }

    private void captureExampleFrame() {
        runtime.frame(FRAME_DELTA_NANOS, () -> {
            health = 75;
            runtime.emit(EventSpec.type("player.damaged")
                    .subject(PLAYER_ID)
                    .attribute("amount", RuntimeValues.integer(25)));
        });
        RuntimeValue healthValue = runtime.entity(PLAYER_ID).orElseThrow()
                .property("health").orElseThrow();
        long events = runtime.events(new EventQuery(
                FrameRange.of(1, 1), Optional.of("player.damaged"), false,
                Optional.of(PLAYER_ID), Optional.empty(), 8)).items().size();
        String evidence = "session=" + runtime.sessionId().value()
                + "\nlatestFrame=" + runtime.latestFrame().orElseThrow().frameId().value()
                + "\nhealth=" + ((RuntimeValue.IntegerValue) healthValue).value()
                + "\neventCount=" + events
                + "\ncaptureThreadCorrect=" + (Thread.currentThread() == captureThread)
                + "\n";
        try {
            Files.writeString(evidencePath, evidence, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("could not write basic example evidence", failure);
        }
    }
}
