package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.physics.box2d.Box2D;
import com.badlogic.gdx.utils.GdxNativesLoader;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dAdapterLimits;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dAssertions;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dContactLimits;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dDeterminism;
import io.github.teemuki8.libgdx.agent.runtime.box2d.Box2dVector;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntimeException;
import io.github.teemuki8.libgdx.agent.runtime.core.AssertionStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.CommandState;
import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntitySnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.EventQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.ExecutionEpochId;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameRange;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepUpdateDiagnostic;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingInputEntry;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingTickEntry;
import io.github.teemuki8.libgdx.agent.runtime.core.ReplayCaptureSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.ReplayPhase;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeErrorCode;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeEvent;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertion;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertionScope;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertionSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTickQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationTickOutcome;
import io.github.teemuki8.libgdx.agent.runtime.mcp.RuntimeToolHandler;
import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolVersion;
import io.github.teemuki8.libgdx.agent.runtime.protocol.PublishedRuntime;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeCommand;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRequest;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeResponse;
import io.modelcontextprotocol.spec.McpSchema;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Box2dConformanceFixtureTest {
    @BeforeAll
    static void initializeNativeBox2d() {
        GdxNativesLoader.load();
        Box2D.init();
    }

    @Test
    void ballDropUsesScenarioFixedTicksContactsAndRenderingIndependentEvidence() {
        try (Box2dConformanceSimulation fixture =
                new Box2dConformanceSimulation(Runnable::run)) {
            AgentRuntime runtime = fixture.runtime();
            var reset = runtime.scenarios().reset(
                    "ball-drop", "ball-drop-reset", Duration.ofSeconds(2));
            assertEquals(CommandState.SUCCEEDED,
                    reset.command().status().orElseThrow().state());
            ExecutionEpochId epoch = reset.executionEpochId().orElseThrow();
            assertEquals(1, epoch.value());

            runtime.controls().control(true, "ball-drop-pause", Duration.ofSeconds(2));
            var advance = runtime.controls().advanceFixed(
                    "ball-drop-advance", 240, Duration.ofSeconds(5));
            assertEquals(240, advance.completedTicks());
            assertEquals(240, runtime.simulation().state().completedEpochTicks());
            assertEquals(0, fixture.renderCount());

            var finalTick = runtime.simulation().ticks(new SimulationTickQuery(
                    epoch, 240, 240, 1)).ticks().getFirst();
            assertTrue(finalTick.resultingFrameId().isPresent());
            assertEquals(finalTick.resultingFrameId().orElseThrow(),
                    runtime.latestFrame().orElseThrow().frameId());

            var position = Box2dAssertions.bodyPositionApproximately(
                    "ball", new Box2dVector(0, 1), 0.03,
                    SimulationAssertion.VectorToleranceMode.COMPONENT);
            assertEquals(AssertionStatus.PASS, runtime.assertions().evaluateSimulation(
                    position, new SimulationAssertionScope(epoch, 240, 240, 8)).status());
            assertEquals(AssertionStatus.PASS, runtime.assertions().evaluateSimulation(
                    Box2dAssertions.bodySleeping("ball"),
                    new SimulationAssertionScope(epoch, 240, 240, 8)).status());

            var ball = new Box2dAssertions.ContactEndpoint("ball", "ball-shape", 0);
            var ground = new Box2dAssertions.ContactEndpoint("ground", "ground-shape", 0);
            assertEquals(AssertionStatus.PASS, runtime.assertions().evaluateSimulation(
                    Box2dAssertions.contactOccurred("main", ball, ground),
                    new SimulationAssertionScope(epoch, 1, 240, 8)).status());
            assertFalse(runtime.entity(EntityId.of("box2d.contacts.main")).isEmpty());
        }
    }

    @Test
    void collisionAndScheduledPlayerInputProduceStructuredNativeEvidence() {
        try (Box2dConformanceSimulation fixture =
                new Box2dConformanceSimulation(Runnable::run)) {
            AgentRuntime runtime = fixture.runtime();
            var collisionReset = runtime.scenarios().reset(
                    "collision", "collision-reset", Duration.ofSeconds(2));
            ExecutionEpochId collisionEpoch = collisionReset.executionEpochId().orElseThrow();
            runtime.controls().control(true, "collision-pause", Duration.ofSeconds(2));
            assertEquals(90, runtime.controls().advanceFixed(
                    "collision-advance", 90, Duration.ofSeconds(5)).completedTicks());

            var left = new Box2dAssertions.ContactEndpoint(
                    "collision-left", "collision-left-shape", 0);
            var right = new Box2dAssertions.ContactEndpoint(
                    "collision-right", "collision-right-shape", 0);
            assertEquals(AssertionStatus.PASS, runtime.assertions().evaluateSimulation(
                    Box2dAssertions.contactOccurred("main", left, right),
                    new SimulationAssertionScope(collisionEpoch, 1, 90, 8)).status());
            RuntimeEvent postSolve = runtime.events(new EventQuery(
                    FrameRange.of(collisionReset.baselineFrameId().orElseThrow().value(),
                            runtime.latestFrame().orElseThrow().frameId().value()),
                    Optional.of("box2d.contact.postSolve"), false,
                    Optional.empty(), Optional.empty(), 100)).items().stream()
                    .findFirst().orElseThrow();
            assertInstanceOf(RuntimeValue.Vector2Value.class,
                    attribute(postSolve, "normal"));
            assertFalse(list(attribute(postSolve, "points")).values().isEmpty());
            assertFalse(list(attribute(postSolve, "impulses")).values().isEmpty());

            EntitySnapshot contacts = runtime.entity(
                    EntityId.of("box2d.contacts.main")).orElseThrow();
            assertEquals(RuntimeValues.bool(true),
                    contacts.property("complete").orElseThrow());
            assertFalse(list(contacts.property("activeContacts").orElseThrow())
                    .values().isEmpty());
            assertEquals(RuntimeValues.enumValue("DISTANCE"), runtime.entity(
                    EntityId.of("box2d.joint.static-link")).orElseThrow()
                    .property("jointType").orElseThrow());
            assertEquals(RuntimeValues.integer(90), runtime.entity(
                    EntityId.of("fixture.post-physics")).orElseThrow()
                    .property("completedTicks").orElseThrow());

            runtime.scenarios().reset(
                    "player-movement", "player-reset", Duration.ofSeconds(2));
            ExecutionEpochId playerEpoch = runtime.currentEpoch();
            long targetTick = runtime.controls().currentTick() + 1;
            var parameters = RuntimeValues.object(RuntimeValues.field(
                    "velocityX", RuntimeValues.decimal("4")));
            var input = runtime.inputs().inject("move-player", "move-player-1", parameters,
                    OptionalLong.of(targetTick), Duration.ofSeconds(2));
            assertEquals(CommandState.SUCCEEDED,
                    input.command().status().orElseThrow().state());
            assertEquals(90, runtime.controls().advanceFixed(
                    "player-advance", 90, Duration.ofSeconds(5)).completedTicks());
            var executed = runtime.inputs().inject("move-player", "move-player-1", parameters,
                    OptionalLong.of(targetTick), Duration.ofSeconds(2));
            assertEquals(OptionalLong.of(targetTick), executed.actualTick());

            assertEquals(AssertionStatus.PASS, runtime.assertions().evaluateSimulation(
                    Box2dAssertions.bodyPositionApproximately(
                            "player", new Box2dVector(2, 1), 0.04,
                            SimulationAssertion.VectorToleranceMode.COMPONENT),
                    new SimulationAssertionScope(playerEpoch, 90, 90, 8)).status());
            var player = new Box2dAssertions.ContactEndpoint(
                    "player", "player-shape", 0);
            var wall = new Box2dAssertions.ContactEndpoint("wall", "wall-shape", 0);
            assertEquals(AssertionStatus.PASS, runtime.assertions().evaluateSimulation(
                    Box2dAssertions.contactOccurred("main", player, wall),
                    new SimulationAssertionScope(playerEpoch, 1, 90, 8)).status());
            fixture.recordRender();
            assertEquals(1, fixture.renderCount());
        }
    }

    @Test
    void checkpointRestoreAndRecordingUseTheSameScheduledTickPath() {
        try (Box2dConformanceSimulation fixture =
                new Box2dConformanceSimulation(Runnable::run)) {
            AgentRuntime runtime = fixture.runtime();
            runtime.scenarios().reset(
                    "player-movement", "checkpoint-player-reset", Duration.ofSeconds(2));
            runtime.controls().control(true, "checkpoint-pause", Duration.ofSeconds(2));
            runtime.checkpoints().create(
                    "player-start", "before scheduled input", "checkpoint-create",
                    Duration.ofSeconds(2));
            runtime.recordings().start(new RecordingSpec(
                    "player-recording", "2.4", List.of(),
                    Optional.of("player-movement"), Optional.of("player-start"),
                    OptionalLong.of(7), RuntimeValues.object(), false),
                    "recording-start", Duration.ofSeconds(2));

            long scheduledTick = runtime.controls().currentTick() + 1;
            runtime.inputs().inject("move-player", "recorded-player-input",
                    RuntimeValues.object(RuntimeValues.field(
                            "velocityX", RuntimeValues.decimal("4"))),
                    OptionalLong.of(scheduledTick), Duration.ofSeconds(2));
            runtime.controls().advanceFixed(
                    "recorded-player-advance", 10, Duration.ofSeconds(5));
            runtime.recordings().stop(
                    "player-recording", "recording-stop", Duration.ofSeconds(2));
            assertTrue(position(runtime, "player").x().value().doubleValue() > 0);

            var restored = runtime.checkpoints().restore(
                    "player-start", "checkpoint-restore", Duration.ofSeconds(2));
            assertEquals(CommandState.SUCCEEDED,
                    restored.command().status().orElseThrow().state());
            assertEquals(0.0, position(runtime, "player").x().value().doubleValue(), 0.0001);
            assertEquals(RuntimeValues.integer(0), runtime.entity(
                    EntityId.of("fixture.post-physics")).orElseThrow()
                    .property("completedTicks").orElseThrow());

            var recording = runtime.recordings().get("player-recording", 0, 64);
            assertTrue(recording.entries().stream().anyMatch(RecordingInputEntry.class::isInstance));
            assertTrue(recording.entries().stream().anyMatch(RecordingTickEntry.class::isInstance));
        }
    }

    @Test
    void scenarioReplayExecutesOneHundredTwentyNativeTicksWithExactEvidence() {
        try (Box2dConformanceSimulation fixture =
                new Box2dConformanceSimulation(Runnable::run)) {
            AgentRuntime runtime = fixture.runtime();
            runtime.controls().control(true, "replay-scenario-pause", Duration.ofSeconds(2));
            ReplayCaptureSpec spec = replaySpec(
                    "native-scenario-replay", Optional.of("player-movement"), Optional.empty());

            var started = runtime.replays().start(
                    spec, "start-native-scenario-replay", Duration.ofSeconds(5));
            assertEquals(CommandState.SUCCEEDED,
                    started.command().status().orElseThrow().state());
            ExecutionEpochId referenceEpoch = started.baselineExecutionEpochId().orElseThrow();
            schedulePlayerInputAndAdvance(runtime, "scenario", 120);
            runtime.recordings().stop("native-scenario-replay",
                    "stop-native-scenario-replay", Duration.ofSeconds(5));
            var referenceTick = runtime.simulation().ticks(new SimulationTickQuery(
                    referenceEpoch, 120, 120, 1)).ticks().getFirst();
            var referenceFrame = runtime.frame(
                    referenceTick.resultingFrameId().orElseThrow()).orElseThrow();
            EntitySnapshot referencePlayer = referenceFrame.entity(
                    EntityId.of("box2d.body.player")).orElseThrow();
            EntitySnapshot referenceContacts = referenceFrame.entity(
                    EntityId.of("box2d.contacts.main")).orElseThrow();

            var result = runtime.replays().execute(
                    "native-scenario-replay", "execute-native-scenario-replay",
                    Duration.ofSeconds(20)).result().orElseThrow();

            assertEquals(DeterminismStatus.EQUAL, result.status(), result::toString);
            assertEquals(120, result.bounds().requestedTicks());
            assertEquals(120, result.bounds().completedTicks());
            assertEquals(1, result.bounds().recordedInputs());
            assertTrue(result.divergence().isEmpty());
            assertReplayFramesMatch(runtime, referencePlayer, referenceContacts);
        }
    }

    @Test
    void checkpointReplayExecutesTheSameActualNativePath() {
        try (Box2dConformanceSimulation fixture =
                new Box2dConformanceSimulation(Runnable::run)) {
            AgentRuntime runtime = fixture.runtime();
            runtime.scenarios().reset("player-movement",
                    "checkpoint-replay-reset", Duration.ofSeconds(2));
            runtime.controls().control(true, "checkpoint-replay-pause", Duration.ofSeconds(2));
            runtime.checkpoints().create("native-player-origin", "native replay origin",
                    "create-native-player-origin", Duration.ofSeconds(2));
            ReplayCaptureSpec spec = replaySpec(
                    "native-checkpoint-replay", Optional.empty(),
                    Optional.of("native-player-origin"));

            var started = runtime.replays().start(
                    spec, "start-native-checkpoint-replay", Duration.ofSeconds(5));
            assertEquals(CommandState.SUCCEEDED,
                    started.command().status().orElseThrow().state());
            schedulePlayerInputAndAdvance(runtime, "checkpoint", 120);
            runtime.recordings().stop("native-checkpoint-replay",
                    "stop-native-checkpoint-replay", Duration.ofSeconds(5));

            var result = runtime.replays().execute(
                    "native-checkpoint-replay", "execute-native-checkpoint-replay",
                    Duration.ofSeconds(20)).result().orElseThrow();

            assertEquals(DeterminismStatus.EQUAL, result.status(), result::toString);
            assertEquals(120, result.bounds().completedTicks());
            assertEquals(RuntimeValues.integer(120), runtime.entity(
                    EntityId.of("fixture.post-physics")).orElseThrow()
                    .property("completedTicks").orElseThrow());
        }
    }

    @Test
    void alteredReplayInputStopsAtTheFirstActualNativeDifference() {
        try (Box2dConformanceSimulation fixture = new Box2dConformanceSimulation(
                Runnable::run, Box2dConformanceSimulation.FIXED_STEP_NANOS,
                null, Box2dAdapterLimits.developmentDefaults(),
                Box2dContactLimits.developmentDefaults(), true)) {
            AgentRuntime runtime = fixture.runtime();
            runtime.controls().control(true, "divergent-replay-pause", Duration.ofSeconds(2));
            runtime.replays().start(replaySpec(
                            "native-divergent-replay", Optional.of("player-movement"),
                            Optional.empty()),
                    "start-native-divergent-replay", Duration.ofSeconds(5));
            schedulePlayerInputAndAdvance(runtime, "divergent", 120);
            runtime.recordings().stop("native-divergent-replay",
                    "stop-native-divergent-replay", Duration.ofSeconds(5));

            var result = runtime.replays().execute(
                    "native-divergent-replay", "execute-native-divergent-replay",
                    Duration.ofSeconds(20)).result().orElseThrow();

            assertEquals(DeterminismStatus.DIVERGED, result.status());
            var divergence = result.divergence().orElseThrow();
            assertEquals(ReplayPhase.SIMULATION_TICK, divergence.phase());
            assertEquals(1, divergence.epochTick().orElseThrow());
            assertTrue(divergence.referenceSimulationTickId().isPresent());
            assertTrue(divergence.replaySimulationTickId().isPresent());
            assertFalse(divergence.referenceExecutionEpochId()
                    .equals(divergence.replayExecutionEpochId()));
            assertFalse(divergence.referenceFrameId().equals(divergence.replayFrameId()));
            assertTrue(divergence.difference().fact().orElseThrow()
                    .contains("linearVelocity"));
            assertEquals(120, result.bounds().requestedTicks());
            assertEquals(0, result.bounds().completedTicks());
            assertEquals(1, runtime.simulation().state().completedEpochTicks());
            assertTrue(runtime.simulation().ticks(new SimulationTickQuery(
                    runtime.currentEpoch(), 2, 2, 1)).ticks().isEmpty());
            assertFalse(result.message().contains("caused"));
        }
    }

    @Test
    void identicalActualNativeRunsCompareSelectedTickEvidenceEqual() {
        try (Box2dConformanceSimulation fixture =
                new Box2dConformanceSimulation(Runnable::run)) {
            var settings = new Box2dDeterminism.WorldSettings(
                    Box2dConformanceSimulation.FIXED_STEP_NANOS,
                    new Box2dVector(0, 0), 8, 3, true, true, true);
            var spec = Box2dDeterminism.builder(
                            "main", settings, "player-movement", 7,
                            RuntimeValues.object(), 2, 90)
                    .body("player", "position", "linearVelocity")
                    .contactEvents()
                    .input(1, "move-player", RuntimeValues.object(RuntimeValues.field(
                            "velocityX", RuntimeValues.decimal("4"))))
                    .build();

            fixture.runtime().scenarios().reset(
                    "player-movement", "native-player-baseline", Duration.ofSeconds(2));

            var operation = fixture.runtime().determinism().checkSimulation(
                    spec, "native-player-equal", Duration.ofSeconds(10));
            assertEquals(CommandState.SUCCEEDED,
                    operation.command().status().orElseThrow().state());
            var result = operation.result().orElseThrow();
            assertEquals(DeterminismStatus.EQUAL, result.status(), result::toString);
            assertEquals(2, result.bounds().completedRepeats());
            assertTrue(result.divergence().isEmpty());
            assertFalse(result.message().contains("whole-program determinism is proven"));
            assertEquals(RuntimeValues.integer(90), fixture.runtime().entity(
                    EntityId.of("fixture.post-physics")).orElseThrow()
                    .property("completedTicks").orElseThrow());
        }
    }

    @Test
    void actualNativeFixtureIsInspectableThroughProtocolAndMcp() {
        try (Box2dConformanceSimulation fixture =
                new Box2dConformanceSimulation(Runnable::run)) {
            AgentRuntime runtime = fixture.runtime();
            var reset = runtime.scenarios().reset(
                    "ball-drop", "transport-ball-reset", Duration.ofSeconds(2));
            ExecutionEpochId epoch = reset.executionEpochId().orElseThrow();
            runtime.controls().control(true, "transport-pause", Duration.ofSeconds(2));
            runtime.controls().advanceFixed(
                    "transport-ball-advance", 240, Duration.ofSeconds(5));
            var position = Box2dAssertions.bodyPositionApproximately(
                    "ball", new Box2dVector(0, 1), 0.03,
                    SimulationAssertion.VectorToleranceMode.COMPONENT);
            RuntimeRegistry registry = new RuntimeRegistry();

            try (PublishedRuntime publication = registry.publish(runtime);
                    RuntimeToolHandler handler =
                            new RuntimeToolHandler(new RuntimeProtocolService(registry))) {
                assertEquals(runtime.sessionId(), publication.sessionId());
                RuntimeResponse.Result.SimulationAssertion asserted = assertInstanceOf(
                        RuntimeResponse.Result.SimulationAssertion.class,
                        assertInstanceOf(RuntimeResponse.Success.class,
                                new RuntimeProtocolService(registry).execute(new RuntimeRequest(
                                        ProtocolVersion.V2_3, "native-position-assert",
                                        runtime.sessionId().value(),
                                        new RuntimeCommand.SimulationAssert(
                                                position.assertion(),
                                                position.evidenceRequirements(),
                                                epoch.value(), 240, 240, 8))))
                                .result());
                assertEquals(AssertionStatus.PASS, asserted.result().status());

                var settings = new Box2dDeterminism.WorldSettings(
                        Box2dConformanceSimulation.FIXED_STEP_NANOS,
                        new Box2dVector(0, -10), 8, 3, true, true, true);
                var spec = Box2dDeterminism.builder("main", settings, "ball-drop", 7,
                                RuntimeValues.object(), 2, 60)
                        .body("ball", "position", "linearVelocity")
                        .contactEvents().build();
                RuntimeResponse.Result.SimulationDeterminism compared = assertInstanceOf(
                        RuntimeResponse.Result.SimulationDeterminism.class,
                        assertInstanceOf(RuntimeResponse.Success.class,
                                new RuntimeProtocolService(registry).execute(new RuntimeRequest(
                                        ProtocolVersion.V2_4, "native-determinism",
                                        runtime.sessionId().value(),
                                        new RuntimeCommand.SimulationDeterminismCheck(
                                                "native-determinism", spec,
                                                Duration.ofSeconds(10).toNanos()))))
                                .result());
                assertEquals(DeterminismStatus.EQUAL,
                        compared.operation().result().orElseThrow().status());

                McpSchema.CallToolResult inspected = handler.handle(
                        McpSchema.CallToolRequest.builder("runtime_entity")
                                .arguments(Map.of(
                                        "sessionId", runtime.sessionId().value(),
                                        "entityId", "box2d.body.ball"))
                                .build()).block(Duration.ofSeconds(5));
                assertFalse(inspected.isError());
                assertTrue(inspected.structuredContent().toString().contains("position"));

                McpSchema.CallToolResult mcpAssertion = handler.handle(
                        McpSchema.CallToolRequest.builder("runtime_simulation_assert")
                                .arguments(Map.of(
                                        "sessionId", runtime.sessionId().value(),
                                        "executionEpochId", epoch.value(),
                                        "fromEpochTick", 240,
                                        "toEpochTick", 240,
                                        "evidenceLimit", 8,
                                        "evidenceRequirements", List.of(),
                                        "assertion", Map.of(
                                                "assertionType", "vectorApproximatelyEquals",
                                                "entityId", "box2d.body.ball",
                                                "property", "position",
                                                "expected", Map.of("x", 0, "y", 1),
                                                "absoluteTolerance", 0.03,
                                                "toleranceMode", "COMPONENT")))
                                .build()).block(Duration.ofSeconds(5));
                assertFalse(mcpAssertion.isError(),
                        () -> String.valueOf(mcpAssertion.structuredContent()));
                assertEquals("PASS", ((Map<?, ?>) ((Map<?, ?>)
                        mcpAssertion.structuredContent()).get("result")).get("status"));
            }
        }
    }

    @Test
    void actualNativeFixtureReportsDroppedTimeAndConfigurationMismatchExplicitly() {
        try (Box2dConformanceSimulation fixture =
                new Box2dConformanceSimulation(Runnable::run)) {
            var update = fixture.simulation().update(1.0f);
            assertTrue(update.diagnostics().contains(
                    FixedStepUpdateDiagnostic.RENDER_DELTA_CLAMPED));
            assertTrue(update.diagnostics().contains(
                    FixedStepUpdateDiagnostic.CATCH_UP_TICKS_DROPPED));
            assertTrue(update.diagnostics().contains(
                    FixedStepUpdateDiagnostic.ACCUMULATOR_TIME_DROPPED));
            assertTrue(update.accumulatorLimitDroppedTimeNanos() > 0);
            assertTrue(update.droppedTicks() > 0);

            var wrongSettings = new Box2dDeterminism.WorldSettings(
                    Box2dConformanceSimulation.FIXED_STEP_NANOS,
                    new Box2dVector(0, 0), 8, 3, true, true, true);
            var wrongConfiguration = Box2dDeterminism.builder(
                            "main", wrongSettings, "ball-drop", 7,
                            RuntimeValues.object(), 2, 1)
                    .body("ball", "position").build();
            AgentRuntimeException failure = assertThrows(AgentRuntimeException.class,
                    () -> fixture.runtime().determinism().checkSimulation(
                            wrongConfiguration, "wrong-native-gravity",
                            Duration.ofSeconds(2)));
            assertEquals(RuntimeErrorCode.INVALID_QUERY, failure.code());
            assertTrue(failure.getMessage().contains("box2d.world.main:gravity"));
        }
    }

    @Test
    void executedStepMismatchIsRetainedAsFailedTickEvidence() {
        try (Box2dConformanceSimulation fixture = new Box2dConformanceSimulation(
                Runnable::run, Box2dConformanceSimulation.FIXED_STEP_NANOS * 2,
                null, Box2dAdapterLimits.developmentDefaults(),
                Box2dContactLimits.developmentDefaults(), false)) {
            var update = fixture.simulation().updateNanos(
                    Box2dConformanceSimulation.FIXED_STEP_NANOS);
            assertTrue(update.diagnostics().contains(
                    FixedStepUpdateDiagnostic.EXECUTED_DELTA_MISMATCH));
            assertEquals(1, update.ticksCompleted());
            assertEquals(SimulationTickOutcome.DELTA_MISMATCH,
                    fixture.runtime().simulation().ticks(new SimulationTickQuery(
                            new ExecutionEpochId(0), 1, 1, 1)).ticks().getFirst().outcome());
        }
    }

    @Test
    void scaleShapeContactAndRegistrationFailuresRemainDistinguishable() {
        Box2dAdapterLimits shapeLimits = new Box2dAdapterLimits(
                16, 4_096, 8_192, 2_048, 2, 64, 8);
        try (Box2dConformanceSimulation fixture = new Box2dConformanceSimulation(
                Runnable::run, Box2dConformanceSimulation.FIXED_STEP_NANOS,
                null, shapeLimits, Box2dContactLimits.developmentDefaults(), false)) {
            AgentRuntime runtime = fixture.runtime();
            var reset = runtime.scenarios().reset(
                    "ball-drop", "extent-ball-reset", Duration.ofSeconds(2));
            ExecutionEpochId epoch = reset.executionEpochId().orElseThrow();
            runtime.controls().control(true, "extent-pause", Duration.ofSeconds(2));
            runtime.controls().advanceFixed("extent-advance", 240, Duration.ofSeconds(5));

            RuntimeValue.ListValue shapeDiagnostics = list(runtime.entity(
                    EntityId.of("box2d.fixture.ground-shape")).orElseThrow()
                    .property("diagnostics").orElseThrow());
            assertTrue(shapeDiagnostics.values().contains(
                    RuntimeValues.enumValue("SHAPE_VERTICES_TRUNCATED")));

            var renderExtent = new SimulationAssertionSpec(
                    new SimulationAssertion.VectorInArea(
                            EntityId.of("box2d.body.ball"), "renderPosition",
                            new SimulationAssertion.Area(
                                    BigDecimal.valueOf(-10), BigDecimal.valueOf(-10),
                                    BigDecimal.valueOf(10), BigDecimal.valueOf(10)),
                            SimulationAssertion.AreaRelation.INSIDE,
                            SimulationAssertion.Extent.FINAL), List.of());
            assertEquals(AssertionStatus.FAIL, runtime.assertions().evaluateSimulation(
                    renderExtent,
                    new SimulationAssertionScope(epoch, 240, 240, 8)).status());
        }

        try (Box2dConformanceSimulation fixture = new Box2dConformanceSimulation(
                Runnable::run, Box2dConformanceSimulation.FIXED_STEP_NANOS,
                "ball-shape", Box2dAdapterLimits.developmentDefaults(),
                Box2dContactLimits.developmentDefaults(), false)) {
            AgentRuntime runtime = fixture.runtime();
            var reset = runtime.scenarios().reset(
                    "ball-drop", "unmapped-ball-reset", Duration.ofSeconds(2));
            ExecutionEpochId epoch = reset.executionEpochId().orElseThrow();
            runtime.controls().control(true, "unmapped-pause", Duration.ofSeconds(2));
            runtime.controls().advanceFixed("unmapped-advance", 240, Duration.ofSeconds(5));

            EntitySnapshot contactEvidence = runtime.entity(
                    EntityId.of("box2d.contacts.main")).orElseThrow();
            assertEquals(RuntimeValues.bool(false),
                    contactEvidence.property("complete").orElseThrow());
            assertTrue(list(contactEvidence.property("diagnostics").orElseThrow())
                    .values().stream().anyMatch(value -> RuntimeValues.enumValue(
                            "UNMAPPED_ENDPOINT").equals(field(value, "code"))));

            var ball = new Box2dAssertions.ContactEndpoint("ball", "ball-shape", 0);
            var ground = new Box2dAssertions.ContactEndpoint("ground", "ground-shape", 0);
            var contact = runtime.assertions().evaluateSimulation(
                    Box2dAssertions.contactOccurred("main", ball, ground),
                    new SimulationAssertionScope(epoch, 1, 240, 8));
            assertEquals(AssertionStatus.INCONCLUSIVE, contact.status());
            assertTrue(contact.evidenceIncomplete());
        }

        try (Box2dConformanceSimulation fixture = new Box2dConformanceSimulation(
                Runnable::run, Box2dConformanceSimulation.FIXED_STEP_NANOS,
                null, Box2dAdapterLimits.developmentDefaults(),
                new Box2dContactLimits(1, 1, 1, 1, 1, 8, 1_024, 256), false)) {
            AgentRuntime runtime = fixture.runtime();
            runtime.scenarios().reset(
                    "collision", "truncated-collision-reset", Duration.ofSeconds(2));
            ExecutionEpochId epoch = runtime.currentEpoch();
            runtime.controls().control(true, "truncated-pause", Duration.ofSeconds(2));
            runtime.controls().advanceFixed(
                    "truncated-advance", 90, Duration.ofSeconds(5));
            EntitySnapshot evidence = runtime.entity(
                    EntityId.of("box2d.contacts.main")).orElseThrow();
            assertEquals(RuntimeValues.bool(false), evidence.property("complete").orElseThrow());
            assertTrue(runtime.simulation().ticks(new SimulationTickQuery(
                            epoch, 1, 90, 90)).ticks().stream()
                    .map(tick -> runtime.frame(tick.resultingFrameId().orElseThrow())
                            .orElseThrow().entity(EntityId.of("box2d.contacts.main"))
                            .orElseThrow())
                    .flatMap(snapshot -> list(snapshot.property("diagnostics").orElseThrow())
                            .values().stream())
                    .anyMatch(value -> RuntimeValues.enumValue(
                            "RECORD_LIMIT_REACHED").equals(field(value, "code"))));
        }
    }

    @Test
    void alteredNativeRerunReportsFirstDifferingTickAndProperty() {
        try (Box2dConformanceSimulation fixture = new Box2dConformanceSimulation(
                Runnable::run, Box2dConformanceSimulation.FIXED_STEP_NANOS,
                null, Box2dAdapterLimits.developmentDefaults(),
                Box2dContactLimits.developmentDefaults(), true)) {
            var settings = new Box2dDeterminism.WorldSettings(
                    Box2dConformanceSimulation.FIXED_STEP_NANOS,
                    new Box2dVector(0, 0), 8, 3, true, true, true);
            var spec = Box2dDeterminism.builder(
                            "main", settings, "player-movement", 7,
                            RuntimeValues.object(), 2, 10)
                    .body("player", "position", "linearVelocity")
                    .input(1, "move-player", RuntimeValues.object(RuntimeValues.field(
                            "velocityX", RuntimeValues.decimal("4"))))
                    .build();
            fixture.runtime().scenarios().reset(
                    "player-movement", "divergent-baseline", Duration.ofSeconds(2));

            var result = fixture.runtime().determinism().checkSimulation(
                    spec, "native-player-diverged", Duration.ofSeconds(10))
                    .result().orElseThrow();
            assertEquals(DeterminismStatus.DIVERGED, result.status());
            assertEquals(1, result.divergence().orElseThrow().epochTick());
            assertTrue(result.divergence().orElseThrow().difference().fact()
                    .orElseThrow().contains("linearVelocity"));
        }
    }

    private static RuntimeValue attribute(RuntimeEvent event, String name) {
        return event.attributes().stream().filter(field -> field.name().equals(name))
                .findFirst().orElseThrow().value();
    }

    private static RuntimeValue.ListValue list(RuntimeValue value) {
        return assertInstanceOf(RuntimeValue.ListValue.class, value);
    }

    private static RuntimeValue field(RuntimeValue value, String name) {
        return assertInstanceOf(RuntimeValue.ObjectValue.class, value).fields().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst().orElseThrow().value();
    }

    private static RuntimeValue.Vector2Value position(AgentRuntime runtime, String bodyId) {
        return assertInstanceOf(RuntimeValue.Vector2Value.class, runtime.entity(
                EntityId.of("box2d.body." + bodyId)).orElseThrow()
                .property("position").orElseThrow());
    }

    private static ReplayCaptureSpec replaySpec(String recordingId,
            Optional<String> scenarioId, Optional<String> checkpointId) {
        var settings = new Box2dDeterminism.WorldSettings(
                Box2dConformanceSimulation.FIXED_STEP_NANOS,
                new Box2dVector(0, 0), 8, 3, true, true, true);
        var template = Box2dDeterminism.builder(
                        "main", settings, "player-movement", 7,
                        RuntimeValues.object(), 2, 120)
                .body("player", "position", "linearVelocity")
                .activeContacts()
                .build();
        RecordingSpec recording = new RecordingSpec(recordingId, "2.5", List.of(),
                scenarioId, checkpointId, OptionalLong.of(7), RuntimeValues.object(), true);
        return new ReplayCaptureSpec(recording, template.execution().profile(),
                template.configurationRequirements(), template.evidenceRequirements(),
                template.eventTypes());
    }

    private static void schedulePlayerInputAndAdvance(
            AgentRuntime runtime, String requestPrefix, int ticks) {
        long targetTick = runtime.controls().currentTick() + 1;
        runtime.inputs().inject("move-player", requestPrefix + "-replay-input",
                RuntimeValues.object(RuntimeValues.field(
                        "velocityX", RuntimeValues.decimal("4"))),
                OptionalLong.of(targetTick), Duration.ofSeconds(2));
        runtime.controls().advanceFixed(
                requestPrefix + "-replay-advance", ticks, Duration.ofSeconds(10));
    }

    private static void assertReplayFramesMatch(AgentRuntime runtime,
            EntitySnapshot referencePlayer, EntitySnapshot referenceContacts) {
        ExecutionEpochId replayEpoch = runtime.currentEpoch();
        var replayTicks = runtime.simulation().ticks(new SimulationTickQuery(
                replayEpoch, 1, 120, 120)).ticks();
        assertEquals(120, replayTicks.size());
        for (int index = 0; index < replayTicks.size(); index++) {
            assertEquals(index + 1, replayTicks.get(index).epochTick());
            assertTrue(replayTicks.get(index).resultingFrameId().isPresent());
        }
        var replayFrame = runtime.frame(replayTicks.getLast()
                .resultingFrameId().orElseThrow()).orElseThrow();
        EntitySnapshot replayPlayer = replayFrame.entity(
                EntityId.of("box2d.body.player")).orElseThrow();
        EntitySnapshot replayContacts = replayFrame.entity(
                EntityId.of("box2d.contacts.main")).orElseThrow();
        assertEquals(referencePlayer.property("position"), replayPlayer.property("position"));
        assertEquals(referencePlayer.property("linearVelocity"),
                replayPlayer.property("linearVelocity"));
        assertEquals(referenceContacts.property("activeContacts"),
                replayContacts.property("activeContacts"));
        assertFalse(list(referenceContacts.property("activeContacts").orElseThrow())
                .values().isEmpty());
    }
}
