package io.github.teemuki8.libgdx.agent.runtime.examples;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.ApplicationCommandDispatcher;
import io.github.teemuki8.libgdx.agent.runtime.core.AssertionStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointHandle;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointProvider;
import io.github.teemuki8.libgdx.agent.runtime.core.CommandState;
import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismProfile;
import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.EventSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepSimulationConfiguration;
import io.github.teemuki8.libgdx.agent.runtime.core.FixedStepUpdateReport;
import io.github.teemuki8.libgdx.agent.runtime.core.InputSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingCapabilityVersion;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingEntry;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingInputEntry;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingTickEntry;
import io.github.teemuki8.libgdx.agent.runtime.core.ReplayCaptureSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertion;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertionScope;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationAssertionSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationDeterminismInput;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationDeterminismSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SnapshotComparisonScope;
import io.github.teemuki8.libgdx.agent.runtime.libgdx.LibGdxAgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.libgdx.LibGdxFixedStepSimulation;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Complete backend-neutral agent workflow with application-owned dispatch and fixed ticks. */
public final class ControlledWorkflowExample implements AutoCloseable {
    /** Stable session ID used by the example and its MCP transcript. */
    public static final SessionId SESSION_ID = SessionId.of("controlled-workflow-example");
    /** Authoritative configured step. */
    public static final long FIXED_STEP_NANOS = 10_000_000L;

    private static final EntityId PLAYER_ID = EntityId.of("player");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ArrayDeque<Runnable> applicationQueue;
    private final AgentRuntime runtime;
    private final LibGdxFixedStepSimulation simulation;
    private double positionX;
    private double velocityX;
    private boolean closed;

    private ControlledWorkflowExample(ApplicationCommandDispatcher dispatcher,
            ArrayDeque<Runnable> applicationQueue) {
        this.applicationQueue = applicationQueue;
        runtime = LibGdxAgentRuntime.builder()
                .captureThread(Thread.currentThread())
                .sessionId(SESSION_ID)
                .commandDispatcher(Objects.requireNonNull(dispatcher, "dispatcher"))
                .build();
        runtime.entities().register(PLAYER_ID, EntityType.of("player"), () -> "Player",
                inspector -> inspector
                        .property("position", () -> RuntimeValues.vector2(positionX, 0))
                        .property("velocity", () -> RuntimeValues.vector2(velocityX, 0)));
        runtime.inputs().register(InputSpec.builder("set-velocity")
                .description("Sets horizontal velocity before an exact simulation tick")
                .requiredDecimal("velocityX")
                .handler(parameters -> velocityX = parameters
                        .requiredDecimal("velocityX").doubleValue())
                .build());
        runtime.checkpoints().register(new CheckpointProvider() {
            @Override public CheckpointHandle create() {
                return new StateCheckpoint(positionX, velocityX);
            }

            @Override public void restore(CheckpointHandle handle) {
                StateCheckpoint checkpoint = (StateCheckpoint) handle;
                positionX = checkpoint.positionX();
                velocityX = checkpoint.velocityX();
                runtime.fixedStepSimulation().clearAccumulator();
            }

            @Override public void dispose(CheckpointHandle handle) {
                // Immutable application state owns no resource.
            }
        });
        runtime.scenarios().register("walk", "Restores the deterministic walking state",
                context -> {
                    positionX = 0;
                    velocityX = 0;
                    runtime.fixedStepSimulation().clearAccumulator();
                });
        simulation = LibGdxFixedStepSimulation.acknowledged(runtime,
                FixedStepSimulationConfiguration.developmentDefaults(FIXED_STEP_NANOS), tick -> {
                    positionX += velocityX * tick.fixedStepSeconds();
                    if (velocityX != 0) {
                        runtime.emit(EventSpec.type("player.moved")
                                .subject(PLAYER_ID)
                                .attribute("position",
                                        RuntimeValues.vector2(positionX, 0)));
                    }
                    return tick.fixedStepNanos();
                });
        runtime.start();
    }

    /** Creates the copyable example with an explicit inspectable application queue. */
    public static ControlledWorkflowExample createQueued() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        return new ControlledWorkflowExample(queue::addLast, queue);
    }

    /** Creates the example with an application-supplied capture-thread dispatcher. */
    public static ControlledWorkflowExample create(ApplicationCommandDispatcher dispatcher) {
        return new ControlledWorkflowExample(dispatcher, null);
    }

    /** Returns the started runtime. */
    public AgentRuntime runtime() {
        return runtime;
    }

    /** Returns the number of queued application commands in the inspectable queue variant. */
    public int queuedCommands() {
        return queue().size();
    }

    /** Runs one queued command on the application/capture thread. */
    public void drainOne() {
        queue().removeFirst().run();
    }

    /** Drains all currently queued commands on the application/capture thread. */
    public void drainAll() {
        while (!queue().isEmpty()) {
            drainOne();
        }
    }

    /** Advances ordinary render time through the canonical accumulator on the capture thread. */
    public FixedStepUpdateReport updateNanos(long renderDeltaNanos) {
        return simulation.updateNanos(renderDeltaNanos);
    }

    /** Executes the complete queued Java workflow and returns bounded summary evidence. */
    public WorkflowResult runWorkflow() {
        requireQueuedVariant();
        var reset = runtime.scenarios().reset("walk", "workflow-reset", TIMEOUT);
        drainAll();
        reset = runtime.scenarios().reset("walk", "workflow-reset", TIMEOUT);

        runtime.controls().control(true, "workflow-pause", TIMEOUT);
        drainAll();
        runtime.controls().control(true, "workflow-pause", TIMEOUT);
        runtime.checkpoints().create(
                "start", "Before scheduled movement", "workflow-checkpoint-create", TIMEOUT);
        drainAll();

        ReplayCaptureSpec replayCaptureSpec = replayCaptureSpec();
        runtime.replays().start(
                replayCaptureSpec, "workflow-replay-capture-start", TIMEOUT);
        drainAll();
        var replayCapture = runtime.replays().start(
                replayCaptureSpec, "workflow-replay-capture-start", TIMEOUT);
        var epoch = replayCapture.baselineExecutionEpochId().orElseThrow();

        RuntimeValue.ObjectValue velocity = velocityParameters(2);
        long targetTick = runtime.controls().currentTick() + 1;
        runtime.inputs().inject("set-velocity", "workflow-input", velocity,
                OptionalLong.of(targetTick), TIMEOUT);
        drainAll();
        runtime.controls().advanceFixed("workflow-advance", 60, TIMEOUT);
        drainAll();
        var advanced = runtime.controls().advanceFixed("workflow-advance", 60, TIMEOUT);
        var injection = runtime.inputs().inject("set-velocity", "workflow-input", velocity,
                OptionalLong.of(targetTick), TIMEOUT);

        SimulationAssertionResultPair assertions = assertions(epoch);
        runtime.recordings().stop(
                "walk-recording", "workflow-recording-stop", TIMEOUT);
        drainAll();
        List<RecordingEntry> entries = recordingEntries("walk-recording");

        runtime.replays().execute(
                "walk-recording", "workflow-replay-execute", TIMEOUT);
        drainAll();
        var replay = runtime.replays().execute(
                "walk-recording", "workflow-replay-execute", TIMEOUT)
                .result().orElseThrow();

        SimulationDeterminismSpec determinismSpec = determinismSpec(velocity);
        runtime.determinism().checkSimulation(
                determinismSpec, "workflow-determinism", TIMEOUT);
        drainAll();
        var determinism = runtime.determinism().checkSimulation(
                determinismSpec, "workflow-determinism", TIMEOUT)
                .result().orElseThrow();

        runtime.checkpoints().restore("start", "workflow-checkpoint-restore", TIMEOUT);
        drainAll();
        runtime.checkpoints().restore("start", "workflow-checkpoint-restore", TIMEOUT);
        RuntimeValue.Vector2Value restored = (RuntimeValue.Vector2Value) runtime.entity(PLAYER_ID)
                .orElseThrow().property("position").orElseThrow();
        boolean correlated = runtime.simulation().ticks(
                new io.github.teemuki8.libgdx.agent.runtime.core.SimulationTickQuery(
                        epoch, 60, 60, 1)).ticks().getFirst().resultingFrameId().isPresent();

        return new WorkflowResult(
                reset.command().status().orElseThrow().state(), advanced.completedTicks(),
                injection.actualTick().orElseThrow(), assertions.expected().name(),
                assertions.wrong().name(),
                Math.toIntExact(entries.stream().filter(RecordingInputEntry.class::isInstance)
                        .count()),
                Math.toIntExact(entries.stream().filter(RecordingTickEntry.class::isInstance)
                        .count()), replay.status(), replay.bounds().completedTicks(),
                determinism.status(), restored, correlated);
    }

    /** Runs the queued example and writes only human-readable diagnostics to stderr. */
    public static void main(String[] args) {
        if (args.length != 0) {
            throw new IllegalArgumentException("controlled workflow accepts no arguments");
        }
        try (ControlledWorkflowExample example = createQueued()) {
            System.err.println(example.runWorkflow());
        }
    }

    @Override public void close() {
        if (!closed) {
            runtime.close();
            closed = true;
        }
    }

    private SimulationAssertionResultPair assertions(
            io.github.teemuki8.libgdx.agent.runtime.core.ExecutionEpochId epoch) {
        SimulationAssertionScope scope = new SimulationAssertionScope(epoch, 60, 60, 8);
        AssertionStatus expected = runtime.assertions().evaluateSimulation(
                positionAssertion(1.2), scope).status();
        AssertionStatus wrong = runtime.assertions().evaluateSimulation(
                positionAssertion(9), scope).status();
        return new SimulationAssertionResultPair(expected, wrong);
    }

    private static SimulationAssertionSpec positionAssertion(double expectedX) {
        return SimulationAssertionSpec.of(new SimulationAssertion.VectorApproximatelyEquals(
                PLAYER_ID, "position", RuntimeValues.vector2(expectedX, 0),
                new BigDecimal("0.0001"),
                SimulationAssertion.VectorToleranceMode.COMPONENT));
    }

    private static SimulationDeterminismSpec determinismSpec(
            RuntimeValue.ObjectValue velocityParameters) {
        SnapshotComparisonScope scope = new SnapshotComparisonScope(
                List.of(PLAYER_ID), List.of("position", "velocity"), List.of(), false, false);
        DeterminismSpec execution = new DeterminismSpec(
                "walk", 7, RuntimeValues.object(), 2, 60, FIXED_STEP_NANOS,
                new DeterminismProfile(scope, false));
        return new SimulationDeterminismSpec(execution,
                List.of(new SimulationDeterminismInput(
                        1, "set-velocity", velocityParameters)),
                List.of(), List.of(), List.of());
    }

    private static ReplayCaptureSpec replayCaptureSpec() {
        SimulationDeterminismSpec template = determinismSpec(velocityParameters(2));
        RecordingSpec recording = new RecordingSpec(
                "walk-recording", "2.5",
                List.of(new RecordingCapabilityVersion("fixed-step-simulation", "2.2")),
                Optional.of("walk"), Optional.empty(), OptionalLong.of(7),
                RuntimeValues.object(), true);
        return new ReplayCaptureSpec(recording, template.execution().profile(),
                template.configurationRequirements(), template.evidenceRequirements(),
                template.eventTypes());
    }

    private List<RecordingEntry> recordingEntries(String recordingId) {
        ArrayList<RecordingEntry> entries = new ArrayList<>();
        int offset = 0;
        boolean more;
        do {
            var chunk = runtime.recordings().get(recordingId, offset,
                    runtime.recordings().limits().chunkItems());
            entries.addAll(chunk.entries());
            offset = chunk.nextOffset();
            more = chunk.hasMore();
        } while (more);
        return List.copyOf(entries);
    }

    private static RuntimeValue.ObjectValue velocityParameters(double velocity) {
        return RuntimeValues.object(RuntimeValues.field(
                "velocityX", RuntimeValues.decimal(Double.toString(velocity))));
    }

    private ArrayDeque<Runnable> queue() {
        requireQueuedVariant();
        return applicationQueue;
    }

    private void requireQueuedVariant() {
        if (applicationQueue == null) {
            throw new IllegalStateException("the external-dispatch variant has no local queue");
        }
    }

    /** Immutable result of the complete controlled workflow. */
    public record WorkflowResult(CommandState resetState, int completedTicks,
            long firstAppliedEpochTick, String expectedPosition, String wrongPosition,
            int recordedInputs, int recordedTicks, DeterminismStatus replay, int replayedTicks,
            DeterminismStatus determinism,
            RuntimeValue.Vector2Value restoredPosition, boolean tickFrameCorrelated) {
        /** Validates required immutable evidence. */
        public WorkflowResult {
            Objects.requireNonNull(resetState, "resetState");
            Objects.requireNonNull(expectedPosition, "expectedPosition");
            Objects.requireNonNull(wrongPosition, "wrongPosition");
            Objects.requireNonNull(replay, "replay");
            Objects.requireNonNull(determinism, "determinism");
            Objects.requireNonNull(restoredPosition, "restoredPosition");
        }
    }

    private record StateCheckpoint(double positionX, double velocityX)
            implements CheckpointHandle {}

    private record SimulationAssertionResultPair(
            AssertionStatus expected, AssertionStatus wrong) {}
}
