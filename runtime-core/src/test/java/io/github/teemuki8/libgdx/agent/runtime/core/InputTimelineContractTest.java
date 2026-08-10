package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

final class InputTimelineContractTest {
    @Test
    void timelineSpecPreservesOrderedTransitionsAndDefensivelyCopies() {
        ArrayList<InputTimelineTransition> source = new ArrayList<>(List.of(
                transition("press", 1, "button", RuntimeValues.bool(true)),
                transition("steer", 3, "analog", RuntimeValues.decimal("0.5")),
                transition("release", 3, "button", RuntimeValues.bool(false))));

        InputTimelineSpec spec = new InputTimelineSpec(3, source);
        source.clear();

        assertEquals(List.of("press", "steer", "release"), spec.transitions().stream()
                .map(InputTimelineTransition::transitionId).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> spec.transitions().add(transition(
                        "extra", 3, "button", RuntimeValues.bool(false))));
    }

    @Test
    void timelineSpecRejectsInvalidOrderIdentityRangeAndNestedValues() {
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineSpec(0, List.of(
                transition("press", 1, "button", RuntimeValues.bool(true)))));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineSpec(2, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineSpec(2, List.of(
                transition("late", 2, "button", RuntimeValues.bool(true)),
                transition("early", 1, "button", RuntimeValues.bool(false)))));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineSpec(2, List.of(
                transition("same", 1, "button", RuntimeValues.bool(true)),
                transition("same", 2, "button", RuntimeValues.bool(false)))));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineTransition(
                "nested", 1, "button", RuntimeValues.object(RuntimeValues.field(
                        "active", RuntimeValues.list(RuntimeValues.bool(true))))));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineTransition(
                "vector", 1, "button", RuntimeValues.object(RuntimeValues.field(
                        "direction", RuntimeValues.vector2(1, 2)))));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineTransition(
                "null", 1, "button", RuntimeValues.object(RuntimeValues.field(
                        "value", RuntimeValues.nullValue()))));
        IllegalArgumentException boundedValue = assertThrows(IllegalArgumentException.class,
                () -> transition("long-string", 1, "button",
                        RuntimeValues.string("x".repeat(4_097))));
        assertEquals("input timeline parameters exceed bounded runtime-value limits",
                boundedValue.getMessage());

        ArrayList<RuntimeValue.Field> tooManyFields = new ArrayList<>();
        for (int index = 0; index < 257; index++) {
            tooManyFields.add(RuntimeValues.field("field-" + index, RuntimeValues.bool(true)));
        }
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineTransition(
                "too-many-fields", 1, "button", new RuntimeValue.ObjectValue(tooManyFields)));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineSpec(
                InputTimelineLimits.MAXIMUM_TICKS + 1,
                List.of(transition("press", 1, "button", RuntimeValues.bool(true)))));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineSpec(1,
                Collections.nCopies(InputTimelineLimits.MAXIMUM_TRANSITIONS + 1,
                        transition("press", 1, "button", RuntimeValues.bool(true)))));
        assertThrows(NullPointerException.class, () -> new InputTimelineSpec(1, null));
    }

    @Test
    void developmentTimelineLimitsMatchTheApprovedContractAndSupportedCeilings() {
        assertEquals(new InputTimelineLimits(
                32, 4_096, 600, 1_048_576, Duration.ofSeconds(30).toNanos()),
                InputTimelineLimits.developmentDefaults());
        assertEquals(100_000, InputTimelineLimits.MAXIMUM_RETAINED_OPERATIONS);
        assertEquals(100_000, InputTimelineLimits.MAXIMUM_TRANSITIONS);
        assertEquals(100_000, InputTimelineLimits.MAXIMUM_TICKS);
        assertEquals(16_777_216, InputTimelineLimits.MAXIMUM_ENCODED_EVIDENCE_BYTES);
        assertEquals(Duration.ofMinutes(5).toNanos(),
                InputTimelineLimits.MAXIMUM_EXECUTION_NANOS);

        assertThrows(IllegalArgumentException.class, () -> limits(0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> limits(
                InputTimelineLimits.MAXIMUM_RETAINED_OPERATIONS + 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(
                1, InputTimelineLimits.MAXIMUM_TRANSITIONS + 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(
                1, 1, InputTimelineLimits.MAXIMUM_TICKS + 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(
                1, 1, 1, InputTimelineLimits.MAXIMUM_ENCODED_EVIDENCE_BYTES + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(
                1, 1, 1, 1, InputTimelineLimits.MAXIMUM_EXECUTION_NANOS + 1));
    }

    @Test
    void transitionEvidenceRequiresStateConsistentAttemptAndBoundedDiagnostic() {
        InputTimelineTransitionEvidence executed = executedEvidence(
                "press", 1, "button", 1);
        InputTimelineTransitionEvidence unattempted = unattemptedEvidence(
                "release", 2, "button", "earlier transition failed");

        assertEquals(InputTimelineTransitionState.EXECUTED, executed.state());
        assertEquals(Optional.empty(), unattempted.injection());
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineTransitionEvidence(
                "press", 1, "button", InputTimelineTransitionState.NOT_EXECUTED,
                executed.injection(), Optional.of("not attempted")));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineTransitionEvidence(
                "press", 1, "button", InputTimelineTransitionState.EXECUTED,
                Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineTransitionEvidence(
                "press", 1, "button", InputTimelineTransitionState.NOT_EXECUTED,
                Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineTransitionEvidence(
                "press", 1, "button", InputTimelineTransitionState.NOT_EXECUTED,
                Optional.empty(), Optional.of("x".repeat(
                        ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY + 1))));
        assertThrows(NullPointerException.class, () -> new InputTimelineTransitionEvidence(
                "press", 1, "button", InputTimelineTransitionState.NOT_EXECUTED,
                null, Optional.of("not attempted")));
        assertThrows(NullPointerException.class, () -> new InputTimelineTransitionEvidence(
                "press", 1, "button", InputTimelineTransitionState.NOT_EXECUTED,
                Optional.empty(), null));
    }

    @Test
    void boundsRequireCompleteNonOverflowingCountTestimony() {
        InputTimelineBounds bounds = completedBounds(2, 2, 128);

        assertEquals(2, bounds.executedTransitions());
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineBounds(
                1, 2, 1, 1, 0, 0, 1, 1, 1, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineBounds(
                1, 0, 2, 1, 0, 0, 1, 1, 2, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineBounds(
                2, 2, 1, 1, 0, 0, 11, 2, 1, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineBounds(
                2, 2, 1, 1, 0, 0, 1, 1, 1, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineBounds(
                2, 2, 1, 1, 0, 0, 1, 2, 1, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineBounds(
                1, 0, 2, Integer.MAX_VALUE, Integer.MAX_VALUE, 4,
                1, 1, 2, 10, 10));
    }

    @Test
    void resultRequiresConsistentCompletedEvidenceAndDefensivelyCopies() {
        ArrayList<InputTimelineTransitionEvidence> source = new ArrayList<>(List.of(
                executedEvidence("press", 1, "button", 1),
                executedEvidence("release", 2, "button", 2)));
        InputTimelineBounds bounds = completedBounds(2, 2, 128);
        InputTimelineResult completed = result(
                InputTimelineStopReason.COMPLETED, source, bounds, Optional.empty());
        source.clear();

        assertEquals(List.of("press", "release"), completed.transitions().stream()
                .map(InputTimelineTransitionEvidence::transitionId).toList());
        assertThrows(UnsupportedOperationException.class, () -> completed.transitions().clear());

        InputTimelineResult timedOutAfterFinalTick = result(
                InputTimelineStopReason.TIMED_OUT, completed.transitions(), bounds,
                Optional.empty());
        assertEquals(2, timedOutAfterFinalTick.bounds().completedTicks());
        assertEquals(2, timedOutAfterFinalTick.bounds().executedTransitions());

        List<InputTimelineTransitionEvidence> incomplete = List.of(
                executedEvidence("press", 1, "button", 1),
                unattemptedEvidence("release", 2, "button", "deadline expired"));
        InputTimelineBounds incompleteBounds = new InputTimelineBounds(
                2, 1, 2, 1, 0, 1, 96, 2, 2, 1_024, 10);
        assertThrows(IllegalArgumentException.class, () -> result(
                InputTimelineStopReason.COMPLETED, incomplete, incompleteBounds,
                Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> result(
                InputTimelineStopReason.COMPLETED, completed.transitions(), bounds,
                Optional.of(applicationFailure())));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineResult(
                InputTimelineStopReason.COMPLETED,
                "x".repeat(ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY + 1),
                new ExecutionEpochId(1), 10, 16, Optional.of(new FrameId(1)),
                Optional.of(new FrameId(2)), completed.transitions(), bounds, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineResult(
                InputTimelineStopReason.TIMED_OUT, "timed out", new ExecutionEpochId(1),
                10, 16, Optional.of(new FrameId(1)), Optional.of(new FrameId(2)),
                incomplete, bounds, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new InputTimelineResult(
                InputTimelineStopReason.COMPLETED, "completed", new ExecutionEpochId(1),
                10, 16, null, Optional.of(new FrameId(2)), completed.transitions(), bounds,
                Optional.empty()));
        assertThrows(NullPointerException.class, () -> new InputTimelineResult(
                InputTimelineStopReason.COMPLETED, "completed", new ExecutionEpochId(1),
                10, 16, Optional.of(new FrameId(1)), null, completed.transitions(), bounds,
                Optional.empty()));
        assertThrows(NullPointerException.class, () -> new InputTimelineResult(
                InputTimelineStopReason.COMPLETED, "completed", new ExecutionEpochId(1),
                10, 16, Optional.of(new FrameId(1)), Optional.of(new FrameId(2)), null, bounds,
                Optional.empty()));
        assertThrows(NullPointerException.class, () -> new InputTimelineResult(
                InputTimelineStopReason.COMPLETED, "completed", new ExecutionEpochId(1),
                10, 16, Optional.of(new FrameId(1)), Optional.of(new FrameId(2)),
                completed.transitions(), bounds, null));
    }

    @Test
    void resultRequiresFrameAndAttemptedInputCorrelation() {
        List<InputTimelineTransitionEvidence> unattempted = List.of(
                unattemptedEvidence("press", 1, "button", "not executed"));
        InputTimelineBounds zeroCompleted = new InputTimelineBounds(
                1, 0, 1, 0, 0, 1, 64, 1, 1, 1_024, 10);
        assertThrows(IllegalArgumentException.class, () -> result(
                InputTimelineStopReason.TIMED_OUT, 10, unattempted, zeroCompleted,
                Optional.of(new FrameId(1)), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> result(
                InputTimelineStopReason.TIMED_OUT, 10, unattempted, zeroCompleted,
                Optional.empty(), Optional.of(new FrameId(1))));

        List<InputTimelineTransitionEvidence> executed = List.of(
                executedEvidence("press", 1, "button", 1));
        InputTimelineBounds oneCompleted = completedBounds(1, 1, 64);
        assertThrows(IllegalArgumentException.class, () -> result(
                InputTimelineStopReason.COMPLETED, 10, executed, oneCompleted,
                Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> result(
                InputTimelineStopReason.COMPLETED, 10, executed, oneCompleted,
                Optional.of(new FrameId(1)), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> result(
                InputTimelineStopReason.COMPLETED, 10, executed, oneCompleted,
                Optional.empty(), Optional.of(new FrameId(1))));
        assertThrows(IllegalArgumentException.class, () -> result(
                InputTimelineStopReason.COMPLETED, 10, executed, oneCompleted,
                Optional.of(new FrameId(2)), Optional.of(new FrameId(1))));

        assertThrows(IllegalArgumentException.class, () -> result(
                InputTimelineStopReason.COMPLETED, 10,
                List.of(executedEvidence("press", 1, "button", 1,
                        12, OptionalLong.of(11), new ExecutionEpochId(1))),
                oneCompleted, Optional.of(new FrameId(1)), Optional.of(new FrameId(1))));
        assertThrows(IllegalArgumentException.class, () -> result(
                InputTimelineStopReason.COMPLETED, 10,
                List.of(executedEvidence("press", 1, "button", 1,
                        11, OptionalLong.of(12), new ExecutionEpochId(1))),
                oneCompleted, Optional.of(new FrameId(1)), Optional.of(new FrameId(1))));
        assertThrows(IllegalArgumentException.class, () -> result(
                InputTimelineStopReason.COMPLETED, 10,
                List.of(executedEvidence("press", 1, "button", 1,
                        11, OptionalLong.empty(), new ExecutionEpochId(1))),
                oneCompleted, Optional.of(new FrameId(1)), Optional.of(new FrameId(1))));
        assertThrows(IllegalArgumentException.class, () -> result(
                InputTimelineStopReason.COMPLETED, 10,
                List.of(executedEvidence("press", 1, "button", 1,
                        11, OptionalLong.of(11), new ExecutionEpochId(2))),
                oneCompleted, Optional.of(new FrameId(1)), Optional.of(new FrameId(1))));
        InputTimelineBounds failedBounds = new InputTimelineBounds(
                1, 0, 1, 0, 1, 0, 64, 1, 1, 1_024, 10);
        assertThrows(IllegalArgumentException.class, () -> result(
                InputTimelineStopReason.INPUT_FAILED, 10,
                List.of(failedEvidence("press", 1, "button", OptionalLong.empty())),
                failedBounds, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> result(
                InputTimelineStopReason.COMPLETED, Long.MAX_VALUE, executed, oneCompleted,
                Optional.of(new FrameId(1)), Optional.of(new FrameId(1))));
    }

    @Test
    void operationValidatesIdentityAndBuilderAcceptsIndependentTimelineLimits() {
        InputTimelineResult result = result(InputTimelineStopReason.COMPLETED,
                List.of(executedEvidence("press", 1, "button", 1)),
                completedBounds(1, 1, 64), Optional.empty());
        InputTimelineOperation operation = new InputTimelineOperation(
                "timeline", command("timeline"), Optional.of(result));

        assertEquals("timeline", operation.requestId());
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineOperation(
                " ", command("timeline"), Optional.of(result)));
        assertThrows(NullPointerException.class, () -> new InputTimelineOperation(
                "timeline", command("timeline"), null));
        assertThrows(NullPointerException.class,
                () -> AgentRuntime.builder().inputTimelineLimits(null));
        assertDoesNotThrow(() -> AgentRuntime.builder().inputTimelineLimits(
                new InputTimelineLimits(4, 8, 16, 4_096, 1_000)).build());
    }

    @Test
    void operationRequiresFoundCommandLifecycleToMatchItsResult() {
        InputTimelineResult completed = result(InputTimelineStopReason.COMPLETED,
                List.of(executedEvidence("press", 1, "button", 1)),
                completedBounds(1, 1, 64), Optional.empty());
        InputTimelineResult inputFailed = stoppedResult(InputTimelineStopReason.INPUT_FAILED);
        InputTimelineResult lifecycleChanged =
                stoppedResult(InputTimelineStopReason.LIFECYCLE_CHANGED);
        InputTimelineResult timedOut = stoppedResult(InputTimelineStopReason.TIMED_OUT);

        assertDoesNotThrow(() -> new InputTimelineOperation(
                "timeline", found("timeline", CommandState.QUEUED), Optional.empty()));
        assertDoesNotThrow(() -> new InputTimelineOperation(
                "timeline", found("timeline", CommandState.EXECUTING), Optional.empty()));
        assertDoesNotThrow(() -> new InputTimelineOperation(
                "timeline", timedOut("timeline", false), Optional.empty()));
        assertDoesNotThrow(() -> new InputTimelineOperation(
                "timeline", found("timeline", CommandState.REJECTED),
                Optional.of(lifecycleChanged)));
        assertDoesNotThrow(() -> new InputTimelineOperation(
                "timeline", found("timeline", CommandState.FAILED), Optional.of(inputFailed)));
        assertDoesNotThrow(() -> new InputTimelineOperation(
                "timeline", found("timeline", CommandState.CANCELLED),
                Optional.of(lifecycleChanged)));
        assertDoesNotThrow(() -> new InputTimelineOperation(
                "timeline", found("timeline", CommandState.SUCCEEDED), Optional.of(completed)));
        assertDoesNotThrow(() -> new InputTimelineOperation(
                "timeline", found("timeline", CommandState.SUCCEEDED), Optional.of(inputFailed)));
        assertDoesNotThrow(() -> new InputTimelineOperation(
                "timeline", timedOut("timeline", true), Optional.of(timedOut)));

        assertThrows(IllegalArgumentException.class, () -> new InputTimelineOperation(
                "timeline", found("timeline", CommandState.QUEUED), Optional.of(completed)));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineOperation(
                "timeline", found("timeline", CommandState.EXECUTING), Optional.of(completed)));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineOperation(
                "timeline", timedOut("timeline", false), Optional.of(completed)));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineOperation(
                "timeline", found("timeline", CommandState.SUCCEEDED), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineOperation(
                "timeline", timedOut("timeline", true), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineOperation(
                "timeline", found("different", CommandState.SUCCEEDED), Optional.of(completed)));
        for (CommandState state : List.of(
                CommandState.REJECTED, CommandState.FAILED, CommandState.CANCELLED)) {
            assertThrows(IllegalArgumentException.class, () -> new InputTimelineOperation(
                    "timeline", found("timeline", state), Optional.of(completed)));
        }
        assertThrows(IllegalArgumentException.class, () -> new InputTimelineOperation(
                "timeline", timedOut("timeline", true), Optional.of(completed)));
    }

    @Test
    void operationMissingLookupsMayRetainIndependentTerminalResults() {
        InputTimelineResult result = result(InputTimelineStopReason.COMPLETED,
                List.of(executedEvidence("press", 1, "button", 1)),
                completedBounds(1, 1, 64), Optional.empty());
        CommandLookup expired = new CommandLookup(
                CommandLookup.Kind.EXPIRED, Optional.empty());
        CommandLookup unknown = new CommandLookup(
                CommandLookup.Kind.UNKNOWN, Optional.empty());

        assertDoesNotThrow(() -> new InputTimelineOperation(
                "timeline", expired, Optional.empty()));
        assertDoesNotThrow(() -> new InputTimelineOperation(
                "timeline", unknown, Optional.empty()));
        assertDoesNotThrow(() -> new InputTimelineOperation(
                "timeline", expired, Optional.of(result)));
        assertDoesNotThrow(() -> new InputTimelineOperation(
                "timeline", unknown, Optional.of(result)));
    }

    @Test
    void timelineResultPublicationWaitsForTheParentOutcome() {
        InputTimelineResult result = result(InputTimelineStopReason.COMPLETED,
                List.of(executedEvidence("press", 1, "button", 1)),
                completedBounds(1, 1, 64), Optional.empty());

        assertEquals(Optional.empty(), InputTimelineExecutor.visibleResult(
                found("timeline", CommandState.EXECUTING), result));
        assertEquals(Optional.of(result), InputTimelineExecutor.visibleResult(
                found("timeline", CommandState.SUCCEEDED), result));
        assertEquals(Optional.of(result), InputTimelineExecutor.visibleResult(
                CommandLookup.missing(CommandLookup.Kind.EXPIRED), result));
    }

    @Test
    void canonicalRequestSizeCountsAsciiAndMultibyteStringsExactly() {
        InputTimelineSpec ascii = new InputTimelineSpec(1, List.of(
                transition("t", 1, "label", RuntimeValues.string("A"))));
        InputTimelineSpec multibyte = new InputTimelineSpec(1, List.of(
                transition("t", 1, "label", RuntimeValues.string("€"))));

        assertEquals(46, InputTimelineCanonicalSize.request(ascii));
        assertEquals(48, InputTimelineCanonicalSize.request(multibyte));
    }

    @Test
    void canonicalResultSizeCountsMultibyteChildAndStructuredFailureEvidence() {
        ApplicationFailureEvidence failure = new ApplicationFailureEvidence(
                "input.execution", IllegalStateException.class.getName(),
                "session-failure-1", Optional.of("é"));
        RuntimeValue.ObjectValue parameters = RuntimeValues.object(
                RuntimeValues.field("value", RuntimeValues.string("€")));
        InputInjection failedInjection = new InputInjection(
                "button", "press",
                CommandLookup.found(new CommandStatus(
                        "press", CommandState.FAILED, 1, 10,
                        Optional.of(2L), Optional.of(3L), true,
                        Optional.of("é"), Optional.of(failure))),
                InputInjectionState.FAILED, 11, OptionalLong.of(11),
                new ExecutionEpochId(1), Optional.of(new FrameId(0)), Optional.empty(),
                Optional.of(parameters), false, Optional.of("é"), Optional.of(failure));
        InputTimelineTransitionEvidence failed = new InputTimelineTransitionEvidence(
                "press", 1, "button", InputTimelineTransitionState.FAILED,
                Optional.of(failedInjection), Optional.of("é"));
        InputTimelineResult failedResult = new InputTimelineResult(
                InputTimelineStopReason.INPUT_FAILED, "é", new ExecutionEpochId(1),
                10, 16, Optional.empty(), Optional.empty(), List.of(failed),
                new InputTimelineBounds(1, 0, 1, 0, 1, 0,
                        0, 1, 1, 1_024, 10), Optional.of(failure));

        assertEquals(510, InputTimelineCanonicalSize.result(failedResult));

        InputInjection succeededInjection = new InputInjection(
                "button", "press", command("press"), InputInjectionState.EXECUTED,
                11, OptionalLong.of(11), new ExecutionEpochId(1),
                Optional.of(new FrameId(0)), Optional.of(new FrameId(1)),
                Optional.of(parameters), false, Optional.empty(), Optional.empty());
        InputTimelineTransitionEvidence succeeded = new InputTimelineTransitionEvidence(
                "press", 1, "button", InputTimelineTransitionState.EXECUTED,
                Optional.of(succeededInjection), Optional.empty());
        InputTimelineResult succeededResult = new InputTimelineResult(
                InputTimelineStopReason.COMPLETED, "completed", new ExecutionEpochId(1),
                10, 16, Optional.of(new FrameId(1)), Optional.of(new FrameId(1)),
                List.of(succeeded), completedBounds(1, 1, 0), Optional.empty());
        InputTimelineSpec spec = new InputTimelineSpec(1, List.of(
                new InputTimelineTransition("press", 1, "button", parameters)));

        assertEquals(277, InputTimelineCanonicalSize.result(succeededResult));
        assertEquals(InputTimelineCanonicalSize.result(succeededResult),
                InputTimelineCanonicalSize.successfulResultReservation(spec));
        assertEquals(21_134,
                InputTimelineCanonicalSize.terminalResultReservation(spec));
        assertTrue(InputTimelineCanonicalSize.terminalResultReservation(spec)
                > InputTimelineCanonicalSize.successfulResultReservation(spec));
        assertEquals(InputTimelineCanonicalSize.terminalResultReservation(spec),
                InputTimelineCanonicalSize.resultReservation(spec));
    }

    @Test
    void terminalReservationCoversNearCapacityFailedTransitionEvidence() {
        ApplicationFailureEvidence failure = new ApplicationFailureEvidence(
                "€".repeat(ApplicationFailureEvidence.MAX_CATEGORY_LENGTH),
                "€".repeat(ApplicationFailureEvidence.MAX_EXCEPTION_CLASS_LENGTH),
                "€".repeat(ApplicationFailureEvidence.MAX_CORRELATION_ID_LENGTH),
                Optional.of("€".repeat(ApplicationFailureEvidence.MAX_SANITIZED_DETAIL_LENGTH)));
        String envelope = failure.legacyEnvelope();
        RuntimeValue.ObjectValue parameters = RuntimeValues.object(
                RuntimeValues.field("value", RuntimeValues.string("€")));
        InputInjection failedInjection = new InputInjection(
                "button", "press",
                CommandLookup.found(new CommandStatus(
                        "press", CommandState.FAILED, 1, 10,
                        Optional.of(2L), Optional.of(3L), true,
                        Optional.of(envelope), Optional.of(failure))),
                InputInjectionState.FAILED, 11, OptionalLong.of(11),
                new ExecutionEpochId(1), Optional.of(new FrameId(0)), Optional.empty(),
                Optional.of(parameters), false, Optional.of(envelope), Optional.of(failure));
        InputTimelineTransitionEvidence failed = new InputTimelineTransitionEvidence(
                "press", 1, "button", InputTimelineTransitionState.FAILED,
                Optional.of(failedInjection), Optional.of(envelope));
        InputTimelineResult failedResult = new InputTimelineResult(
                InputTimelineStopReason.INPUT_FAILED,
                InputTimelineCanonicalSize.INPUT_FAILED_MESSAGE, new ExecutionEpochId(1),
                10, 16, Optional.empty(), Optional.empty(), List.of(failed),
                new InputTimelineBounds(1, 0, 1, 0, 1, 0,
                        0, 1, 1, 1_048_576, 10), Optional.of(failure));
        InputTimelineSpec spec = new InputTimelineSpec(1, List.of(
                new InputTimelineTransition("press", 1, "button", parameters)));

        long encoded = InputTimelineCanonicalSize.result(failedResult);
        assertTrue(encoded <= InputTimelineCanonicalSize.resultReservation(spec),
                "failed result must fit its preflight reservation");
        assertTrue(encoded <= InputTimelineCanonicalSize.terminalResultReservation(spec),
                "failed result must fit the terminal reservation");
    }

    @Test
    void canonicalSizeAdditionSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE,
                InputTimelineCanonicalSize.add(Long.MAX_VALUE - 1, 2));
        assertEquals(Long.MAX_VALUE,
                InputTimelineCanonicalSize.add(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    private static InputTimelineTransition transition(
            String id, int tick, String input, RuntimeValue value) {
        return new InputTimelineTransition(id, tick, input,
                RuntimeValues.object(RuntimeValues.field("value", value)));
    }

    private static InputTimelineLimits limits(
            int operations, int transitions, int ticks, int bytes, long executionNanos) {
        return new InputTimelineLimits(operations, transitions, ticks, bytes, executionNanos);
    }

    private static InputTimelineTransitionEvidence executedEvidence(
            String id, int tick, String inputId, long frameId) {
        long globalTick = Math.addExact(10, tick);
        return executedEvidence(id, tick, inputId, frameId, globalTick,
                OptionalLong.of(globalTick), new ExecutionEpochId(1));
    }

    private static InputTimelineTransitionEvidence executedEvidence(
            String id, int tick, String inputId, long frameId, long targetTick,
            OptionalLong actualTick, ExecutionEpochId executionEpochId) {
        InputInjection injection = new InputInjection(
                inputId, id, command(id), InputInjectionState.EXECUTED, targetTick,
                actualTick, executionEpochId, Optional.empty(),
                Optional.of(new FrameId(frameId)), Optional.of(RuntimeValues.object()),
                false, Optional.empty(), Optional.empty());
        return new InputTimelineTransitionEvidence(id, tick, inputId,
                InputTimelineTransitionState.EXECUTED, Optional.of(injection), Optional.empty());
    }

    private static InputTimelineTransitionEvidence unattemptedEvidence(
            String id, int tick, String inputId, String diagnostic) {
        return new InputTimelineTransitionEvidence(id, tick, inputId,
                InputTimelineTransitionState.NOT_EXECUTED, Optional.empty(),
                Optional.of(diagnostic));
    }

    private static InputTimelineTransitionEvidence failedEvidence(
            String id, int tick, String inputId, OptionalLong actualTick) {
        long globalTick = Math.addExact(10, tick);
        InputInjection injection = new InputInjection(
                inputId, id, found(id, CommandState.FAILED), InputInjectionState.FAILED,
                globalTick, actualTick, new ExecutionEpochId(1), Optional.empty(),
                Optional.empty(), Optional.of(RuntimeValues.object()), false,
                Optional.of("handler failed"), Optional.empty());
        return new InputTimelineTransitionEvidence(id, tick, inputId,
                InputTimelineTransitionState.FAILED, Optional.of(injection),
                Optional.of("handler failed"));
    }

    private static InputTimelineBounds completedBounds(
            int ticks, int transitions, long encodedBytes) {
        return new InputTimelineBounds(ticks, ticks, transitions, transitions, 0, 0,
                encodedBytes, ticks, transitions, 1_024, 10);
    }

    private static InputTimelineResult result(InputTimelineStopReason reason,
            List<InputTimelineTransitionEvidence> transitions, InputTimelineBounds bounds,
            Optional<ApplicationFailureEvidence> applicationFailure) {
        return result(reason, 10, transitions, bounds,
                Optional.of(new FrameId(1)), Optional.of(new FrameId(2)), applicationFailure);
    }

    private static InputTimelineResult result(InputTimelineStopReason reason,
            long startingControlledTick, List<InputTimelineTransitionEvidence> transitions,
            InputTimelineBounds bounds, Optional<FrameId> firstFrameId,
            Optional<FrameId> finalFrameId) {
        return result(reason, startingControlledTick, transitions, bounds,
                firstFrameId, finalFrameId, Optional.empty());
    }

    private static InputTimelineResult result(InputTimelineStopReason reason,
            long startingControlledTick, List<InputTimelineTransitionEvidence> transitions,
            InputTimelineBounds bounds, Optional<FrameId> firstFrameId,
            Optional<FrameId> finalFrameId,
            Optional<ApplicationFailureEvidence> applicationFailure) {
        return new InputTimelineResult(reason,
                reason == InputTimelineStopReason.COMPLETED ? "completed" : "stopped",
                new ExecutionEpochId(1), startingControlledTick, 16, firstFrameId,
                finalFrameId, transitions, bounds, applicationFailure);
    }

    private static InputTimelineResult stoppedResult(InputTimelineStopReason reason) {
        return result(reason, 10,
                List.of(unattemptedEvidence("press", 1, "button", "not executed")),
                new InputTimelineBounds(1, 0, 1, 0, 0, 1,
                        64, 1, 1, 1_024, 10),
                Optional.empty(), Optional.empty());
    }

    private static CommandLookup command(String requestId) {
        return found(requestId, CommandState.SUCCEEDED);
    }

    private static CommandLookup found(String requestId, CommandState state) {
        Optional<Long> started = switch (state) {
            case EXECUTING, SUCCEEDED, FAILED -> Optional.of(2L);
            case QUEUED, REJECTED, CANCELLED -> Optional.empty();
            case TIMED_OUT -> throw new IllegalArgumentException("use timedOut");
        };
        Optional<Long> completed = switch (state) {
            case REJECTED, SUCCEEDED, FAILED, CANCELLED -> Optional.of(3L);
            case QUEUED, EXECUTING -> Optional.empty();
            case TIMED_OUT -> throw new IllegalArgumentException("use timedOut");
        };
        boolean outcomeKnown = switch (state) {
            case REJECTED, SUCCEEDED, FAILED, CANCELLED -> true;
            case QUEUED, EXECUTING -> false;
            case TIMED_OUT -> throw new IllegalArgumentException("use timedOut");
        };
        return CommandLookup.found(new CommandStatus(
                requestId, state, 1, 10, started, completed, outcomeKnown,
                Optional.empty(), Optional.empty()));
    }

    private static CommandLookup timedOut(String requestId, boolean outcomeKnown) {
        return CommandLookup.found(new CommandStatus(
                requestId, CommandState.TIMED_OUT, 1, 10,
                outcomeKnown ? Optional.empty() : Optional.of(2L),
                outcomeKnown ? Optional.of(10L) : Optional.empty(), outcomeKnown,
                Optional.of("timed out"), Optional.empty()));
    }

    private static ApplicationFailureEvidence applicationFailure() {
        return new ApplicationFailureEvidence(
                "input.handler", IllegalStateException.class.getName(), "session-failure-1",
                Optional.empty());
    }
}
