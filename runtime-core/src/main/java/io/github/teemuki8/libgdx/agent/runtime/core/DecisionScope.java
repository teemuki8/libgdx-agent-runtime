package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;

/** Capture-thread-owned semantic decision scope. */
public interface DecisionScope extends AutoCloseable {
    /** Records an excluded candidate. */
    void reject(EntityId candidate, Reason reason, RuntimeValue.Field... attributes);

    /** Records an eligible candidate. */
    void accept(EntityId candidate, Reason reason, RuntimeValue.Field... attributes);

    /** Records an eligible candidate with the stable default reason {@code eligible}. */
    default void accept(EntityId candidate, RuntimeValue.Field... attributes) {
        accept(candidate, Reason.of("eligible"), attributes);
    }

    /** Records the chosen candidate and stable reason. */
    void choose(EntityId candidate, Reason reason);

    /** Returns the reserved trace ID, if capture is enabled. */
    java.util.Optional<DecisionId> id();

    /** Completes the scope normally. */
    @Override
    void close();

    /** Convenience for attribute lists. */
    default void reject(EntityId candidate, Reason reason, List<RuntimeValue.Field> attributes) {
        reject(candidate, reason, attributes.toArray(RuntimeValue.Field[]::new));
    }
}
