package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Ordered bounded specification for one exact-tick input timeline. */
public record InputTimelineSpec(int totalTicks, List<InputTimelineTransition> transitions) {
    /** Copies transitions and validates range, stable ordering, and unique identities. */
    public InputTimelineSpec {
        if (totalTicks <= 0 || totalTicks > InputTimelineLimits.MAXIMUM_TICKS) {
            throw new IllegalArgumentException("input timeline tick count is outside the bound");
        }
        Objects.requireNonNull(transitions, "transitions");
        if (transitions.isEmpty()
                || transitions.size() > InputTimelineLimits.MAXIMUM_TRANSITIONS) {
            throw new IllegalArgumentException(
                    "input timeline transition count is outside the bound");
        }
        transitions.forEach(value -> Objects.requireNonNull(value, "transition"));
        transitions = List.copyOf(transitions);
        Set<String> transitionIds = new HashSet<>();
        int priorTick = 0;
        for (InputTimelineTransition transition : transitions) {
            if (transition.timelineTick() > totalTicks) {
                throw new IllegalArgumentException("input timeline transition tick is out of range");
            }
            if (transition.timelineTick() < priorTick) {
                throw new IllegalArgumentException("input timeline transition order is unstable");
            }
            if (!transitionIds.add(transition.transitionId())) {
                throw new IllegalArgumentException("duplicate input timeline transition id");
            }
            priorTick = transition.timelineTick();
        }
    }
}
