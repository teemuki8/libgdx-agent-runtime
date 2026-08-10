package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dAssertions;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dDeterminism;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dVector;
import io.github.teemuki8.libgdx.agent.runtime.core.AssertionStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.CommandState;
import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.ExecutionEpochId;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.ReplayCaptureSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertion;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertionScope;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTickQuery;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Hidden LWJGL3 application proving the complete actual-native Box2D vertical slice. */
public final class Box2dConformanceApplication extends ApplicationAdapter {
    private static final int CONTROLLED_TICKS = 60;

    private final Path evidencePath;
    private Box2dConformanceSimulation fixture;
    private Thread applicationThread;
    private boolean dispatchThreadCorrect = true;
    private Phase phase = Phase.RUNNING;
    private long runningTicks;
    private ExecutionEpochId playerEpoch;
    private Box2dDeterminism.WorldSettings settings;
    private AssertionStatus playerPositionStatus;
    private AssertionStatus playerContactStatus;
    private DeterminismStatus determinismStatus;
    private boolean frameCorrelated;

    private Box2dConformanceApplication(Path evidencePath) {
        this.evidencePath = evidencePath;
    }

    /** Launches the hidden native fixture. */
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("expected one fixture evidence path");
        }
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("libGDX Agent Runtime Box2D Conformance");
        configuration.setWindowedMode(320, 180);
        configuration.setInitialVisible(false);
        configuration.setResizable(false);
        configuration.useVsync(false);
        configuration.setForegroundFPS(60);
        configuration.disableAudio(true);
        new Lwjgl3Application(new Box2dConformanceApplication(
                Path.of(args[0]).toAbsolutePath().normalize()), configuration);
    }

    @Override public void create() {
        applicationThread = Thread.currentThread();
        fixture = new Box2dConformanceSimulation(command -> Gdx.app.postRunnable(() -> {
            dispatchThreadCorrect &= Thread.currentThread() == applicationThread;
            command.run();
        }));
        settings = new Box2dDeterminism.WorldSettings(
                Box2dConformanceSimulation.FIXED_STEP_NANOS,
                new Box2dVector(0, 0), 8, 3, true, true, true);
    }

    @Override public void render() {
        advancePhase();
        Gdx.gl.glClearColor(0.04f, 0.06f, 0.1f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    }

    @Override public void dispose() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private void advancePhase() {
        switch (phase) {
            case RUNNING -> advanceRunning();
            case PAUSING -> awaitPause();
            case RESETTING -> awaitReset();
            case REPLAY_STARTING -> awaitReplayStart();
            case INPUTTING -> awaitInput();
            case ADVANCING -> awaitAdvance();
            case REPLAY_STOPPING -> awaitReplayStop();
            case DETERMINISM -> awaitDeterminism();
            case REPLAYING -> awaitReplay();
            case COMPLETE -> {
                // Exit is already requested.
            }
        }
    }

    private void advanceRunning() {
        fixture.simulation().update(Gdx.graphics.getDeltaTime());
        runningTicks = fixture.runtime().simulation().state().completedEpochTicks();
        if (runningTicks >= 2) {
            fixture.runtime().controls().control(
                    true, "box2d-native-pause", Duration.ofSeconds(5));
            phase = Phase.PAUSING;
        }
    }

    private void awaitPause() {
        fixture.simulation().update(Gdx.graphics.getDeltaTime());
        if (succeeded("box2d-native-pause")) {
            fixture.runtime().scenarios().reset(Box2dConformanceSimulation.PLAYER_MOVEMENT,
                    "box2d-native-player-reset", Duration.ofSeconds(5));
            phase = Phase.RESETTING;
        }
    }

    private void awaitReset() {
        if (succeeded("box2d-native-player-reset")) {
            fixture.runtime().replays().start(replaySpec(),
                    "box2d-native-replay-start", Duration.ofSeconds(10));
            phase = Phase.REPLAY_STARTING;
        }
    }

    private void awaitReplayStart() {
        if (succeeded("box2d-native-replay-start")) {
            playerEpoch = fixture.runtime().currentEpoch();
            long targetTick = fixture.runtime().controls().currentTick() + 1;
            fixture.runtime().inputs().inject("move-player", "box2d-native-move",
                    RuntimeValues.object(RuntimeValues.field(
                            "velocityX", RuntimeValues.decimal("4"))),
                    OptionalLong.of(targetTick), Duration.ofSeconds(5));
            phase = Phase.INPUTTING;
        }
    }

    private void awaitInput() {
        if (succeeded("box2d-native-move")) {
            fixture.runtime().controls().advanceFixed(
                    "box2d-native-advance", CONTROLLED_TICKS, Duration.ofSeconds(10));
            phase = Phase.ADVANCING;
        }
    }

    private void awaitAdvance() {
        if (succeeded("box2d-native-advance")) {
            fixture.recordRender();
            fixture.runtime().recordings().stop(
                    "box2d-native-replay", "box2d-native-replay-stop",
                    Duration.ofSeconds(10));
            phase = Phase.REPLAY_STOPPING;
        }
    }

    private void awaitReplayStop() {
        if (succeeded("box2d-native-replay-stop")) {
            var spec = Box2dDeterminism.builder("main", settings,
                            Box2dConformanceSimulation.PLAYER_MOVEMENT, 7,
                            RuntimeValues.object(), 2, CONTROLLED_TICKS)
                    .body("player", "position", "linearVelocity")
                    .contactEvents()
                    .input(1, "move-player", RuntimeValues.object(RuntimeValues.field(
                            "velocityX", RuntimeValues.decimal("4"))))
                    .build();
            fixture.runtime().determinism().checkSimulation(
                    spec, "box2d-native-determinism", Duration.ofSeconds(20));
            phase = Phase.DETERMINISM;
        }
    }

    private void awaitDeterminism() {
        if (!succeeded("box2d-native-determinism")) {
            return;
        }
        var operation = fixture.runtime().determinism().checkSimulation(
                Box2dDeterminism.builder("main", settings,
                                Box2dConformanceSimulation.PLAYER_MOVEMENT, 7,
                                RuntimeValues.object(), 2, CONTROLLED_TICKS)
                        .body("player", "position", "linearVelocity")
                        .contactEvents()
                        .input(1, "move-player", RuntimeValues.object(RuntimeValues.field(
                                "velocityX", RuntimeValues.decimal("4"))))
                        .build(),
                "box2d-native-determinism", Duration.ofSeconds(20));
        var playerPosition = Box2dAssertions.bodyPositionApproximately(
                "player", new Box2dVector(2, 1), 0.04,
                SimulationAssertion.VectorToleranceMode.COMPONENT);
        var player = new Box2dAssertions.ContactEndpoint(
                "player", "player-shape", 0);
        var wall = new Box2dAssertions.ContactEndpoint("wall", "wall-shape", 0);
        AssertionStatus position = fixture.runtime().assertions().evaluateSimulation(
                playerPosition, new SimulationAssertionScope(
                        playerEpoch, CONTROLLED_TICKS, CONTROLLED_TICKS, 8)).status();
        AssertionStatus contact = fixture.runtime().assertions().evaluateSimulation(
                Box2dAssertions.contactOccurred("main", player, wall),
                new SimulationAssertionScope(playerEpoch, 1, CONTROLLED_TICKS, 8)).status();
        var tick = fixture.runtime().simulation().ticks(new SimulationTickQuery(
                playerEpoch, CONTROLLED_TICKS, CONTROLLED_TICKS, 1)).ticks().getFirst();
        playerPositionStatus = position;
        playerContactStatus = contact;
        determinismStatus = operation.result().orElseThrow().status();
        frameCorrelated = tick.resultingFrameId().isPresent();
        fixture.runtime().replays().execute(
                "box2d-native-replay", "box2d-native-replay-execute",
                Duration.ofSeconds(20));
        phase = Phase.REPLAYING;
    }

    private void awaitReplay() {
        if (!succeeded("box2d-native-replay-execute")) {
            return;
        }
        var result = fixture.runtime().replays().execute(
                "box2d-native-replay", "box2d-native-replay-execute",
                Duration.ofSeconds(20)).result().orElseThrow();
        if (result.status() != DeterminismStatus.EQUAL) {
            throw new IllegalStateException("native replay did not compare equal: " + result);
        }
        writeEvidence(playerPositionStatus, playerContactStatus, determinismStatus,
                result.status(), result.bounds().completedTicks(), frameCorrelated);
        phase = Phase.COMPLETE;
        Gdx.app.exit();
    }

    private boolean succeeded(String requestId) {
        var status = fixture.runtime().commands().orElseThrow().status(requestId).status();
        if (status.isEmpty()) {
            return false;
        }
        CommandState state = status.orElseThrow().state();
        if (state == CommandState.FAILED || state == CommandState.TIMED_OUT
                || state == CommandState.CANCELLED) {
            throw new IllegalStateException(
                    "native fixture command did not succeed: " + status.orElseThrow());
        }
        return state == CommandState.SUCCEEDED;
    }

    private void writeEvidence(AssertionStatus position, AssertionStatus contact,
            DeterminismStatus determinism, DeterminismStatus replay,
            int replayTicks, boolean frameCorrelated) {
        String evidence = "session=" + fixture.runtime().sessionId().value()
                + "\nrunningTicks=" + runningTicks
                + "\ncontrolledTicks=" + CONTROLLED_TICKS
                + "\npresentationRenders=" + fixture.renderCount()
                + "\nposition=" + position
                + "\ncontact=" + contact
                + "\ndeterminism=" + determinism
                + "\nreplay=" + replay
                + "\nreplayTicks=" + replayTicks
                + "\nframeCorrelated=" + frameCorrelated
                + "\ndispatchThreadCorrect=" + dispatchThreadCorrect
                + "\n";
        try {
            Files.writeString(evidencePath, evidence, StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("could not write Box2D fixture evidence", failure);
        }
    }

    private ReplayCaptureSpec replaySpec() {
        var template = Box2dDeterminism.builder("main", settings,
                        Box2dConformanceSimulation.PLAYER_MOVEMENT, 7,
                        RuntimeValues.object(), 2, CONTROLLED_TICKS)
                .body("player", "position", "linearVelocity")
                .activeContacts()
                .build();
        RecordingSpec recording = new RecordingSpec(
                "box2d-native-replay", "2.5", List.of(),
                Optional.of(Box2dConformanceSimulation.PLAYER_MOVEMENT), Optional.empty(),
                OptionalLong.of(7), RuntimeValues.object(), true);
        return new ReplayCaptureSpec(recording, template.execution().profile(),
                template.configurationRequirements(), template.evidenceRequirements(),
                template.eventTypes());
    }

    private enum Phase {
        RUNNING, PAUSING, RESETTING, REPLAY_STARTING, INPUTTING, ADVANCING,
        REPLAY_STOPPING, DETERMINISM, REPLAYING, COMPLETE
    }
}
