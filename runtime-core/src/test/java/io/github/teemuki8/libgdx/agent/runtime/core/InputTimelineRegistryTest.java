package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class InputTimelineRegistryTest {
    @Test
    void executesOrderedTransitionsAndEveryExactTickAtMostOnce() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        ArrayList<String> observed = new ArrayList<>();
        AtomicInteger simulationTicks = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-timeline"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(10));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(deltaNanos -> {
                    simulationTicks.incrementAndGet();
                    return deltaNanos;
                })
                .build());
        runtime.inputs().register(InputSpec.builder("button")
                .requiredBoolean("active")
                .handler(parameters -> observed.add(
                        "active:" + parameters.requiredBoolean("active")))
                .build());
        runtime.inputs().register(InputSpec.builder("label")
                .requiredString("label")
                .handler(parameters -> observed.add(
                        "label:" + parameters.requiredString("label")))
                .build());
        runtime.inputs().register(InputSpec.builder("analog")
                .requiredDecimal("amount")
                .handler(parameters -> observed.add(
                        "amount:" + parameters.requiredDecimal("amount").toPlainString()))
                .build());
        runtime.start();
        runtime.controls().control(true, "pause-timeline", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        InputTimelineSpec spec = new InputTimelineSpec(4, List.of(
                transition("press", 1, "button", "active", RuntimeValues.bool(true)),
                transition("first", 2, "label", "label", RuntimeValues.string("A")),
                transition("second", 2, "label", "label", RuntimeValues.string("B")),
                transition("steer", 3, "analog", "amount", RuntimeValues.decimal("0.5")),
                transition("release", 4, "button", "active", RuntimeValues.bool(false))));

        assertTrue(runtime.inputs().timelineAvailable());
        assertEquals(InputTimelineLimits.developmentDefaults(),
                runtime.inputs().timelineLimits());
        InputTimelineOperation queued = runtime.inputs().executeTimeline(
                spec, "timeline-1", Duration.ofSeconds(2));

        assertEquals(CommandState.QUEUED, queued.command().status().orElseThrow().state());
        assertTrue(queued.result().isEmpty());
        assertEquals(1, dispatch.size());
        dispatch.removeFirst().run();

        InputTimelineOperation terminal = runtime.inputs().executeTimeline(
                spec, "timeline-1", Duration.ofSeconds(2));
        InputTimelineResult result = terminal.result().orElseThrow();
        assertEquals(CommandState.SUCCEEDED,
                terminal.command().status().orElseThrow().state());
        assertEquals(InputTimelineStopReason.COMPLETED, result.stopReason());
        assertEquals(new ExecutionEpochId(0), result.startingExecutionEpochId());
        assertEquals(0, result.startingControlledTick());
        assertEquals(10, result.fixedStepNanos());
        assertEquals(new FrameId(1), result.firstFrameId().orElseThrow());
        assertEquals(new FrameId(4), result.finalFrameId().orElseThrow());
        assertEquals(4, result.bounds().completedTicks());
        assertEquals(5, result.bounds().executedTransitions());
        assertEquals(List.of(
                "active:true", "label:A", "label:B", "amount:0.5", "active:false"),
                observed);
        assertEquals(4, simulationTicks.get());
        assertEquals(4, runtime.controls().currentTick());

        List<InputInjection> injections = result.transitions().stream()
                .map(value -> value.injection().orElseThrow()).toList();
        assertEquals(List.of(1L, 2L, 2L, 3L, 4L), injections.stream()
                .map(value -> value.actualTick().orElseThrow()).toList());
        assertEquals(List.of(1L, 2L, 2L, 3L, 4L), injections.stream()
                .map(InputInjection::targetTick).toList());
        assertEquals(List.of(1L, 2L, 2L, 3L, 4L), injections.stream()
                .map(value -> value.resultingFrameId().orElseThrow().value()).toList());
        assertTrue(injections.stream().allMatch(value ->
                value.executionEpochId().equals(result.startingExecutionEpochId())));
        assertTrue(injections.stream().allMatch(value ->
                value.command().kind() == CommandLookup.Kind.FOUND
                        && value.command().status().orElseThrow().state()
                                == CommandState.SUCCEEDED
                        && value.command().status().orElseThrow().requestId()
                                .equals(value.requestId())));

        SimulationTickPage ticks = runtime.simulation().ticks(new SimulationTickQuery(
                result.startingExecutionEpochId(), 1, 4, 4));
        assertTrue(ticks.complete());
        assertEquals(List.of(1L, 2L, 3L, 4L), ticks.ticks().stream()
                .map(SimulationTick::epochTick).toList());
        assertEquals(List.of(1L, 2L, 3L, 4L), ticks.ticks().stream()
                .map(value -> value.resultingFrameId().orElseThrow().value()).toList());
        assertTrue(ticks.ticks().stream().allMatch(value ->
                value.executionEpochId().equals(result.startingExecutionEpochId())));

        InputTimelineOperation secondPoll = runtime.inputs().executeTimeline(
                spec, "timeline-1", Duration.ofSeconds(2));
        assertEquals(result, secondPoll.result().orElseThrow());
        assertTrue(dispatch.isEmpty());
        assertEquals(5, observed.size());
        assertEquals(4, simulationTicks.get());
    }

    @Test
    void failedWholeTimelineReservationPreservesPreviouslyPollableEvidence() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = runtime(dispatch,
                new InputLimits(1, 1, 3, 3, 10, 642),
                new InputTimelineLimits(2, 3, 10, 65_536,
                        Duration.ofSeconds(2).toNanos()));

        InputTimelineSpec firstSpec = oneTransition("first-child");
        InputTimelineSpec secondSpec = oneTransition("second-child");
        InputTimelineResult first = execute(dispatch, runtime, firstSpec, "first-parent");
        InputTimelineResult second = execute(dispatch, runtime, secondSpec, "second-parent");
        RuntimeValue.ObjectValue parameters = booleanParameters(true);
        runtime.inputs().inject("button", "ordinary", parameters, OptionalLong.empty(),
                Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        runtime.controls().advanceFixed("ordinary-tick", 1, Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        InputInjection ordinary = runtime.inputs().inject(
                "button", "ordinary", parameters, OptionalLong.empty(),
                Duration.ofSeconds(1));

        InputTimelineSpec tooLarge = new InputTimelineSpec(1, List.of(
                transition("new-one", 1, "button", "active", RuntimeValues.bool(true)),
                transition("new-two", 1, "button", "active", RuntimeValues.bool(false)),
                transition("new-three", 1, "button", "active", RuntimeValues.bool(true))));

        AgentRuntimeException failure = assertThrows(AgentRuntimeException.class,
                () -> runtime.inputs().executeTimeline(
                        tooLarge, "new-parent", Duration.ofSeconds(1)));

        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED, failure.code());
        assertEquals(first, runtime.inputs().executeTimeline(
                firstSpec, "first-parent", Duration.ofSeconds(1)).result().orElseThrow());
        assertEquals(second, runtime.inputs().executeTimeline(
                secondSpec, "second-parent", Duration.ofSeconds(1)).result().orElseThrow());
        assertEquals(ordinary, runtime.inputs().inject(
                "button", "ordinary", parameters, OptionalLong.empty(),
                Duration.ofSeconds(1)));
        assertTrue(dispatch.isEmpty());
    }

    @Test
    void terminalParentEvictionReleasesItsTimelineChildren() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = runtime(dispatch,
                new InputLimits(1, 1, 2, 2, 10, 642),
                new InputTimelineLimits(2, 1, 10, 65_536,
                        Duration.ofSeconds(2).toNanos()));
        InputTimelineSpec firstSpec = oneTransition("first-child");
        InputTimelineSpec secondSpec = oneTransition("second-child");
        InputTimelineSpec thirdSpec = oneTransition("third-child");

        execute(dispatch, runtime, firstSpec, "first-parent");
        InputTimelineResult second = execute(
                dispatch, runtime, secondSpec, "second-parent");
        InputTimelineResult third = execute(dispatch, runtime, thirdSpec, "third-parent");

        assertEquals(second, runtime.inputs().executeTimeline(
                secondSpec, "second-parent", Duration.ofSeconds(1)).result().orElseThrow());
        assertEquals(third, runtime.inputs().executeTimeline(
                thirdSpec, "third-parent", Duration.ofSeconds(1)).result().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> runtime.inputs().executeTimeline(
                firstSpec, "first-parent", Duration.ofSeconds(1)));
        assertTrue(dispatch.isEmpty());
    }

    @Test
    void tightTerminalReservationRetainsDispatcherRejectionAndReleasesActiveState() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AtomicBoolean reject = new AtomicBoolean();
        InputTimelineSpec rejectedSpec = oneTransition("rejected-child");
        int evidenceBytes = Math.toIntExact(
                InputTimelineCanonicalSize.terminalResultReservation(rejectedSpec));
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-timeline-rejection"))
                .clock(() -> 1)
                .commandDispatcher(command -> {
                    if (reject.get()) {
                        throw new IllegalStateException("dispatcher rejected command");
                    }
                    dispatch.addLast(command);
                })
                .inputLimits(new InputLimits(1, 1, 1, 1, 10, 642))
                .inputTimelineLimits(new InputTimelineLimits(1, 1, 10, evidenceBytes,
                        Duration.ofSeconds(2).toNanos()))
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(10));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(deltaNanos -> deltaNanos)
                .build());
        runtime.inputs().register(InputSpec.builder("button")
                .requiredBoolean("active")
                .handler(parameters -> {})
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        reject.set(true);
        InputTimelineOperation rejected = runtime.inputs().executeTimeline(
                rejectedSpec, "rejected-parent", Duration.ofSeconds(1));
        InputTimelineResult result = rejected.result().orElseThrow();

        assertEquals(CommandState.REJECTED,
                rejected.command().status().orElseThrow().state());
        assertEquals(InputTimelineStopReason.LIFECYCLE_CHANGED, result.stopReason());
        assertTrue(result.applicationFailure().isPresent());
        assertEquals(InputTimelineTransitionState.NOT_EXECUTED,
                result.transitions().getFirst().state());
        assertEquals(InputTimelineCanonicalSize.result(result),
                result.bounds().encodedEvidenceBytes());
        assertTrue(result.bounds().encodedEvidenceBytes() <= evidenceBytes);
        assertEquals(0, runtime.inputs().retainedPendingInjections());
        assertEquals(result, runtime.inputs().executeTimeline(
                rejectedSpec, "rejected-parent", Duration.ofSeconds(1))
                .result().orElseThrow());

        reject.set(false);
        InputTimelineSpec nextSpec = oneTransition("next-child");
        InputTimelineResult next = execute(dispatch, runtime, nextSpec, "next-parent");
        assertEquals(InputTimelineStopReason.COMPLETED, next.stopReason());
    }

    @Test
    void terminalOrdinaryInputRemainsEvictableAfterCommandCorrelationExpires() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = runtime(dispatch,
                new CommandDispatchLimits(1, 1, 1,
                        Duration.ofSeconds(2).toNanos(), 642),
                new InputLimits(1, 1, 2, 2, 10, 642),
                new InputTimelineLimits(1, 2, 10, 65_536,
                        Duration.ofSeconds(2).toNanos()));
        RuntimeValue.ObjectValue parameters = booleanParameters(true);
        runtime.inputs().inject("button", "ordinary", parameters, OptionalLong.empty(),
                Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        runtime.controls().advanceFixed("ordinary-tick", 1, Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        runtime.commands().orElseThrow().submit(
                "correlation-churn", Duration.ofSeconds(1), () -> {});
        dispatch.removeFirst().run();

        assertEquals(CommandLookup.Kind.UNKNOWN,
                runtime.commands().orElseThrow().status("ordinary").kind());
        InputInjection ordinary = runtime.inputs().inject(
                "button", "ordinary", parameters, OptionalLong.empty(),
                Duration.ofSeconds(1));
        assertEquals(InputInjectionState.EXECUTED, ordinary.state());
        assertEquals(CommandLookup.Kind.UNKNOWN, ordinary.command().kind());

        InputTimelineSpec timeline = new InputTimelineSpec(1, List.of(
                transition("timeline-child-one", 1, "button", "active",
                        RuntimeValues.bool(true)),
                transition("timeline-child-two", 1, "button", "active",
                        RuntimeValues.bool(false))));
        InputTimelineResult result = execute(
                dispatch, runtime, timeline, "timeline-parent");

        assertEquals(InputTimelineStopReason.COMPLETED, result.stopReason());
    }

    @Test
    void mismatchedAcknowledgedDeltaCannotPublishCompletedTimeline() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = runtime(dispatch, dispatch::addLast, () -> 1,
                CommandDispatchLimits.developmentDefaults(),
                InputLimits.developmentDefaults(),
                InputTimelineLimits.developmentDefaults(), deltaNanos -> deltaNanos + 1);
        InputTimelineSpec spec = oneTransition("mismatched-child");
        runtime.inputs().executeTimeline(spec, "mismatched-parent", Duration.ofSeconds(1));

        dispatch.removeFirst().run();

        assertEquals(CommandState.FAILED,
                runtime.commands().orElseThrow().status("mismatched-parent")
                        .status().orElseThrow().state());
        assertEquals(SimulationTickOutcome.DELTA_MISMATCH,
                runtime.simulation().ticks(new SimulationTickQuery(
                        new ExecutionEpochId(0), 1, 1, 1)).ticks().getFirst().outcome());
    }

    @Test
    void deadlineOverflowRollsBackWholeReservationAndAllPlannedEvictions() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AtomicLong clock = new AtomicLong(1);
        AdmissionFixture fixture = admissionFixture(dispatch, dispatch::addLast, clock::get);
        InputTimelineSpec replacement = twoTransitions("overflow");
        clock.set(Long.MAX_VALUE - 5);

        assertThrows(IllegalArgumentException.class,
                () -> fixture.runtime.inputs().executeTimeline(
                        replacement, "overflow-parent", Duration.ofNanos(10)));

        assertEquals(fixture.parentResult, fixture.runtime.inputs().executeTimeline(
                fixture.parentSpec, "retained-parent", Duration.ofSeconds(1))
                .result().orElseThrow());
        assertEquals(fixture.ordinary, fixture.runtime.inputs().inject(
                "button", "ordinary", booleanParameters(true), OptionalLong.empty(),
                Duration.ofSeconds(1)));
        assertEquals(0, fixture.runtime.inputs().retainedPendingInjections());

        fixture.runtime.inputs().executeTimeline(
                replacement, "overflow-parent", Duration.ofNanos(1));
        dispatch.removeFirst().run();
        assertEquals(InputTimelineStopReason.COMPLETED,
                fixture.runtime.inputs().executeTimeline(
                        replacement, "overflow-parent", Duration.ofNanos(1))
                        .result().orElseThrow().stopReason());
    }

    @Test
    void dispatcherErrorBeforeCallbackRetainsTerminalTimelineAndPlannedEvictions() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AtomicBoolean failDispatch = new AtomicBoolean();
        ApplicationCommandDispatcher dispatcher = command -> {
            if (failDispatch.get()) {
                throw new AssertionError("dispatcher failed before callback");
            }
            dispatch.addLast(command);
        };
        AdmissionFixture fixture = admissionFixture(dispatch, dispatcher, () -> 1);
        InputTimelineSpec failed = twoTransitions("error");
        failDispatch.set(true);

        assertThrows(AssertionError.class, () -> fixture.runtime.inputs().executeTimeline(
                failed, "error-parent", Duration.ofSeconds(1)));
        failDispatch.set(false);

        InputTimelineOperation retained = fixture.runtime.inputs().executeTimeline(
                failed, "error-parent", Duration.ofSeconds(1));
        InputTimelineResult result = retained.result().orElseThrow();

        assertEquals(CommandState.FAILED,
                retained.command().status().orElseThrow().state());
        assertEquals(InputTimelineStopReason.LIFECYCLE_CHANGED, result.stopReason());
        assertTrue(result.applicationFailure().isPresent());
        assertTrue(result.transitions().stream().allMatch(transition ->
                transition.state() == InputTimelineTransitionState.NOT_EXECUTED));
        assertEquals(0, fixture.runtime.inputs().retainedPendingInjections());
        assertThrows(IllegalArgumentException.class,
                () -> fixture.runtime.inputs().executeTimeline(
                        fixture.parentSpec, "retained-parent", Duration.ofSeconds(1)));
        AgentRuntimeException evictedOrdinary = assertThrows(AgentRuntimeException.class,
                () -> fixture.runtime.inputs().inject(
                        "button", "ordinary", booleanParameters(true), OptionalLong.empty(),
                        Duration.ofSeconds(1)));
        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED, evictedOrdinary.code());
        assertTrue(dispatch.isEmpty());
    }

    @Test
    void dispatcherErrorAfterCallbackDoesNotRollbackMutatedTimeline() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AtomicBoolean failAfterCallback = new AtomicBoolean();
        ApplicationCommandDispatcher dispatcher = command -> {
            if (failAfterCallback.get()) {
                command.run();
                throw new AssertionError("dispatcher failed after callback");
            }
            dispatch.addLast(command);
        };
        AgentRuntime runtime = runtime(dispatch, dispatcher, () -> 1,
                CommandDispatchLimits.developmentDefaults(),
                InputLimits.developmentDefaults(),
                InputTimelineLimits.developmentDefaults(), deltaNanos -> deltaNanos);
        InputTimelineSpec spec = oneTransition("mutated-child");
        failAfterCallback.set(true);

        assertThrows(AssertionError.class, () -> runtime.inputs().executeTimeline(
                spec, "mutated-parent", Duration.ofSeconds(1)));
        failAfterCallback.set(false);

        InputTimelineOperation retained = runtime.inputs().executeTimeline(
                spec, "mutated-parent", Duration.ofSeconds(1));
        assertEquals(CommandState.SUCCEEDED,
                retained.command().status().orElseThrow().state());
        assertEquals(InputTimelineStopReason.COMPLETED,
                retained.result().orElseThrow().stopReason());
        assertTrue(dispatch.isEmpty());
    }

    @Test
    void retainedTimelineParentIdCannotBeReusedByOrdinaryInputAfterCorrelationExpires() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        ParentNamespaceFixture fixture = parentNamespaceFixture(dispatch);

        assertThrows(IllegalArgumentException.class, () -> fixture.runtime.inputs().inject(
                "button", "retained-parent", booleanParameters(false), OptionalLong.empty(),
                Duration.ofSeconds(1)));

        assertEquals(fixture.agedOperation, fixture.runtime.inputs().executeTimeline(
                fixture.spec, "retained-parent", Duration.ofSeconds(1)));
        assertTrue(dispatch.isEmpty());
    }

    @Test
    void retainedTimelineParentIdCannotBeReusedByAnotherTimelineChild() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        ParentNamespaceFixture fixture = parentNamespaceFixture(dispatch);
        InputTimelineSpec collision = new InputTimelineSpec(1, List.of(transition(
                "retained-parent", 1, "button", "active", RuntimeValues.bool(false))));

        assertThrows(IllegalArgumentException.class,
                () -> fixture.runtime.inputs().executeTimeline(
                        collision, "new-parent", Duration.ofSeconds(1)));

        assertEquals(fixture.agedOperation, fixture.runtime.inputs().executeTimeline(
                fixture.spec, "retained-parent", Duration.ofSeconds(1)));
        assertTrue(dispatch.isEmpty());
    }

    @Test
    void preflightRejectsLateInvalidTransitionWithoutRunningAnyHandler() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AtomicInteger handlerCalls = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-preflight"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(10));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(deltaNanos -> deltaNanos)
                .build());
        runtime.inputs().register(InputSpec.builder("button")
                .requiredBoolean("active")
                .handler(parameters -> handlerCalls.incrementAndGet())
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        assertThrows(IllegalArgumentException.class, () -> runtime.inputs().executeTimeline(
                new InputTimelineSpec(2, List.of(
                        transition("valid", 1, "button", "active", RuntimeValues.bool(true)),
                        transition("invalid", 2, "missing", "active",
                                RuntimeValues.bool(false)))),
                "invalid-late", Duration.ofSeconds(1)));
        assertEquals(0, handlerCalls.get());
        assertTrue(dispatch.isEmpty());
    }

    @Test
    void reservedTimelineExcludesOrdinaryInjectionAndChangedRetry() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = runtime(dispatch, InputLimits.developmentDefaults(),
                InputTimelineLimits.developmentDefaults());
        InputTimelineSpec valid = oneTransition("reserved-child");
        InputTimelineSpec changed = new InputTimelineSpec(2, List.of(transition(
                "reserved-child", 1, "button", "active", RuntimeValues.bool(true))));

        InputTimelineOperation queued = runtime.inputs().executeTimeline(
                valid, "reserved", Duration.ofSeconds(1));
        assertThrows(AgentRuntimeException.class, () -> runtime.inputs().inject(
                "button", "ordinary", booleanParameters(true), OptionalLong.empty(),
                Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> runtime.inputs().executeTimeline(
                changed, "reserved", Duration.ofSeconds(1)));
        assertEquals(CommandState.QUEUED, queued.command().status().orElseThrow().state());

        dispatch.removeFirst().run();
        InputTimelineResult completed = runtime.inputs().executeTimeline(
                valid, "reserved", Duration.ofSeconds(1)).result().orElseThrow();
        assertEquals(InputTimelineStopReason.COMPLETED, completed.stopReason());
        runtime.inputs().inject("button", "ordinary", booleanParameters(true),
                OptionalLong.empty(), Duration.ofSeconds(1));
        assertEquals(1, runtime.inputs().retainedPendingInjections());
    }

    @Test
    void parentAndTransitionIdsMustDiffer() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = runtime(dispatch, InputLimits.developmentDefaults(),
                InputTimelineLimits.developmentDefaults());

        assertThrows(IllegalArgumentException.class, () -> runtime.inputs().executeTimeline(
                new InputTimelineSpec(1, List.of(transition(
                        "shared", 1, "button", "active", RuntimeValues.bool(true)))),
                "shared", Duration.ofSeconds(1)));
        assertTrue(dispatch.isEmpty());
    }

    @Test
    void timelineTransitionIdCollidesWithRetainedOrdinaryEvidence() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = runtime(dispatch, InputLimits.developmentDefaults(),
                InputTimelineLimits.developmentDefaults());
        runtime.inputs().inject("button", "ordinary-child", booleanParameters(true),
                OptionalLong.empty(), Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        runtime.controls().advanceFixed("ordinary-tick", 1, Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        assertThrows(IllegalArgumentException.class, () -> runtime.inputs().executeTimeline(
                new InputTimelineSpec(1, List.of(transition(
                        "ordinary-child", 1, "button", "active",
                        RuntimeValues.bool(false)))),
                "collision-parent", Duration.ofSeconds(1)));
        assertTrue(dispatch.isEmpty());
    }

    @Test
    void timelineTransitionIdCollidesWithRetainedCommandCorrelation() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = runtime(dispatch, InputLimits.developmentDefaults(),
                InputTimelineLimits.developmentDefaults());
        runtime.commands().orElseThrow().submit(
                "command-child", Duration.ofSeconds(1), () -> {});

        assertThrows(IllegalArgumentException.class, () -> runtime.inputs().executeTimeline(
                new InputTimelineSpec(1, List.of(transition(
                        "command-child", 1, "button", "active",
                        RuntimeValues.bool(true)))),
                "correlation-parent", Duration.ofSeconds(1)));
        assertEquals(CommandState.QUEUED,
                runtime.commands().orElseThrow().status("command-child")
                        .status().orElseThrow().state());
    }

    @Test
    void queuedResumeBeforeTimelineCallbackStopsWithLifecycleChanged() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = runtime(dispatch, InputLimits.developmentDefaults(),
                InputTimelineLimits.developmentDefaults());
        InputTimelineSpec spec = oneTransition("resumed-child");

        runtime.controls().control(false, "resume", Duration.ofSeconds(1));
        runtime.inputs().executeTimeline(spec, "resumed-parent", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        dispatch.removeFirst().run();

        InputTimelineResult result = runtime.inputs().executeTimeline(
                spec, "resumed-parent", Duration.ofSeconds(1)).result().orElseThrow();
        assertEquals(InputTimelineStopReason.LIFECYCLE_CHANGED, result.stopReason());
        assertEquals(0, result.bounds().completedTicks());
        assertEquals(InputTimelineTransitionState.NOT_EXECUTED,
                result.transitions().getFirst().state());
        assertEquals(0, runtime.inputs().retainedPendingInjections());
    }

    @Test
    void timelineAndDeterminismExclusiveModesConflict() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = runtime(dispatch, InputLimits.developmentDefaults(),
                InputTimelineLimits.developmentDefaults());

        runtime.inputs().beginDeterminism(List.of());
        assertThrows(AgentRuntimeException.class, () -> runtime.inputs().executeTimeline(
                oneTransition("det-child"), "det-parent", Duration.ofSeconds(1)));
        runtime.inputs().endDeterminism();

        runtime.inputs().executeTimeline(
                oneTransition("reserved-det-child"), "reserved-det-parent",
                Duration.ofSeconds(1));
        assertThrows(IllegalStateException.class,
                () -> runtime.inputs().beginDeterminism(List.of()));
        dispatch.removeFirst().run();
        runtime.inputs().beginDeterminism(List.of());
        runtime.inputs().endDeterminism();
    }

    @Test
    void retentionEvictionNeverReexecutesTimelineHandlers() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AtomicInteger handlerCalls = new AtomicInteger();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-eviction"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .inputLimits(new InputLimits(1, 1, 1, 1, 10, 642))
                .inputTimelineLimits(new InputTimelineLimits(1, 1, 10, 65_536,
                        Duration.ofSeconds(2).toNanos()))
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(10));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(deltaNanos -> deltaNanos)
                .build());
        runtime.inputs().register(InputSpec.builder("button")
                .requiredBoolean("active")
                .handler(parameters -> handlerCalls.incrementAndGet())
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        execute(dispatch, runtime, oneTransition("first-evicted-child"), "first-evicted-parent");
        InputTimelineResult second = execute(dispatch, runtime,
                oneTransition("second-kept-child"), "second-kept-parent");

        assertEquals(InputTimelineStopReason.COMPLETED, second.stopReason());
        assertEquals(2, handlerCalls.get());
        assertThrows(IllegalArgumentException.class, () -> runtime.inputs().executeTimeline(
                oneTransition("first-evicted-child"), "first-evicted-parent",
                Duration.ofSeconds(1)));
    }

    @Test
    void inputHandlerClockAdvanceTimesOutBeforeSimulationAndKeepsAttemptedEvidence() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AtomicLong clock = new AtomicLong(1);
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-timeout"))
                .clock(clock::get)
                .commandDispatcher(dispatch::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(10));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(deltaNanos -> deltaNanos)
                .build());
        runtime.inputs().register(InputSpec.builder("expires")
                .requiredBoolean("active")
                .handler(parameters -> clock.set(101))
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        InputTimelineSpec expires = new InputTimelineSpec(1, List.of(transition(
                "expires-1", 1, "expires", "active", RuntimeValues.bool(true))));
        runtime.inputs().executeTimeline(expires, "timeline-timeout", Duration.ofNanos(50));
        dispatch.removeFirst().run();

        InputTimelineResult result = runtime.inputs().executeTimeline(
                expires, "timeline-timeout", Duration.ofNanos(50)).result().orElseThrow();
        assertEquals(InputTimelineStopReason.TIMED_OUT, result.stopReason());
        assertEquals(0, result.bounds().completedTicks());
        assertEquals(InputTimelineTransitionState.EXECUTED,
                result.transitions().getFirst().state());
        assertEquals(1, result.bounds().executedTransitions());
        assertEquals(0, runtime.inputs().retainedPendingInjections());
        assertEquals(result, runtime.inputs().executeTimeline(
                expires, "timeline-timeout", Duration.ofNanos(50)).result().orElseThrow());
    }

    @Test
    void finalTickCallbackClockAdvanceTimesOutAfterCompletingTheTick() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AtomicLong clock = new AtomicLong(1);
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-final-timeout"))
                .clock(clock::get)
                .commandDispatcher(dispatch::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(10));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(deltaNanos -> {
                    clock.set(101);
                    return deltaNanos;
                })
                .build());
        runtime.inputs().register(InputSpec.builder("button")
                .requiredBoolean("active")
                .handler(parameters -> {})
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        InputTimelineSpec spec = oneTransition("final-child");
        runtime.inputs().executeTimeline(spec, "final-timeout", Duration.ofNanos(50));
        dispatch.removeFirst().run();

        InputTimelineResult result = runtime.inputs().executeTimeline(
                spec, "final-timeout", Duration.ofNanos(50)).result().orElseThrow();
        assertEquals(InputTimelineStopReason.TIMED_OUT, result.stopReason());
        assertEquals(1, result.bounds().completedTicks());
        assertEquals(InputTimelineTransitionState.EXECUTED,
                result.transitions().getFirst().state());
        assertTrue(result.finalFrameId().isPresent());
    }

    @Test
    void firstTimelineHandlerFailureStopsSameTickAndLaterTransitions() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        ArrayList<String> observed = new ArrayList<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-fail-stop"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(10));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(deltaNanos -> deltaNanos)
                .build());
        runtime.inputs().register(InputSpec.builder("button")
                .requiredBoolean("active")
                .handler(parameters -> observed.add(
                        "button:" + parameters.requiredBoolean("active")))
                .build());
        runtime.inputs().register(InputSpec.builder("boom")
                .requiredBoolean("active")
                .handler(parameters -> {
                    throw new IllegalStateException("token=secret /home/private/save.dat");
                })
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        InputTimelineSpec spec = new InputTimelineSpec(3, List.of(
                transition("first-ok", 1, "button", "active", RuntimeValues.bool(true)),
                transition("boom", 1, "boom", "active", RuntimeValues.bool(true)),
                transition("after-boom", 1, "button", "active", RuntimeValues.bool(false)),
                transition("later-tick", 2, "button", "active", RuntimeValues.bool(true))));
        runtime.inputs().executeTimeline(spec, "boom-parent", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        InputTimelineResult result = runtime.inputs().executeTimeline(
                spec, "boom-parent", Duration.ofSeconds(1)).result().orElseThrow();

        assertEquals(InputTimelineStopReason.INPUT_FAILED, result.stopReason());
        assertEquals(List.of("button:true"), observed);
        assertEquals(0, result.bounds().completedTicks());
        List<InputTimelineTransitionEvidence> transitions = result.transitions();
        assertEquals(InputTimelineTransitionState.EXECUTED, transitions.get(0).state());
        assertEquals(InputTimelineTransitionState.FAILED, transitions.get(1).state());
        assertEquals(InputTimelineTransitionState.NOT_EXECUTED, transitions.get(2).state());
        assertEquals(InputTimelineTransitionState.NOT_EXECUTED, transitions.get(3).state());
        assertEquals(1, result.bounds().executedTransitions());
        assertEquals(1, result.bounds().failedTransitions());
        assertEquals(2, result.bounds().notExecutedTransitions());
        assertTrue(result.applicationFailure().isPresent());
        assertTrue(transitions.get(1).injection().orElseThrow()
                .applicationFailure().isPresent());
        assertFalse(transitions.get(1).diagnostic().orElseThrow().contains("token=secret"));
        assertFalse(transitions.get(1).diagnostic().orElseThrow()
                .contains("/home/private/save.dat"));
        assertEquals(0, runtime.inputs().retainedPendingInjections());
    }

    @Test
    void simulationCallbackFailureRetainsAttemptedTransitionAsTickFailed() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("timeline-tick-failed"))
                .clock(() -> 1)
                .commandDispatcher(dispatch::addLast)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(10));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(deltaNanos -> {
                    throw new IllegalStateException("simulation exploded");
                })
                .build());
        runtime.inputs().register(InputSpec.builder("button")
                .requiredBoolean("active")
                .handler(parameters -> {})
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        InputTimelineSpec spec = new InputTimelineSpec(2, List.of(
                transition("attempted", 1, "button", "active", RuntimeValues.bool(true)),
                transition("never", 2, "button", "active", RuntimeValues.bool(false))));
        runtime.inputs().executeTimeline(spec, "tick-failed-parent", Duration.ofSeconds(1));
        dispatch.removeFirst().run();

        InputTimelineResult result = runtime.inputs().executeTimeline(
                spec, "tick-failed-parent", Duration.ofSeconds(1)).result().orElseThrow();
        assertEquals(InputTimelineStopReason.TICK_FAILED, result.stopReason());
        assertEquals(0, result.bounds().completedTicks());
        assertEquals(InputTimelineTransitionState.EXECUTED,
                result.transitions().get(0).state());
        assertEquals(InputTimelineTransitionState.NOT_EXECUTED,
                result.transitions().get(1).state());
        assertEquals(1, result.bounds().executedTransitions());
        assertEquals(1, result.bounds().notExecutedTransitions());
        assertTrue(result.applicationFailure().isPresent());
    }

    @Test
    void evidenceByteLimitRejectsBeforeReservationAndMutation() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        InputTimelineSpec spec = twoTransitions("bytes");
        long reservation = InputTimelineCanonicalSize.resultReservation(spec);
        AgentRuntime runtime = runtime(dispatch,
                new InputLimits(1, 1, 4, 4, 10, 642),
                new InputTimelineLimits(1, 2, 10, Math.toIntExact(reservation - 1),
                        Duration.ofSeconds(1).toNanos()));

        AgentRuntimeException failure = assertThrows(AgentRuntimeException.class,
                () -> runtime.inputs().executeTimeline(
                        spec, "bytes-parent", Duration.ofSeconds(1)));
        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED, failure.code());
        assertTrue(dispatch.isEmpty());
    }

    @Test
    void tickCountBeyondEffectiveLimitRejectsBeforeReservation() {
        ArrayDeque<Runnable> dispatch = new ArrayDeque<>();
        AgentRuntime runtime = runtime(dispatch, InputLimits.developmentDefaults(),
                InputTimelineLimits.developmentDefaults());
        InputTimelineSpec spec = new InputTimelineSpec(601, List.of(transition(
                "tick-child", 1, "button", "active", RuntimeValues.bool(true))));

        AgentRuntimeException failure = assertThrows(AgentRuntimeException.class,
                () -> runtime.inputs().executeTimeline(
                        spec, "tick-parent", Duration.ofSeconds(1)));
        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED, failure.code());
        assertTrue(dispatch.isEmpty());
    }

    private static AgentRuntime runtime(ArrayDeque<Runnable> dispatch,
            InputLimits inputLimits, InputTimelineLimits timelineLimits) {
        return runtime(dispatch, CommandDispatchLimits.developmentDefaults(),
                inputLimits, timelineLimits);
    }

    private static AgentRuntime runtime(ArrayDeque<Runnable> dispatch,
            CommandDispatchLimits commandLimits, InputLimits inputLimits,
            InputTimelineLimits timelineLimits) {
        return runtime(dispatch, dispatch::addLast, () -> 1,
                commandLimits, inputLimits, timelineLimits, deltaNanos -> deltaNanos);
    }

    private static AgentRuntime runtime(ArrayDeque<Runnable> dispatch,
            ApplicationCommandDispatcher dispatcher, MonotonicClock clock,
            CommandDispatchLimits commandLimits, InputLimits inputLimits,
            InputTimelineLimits timelineLimits, SimulationTickCallback tick) {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("input-timeline-retention"))
                .clock(clock)
                .commandDispatcher(dispatcher)
                .commandDispatchLimits(commandLimits)
                .inputLimits(inputLimits)
                .inputTimelineLimits(timelineLimits)
                .build();
        runtime.simulation().register(SimulationTimelineSpec.fixedStep(10));
        runtime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .acknowledgedTick(tick)
                .build());
        runtime.inputs().register(InputSpec.builder("button")
                .requiredBoolean("active")
                .handler(parameters -> {})
                .build());
        runtime.start();
        runtime.controls().control(true, "pause", Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        return runtime;
    }

    private static AdmissionFixture admissionFixture(ArrayDeque<Runnable> dispatch,
            ApplicationCommandDispatcher dispatcher, MonotonicClock clock) {
        AgentRuntime runtime = runtime(dispatch, dispatcher, clock,
                new CommandDispatchLimits(4, 16, 16,
                        Duration.ofSeconds(2).toNanos(), 642),
                new InputLimits(1, 1, 2, 2, 10, 642),
                new InputTimelineLimits(1, 2, 10, 65_536,
                        Duration.ofSeconds(2).toNanos()),
                deltaNanos -> deltaNanos);
        InputTimelineSpec parentSpec = oneTransition("retained-child");
        InputTimelineResult parentResult = execute(
                dispatch, runtime, parentSpec, "retained-parent");
        RuntimeValue.ObjectValue parameters = booleanParameters(true);
        runtime.inputs().inject("button", "ordinary", parameters, OptionalLong.empty(),
                Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        runtime.controls().advanceFixed("ordinary-tick", 1, Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        InputInjection ordinary = runtime.inputs().inject(
                "button", "ordinary", parameters, OptionalLong.empty(),
                Duration.ofSeconds(1));
        return new AdmissionFixture(runtime, parentSpec, parentResult, ordinary);
    }

    private static ParentNamespaceFixture parentNamespaceFixture(
            ArrayDeque<Runnable> dispatch) {
        AgentRuntime runtime = runtime(dispatch,
                new CommandDispatchLimits(1, 1, 1,
                        Duration.ofSeconds(2).toNanos(), 642),
                new InputLimits(1, 1, 2, 2, 10, 642),
                new InputTimelineLimits(2, 1, 10, 65_536,
                        Duration.ofSeconds(2).toNanos()));
        InputTimelineSpec spec = oneTransition("retained-child");
        execute(dispatch, runtime, spec, "retained-parent");
        for (int index = 1; index <= 2; index++) {
            runtime.commands().orElseThrow().submit(
                    "correlation-churn-" + index, Duration.ofSeconds(1), () -> {});
            dispatch.removeFirst().run();
        }
        assertEquals(CommandLookup.Kind.UNKNOWN,
                runtime.commands().orElseThrow().status("retained-parent").kind());
        InputTimelineOperation aged = runtime.inputs().executeTimeline(
                spec, "retained-parent", Duration.ofSeconds(1));
        return new ParentNamespaceFixture(runtime, spec, aged);
    }

    private static InputTimelineResult execute(ArrayDeque<Runnable> dispatch,
            AgentRuntime runtime, InputTimelineSpec spec, String requestId) {
        runtime.inputs().executeTimeline(spec, requestId, Duration.ofSeconds(1));
        dispatch.removeFirst().run();
        return runtime.inputs().executeTimeline(spec, requestId, Duration.ofSeconds(1))
                .result().orElseThrow();
    }

    private static InputTimelineSpec oneTransition(String transitionId) {
        return new InputTimelineSpec(1, List.of(transition(
                transitionId, 1, "button", "active", RuntimeValues.bool(true))));
    }

    private static InputTimelineSpec twoTransitions(String prefix) {
        return new InputTimelineSpec(1, List.of(
                transition(prefix + "-one", 1, "button", "active",
                        RuntimeValues.bool(true)),
                transition(prefix + "-two", 1, "button", "active",
                        RuntimeValues.bool(false))));
    }

    private static RuntimeValue.ObjectValue booleanParameters(boolean value) {
        return RuntimeValues.object(RuntimeValues.field("active", RuntimeValues.bool(value)));
    }

    private static InputTimelineTransition transition(String transitionId, int timelineTick,
            String inputId, String parameterName, RuntimeValue value) {
        return new InputTimelineTransition(transitionId, timelineTick, inputId,
                RuntimeValues.object(RuntimeValues.field(parameterName, value)));
    }

    private record AdmissionFixture(AgentRuntime runtime, InputTimelineSpec parentSpec,
            InputTimelineResult parentResult, InputInjection ordinary) {}

    private record ParentNamespaceFixture(AgentRuntime runtime, InputTimelineSpec spec,
            InputTimelineOperation agedOperation) {}
}
