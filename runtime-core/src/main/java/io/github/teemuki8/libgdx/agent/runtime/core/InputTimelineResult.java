package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable bounded terminal result for one exact-tick input timeline. */
public record InputTimelineResult(InputTimelineStopReason stopReason, String message,
        ExecutionEpochId startingExecutionEpochId, long startingControlledTick,
        long fixedStepNanos, Optional<FrameId> firstFrameId, Optional<FrameId> finalFrameId,
        List<InputTimelineTransitionEvidence> transitions, InputTimelineBounds bounds,
        Optional<ApplicationFailureEvidence> applicationFailure) {
    /** Validates immutable terminal evidence and completed-result consistency. */
    public InputTimelineResult {
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()
                || message.length() > ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY) {
            throw new IllegalArgumentException("input timeline message is outside the public bound");
        }
        Objects.requireNonNull(startingExecutionEpochId, "startingExecutionEpochId");
        if (startingControlledTick < 0) {
            throw new IllegalArgumentException(
                    "input timeline starting controlled tick must be non-negative");
        }
        if (fixedStepNanos <= 0) {
            throw new IllegalArgumentException("input timeline fixed step must be positive");
        }
        firstFrameId = Objects.requireNonNull(firstFrameId, "firstFrameId");
        finalFrameId = Objects.requireNonNull(finalFrameId, "finalFrameId");
        Objects.requireNonNull(transitions, "transitions");
        transitions.forEach(value -> Objects.requireNonNull(value, "transition evidence"));
        transitions = List.copyOf(transitions);
        Objects.requireNonNull(bounds, "bounds");
        applicationFailure = Objects.requireNonNull(applicationFailure, "applicationFailure");
        boolean completedTicksPresent = bounds.completedTicks() > 0;
        if (firstFrameId.isPresent() != completedTicksPresent
                || finalFrameId.isPresent() != completedTicksPresent) {
            throw new IllegalArgumentException(
                    "input timeline frame evidence is inconsistent with completed ticks");
        }
        if (completedTicksPresent
                && firstFrameId.orElseThrow().compareTo(finalFrameId.orElseThrow()) > 0) {
            throw new IllegalArgumentException("input timeline frame order is inconsistent");
        }
        if (transitions.size() != bounds.requestedTransitions()) {
            throw new IllegalArgumentException(
                    "input timeline transition evidence count is inconsistent");
        }
        validateTransitionEvidence(transitions, bounds,
                startingExecutionEpochId, startingControlledTick);
        if (stopReason == InputTimelineStopReason.COMPLETED
                && (bounds.completedTicks() != bounds.requestedTicks()
                        || bounds.executedTransitions() != bounds.requestedTransitions()
                        || bounds.failedTransitions() != 0 || bounds.notExecutedTransitions() != 0
                        || applicationFailure.isPresent())) {
            throw new IllegalArgumentException("completed input timeline evidence is inconsistent");
        }
    }

    private static void validateTransitionEvidence(
            List<InputTimelineTransitionEvidence> transitions, InputTimelineBounds bounds,
            ExecutionEpochId startingExecutionEpochId, long startingControlledTick) {
        Set<String> transitionIds = new HashSet<>();
        int priorTick = 0;
        int executed = 0;
        int failed = 0;
        int notExecuted = 0;
        for (InputTimelineTransitionEvidence transition : transitions) {
            long expectedTick;
            try {
                expectedTick = Math.addExact(
                        startingControlledTick, (long) transition.timelineTick());
            } catch (ArithmeticException failure) {
                throw new IllegalArgumentException(
                        "input timeline transition tick overflows the controlled timeline",
                        failure);
            }
            if (transition.timelineTick() < priorTick
                    || transition.timelineTick() > bounds.requestedTicks()
                    || !transitionIds.add(transition.transitionId())) {
                throw new IllegalArgumentException(
                        "input timeline transition evidence order is inconsistent");
            }
            priorTick = transition.timelineTick();
            transition.injection().ifPresent(injection -> {
                if (injection.targetTick() != expectedTick
                        || injection.actualTick().isEmpty()
                        || injection.actualTick().orElseThrow() != expectedTick
                        || !injection.executionEpochId().equals(startingExecutionEpochId)) {
                    throw new IllegalArgumentException(
                            "input timeline transition injection correlation is inconsistent");
                }
            });
            switch (transition.state()) {
                case EXECUTED -> executed++;
                case FAILED -> failed++;
                case NOT_EXECUTED -> notExecuted++;
            }
        }
        if (executed != bounds.executedTransitions()
                || failed != bounds.failedTransitions()
                || notExecuted != bounds.notExecutedTransitions()) {
            throw new IllegalArgumentException(
                    "input timeline transition outcome counts are inconsistent");
        }
    }
}
