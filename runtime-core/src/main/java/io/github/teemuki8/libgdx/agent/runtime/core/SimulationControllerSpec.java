package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Explicit application-owned pause, resume, tick, and named-condition callbacks. */
public final class SimulationControllerSpec {
    private final Runnable pause;
    private final Runnable resume;
    private final LongConsumer tick;
    private final List<Condition> conditions;

    private SimulationControllerSpec(Runnable pause, Runnable resume, LongConsumer tick,
            List<Condition> conditions) {
        this.pause = pause;
        this.resume = resume;
        this.tick = tick;
        this.conditions = List.copyOf(conditions);
    }

    /** Starts a simulation controller specification. */
    public static Builder builder() {
        return new Builder();
    }

    Runnable pause() {
        return pause;
    }

    Runnable resume() {
        return resume;
    }

    LongConsumer tick() {
        return tick;
    }

    List<Condition> conditions() {
        return conditions;
    }

    /** Builder for one explicit controller. */
    public static final class Builder {
        private Runnable pause;
        private Runnable resume;
        private LongConsumer tick;
        private final List<Condition> conditions = new ArrayList<>();

        private Builder() {}

        /** Sets the application callback that gates normal simulation updates. */
        public Builder pause(Runnable value) {
            pause = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets the application callback that resumes normal simulation updates. */
        public Builder resume(Runnable value) {
            resume = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets the application-defined deterministic tick callback. */
        public Builder tick(LongConsumer value) {
            tick = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Adds one stable named condition evaluated after completed controlled ticks. */
        public Builder condition(
                String id, String description, BooleanSupplier predicate) {
            conditions.add(new Condition(id, description, predicate));
            return this;
        }

        /** Builds the complete controller specification. */
        public SimulationControllerSpec build() {
            return new SimulationControllerSpec(
                    Objects.requireNonNull(pause, "pause"),
                    Objects.requireNonNull(resume, "resume"),
                    Objects.requireNonNull(tick, "tick"), conditions);
        }
    }

    record Condition(String id, String description, BooleanSupplier predicate) {
        Condition {
            IdentifierSupport.validate(id, "condition id");
            Objects.requireNonNull(description, "description");
            if (description.isBlank() || description.length() > 512) {
                throw new IllegalArgumentException("condition description is invalid");
            }
            Objects.requireNonNull(predicate, "predicate");
        }
    }
}
