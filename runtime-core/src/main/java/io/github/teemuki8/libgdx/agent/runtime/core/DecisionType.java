package io.github.teemuki8.libgdx.agent.runtime.core;

/** Stable game-supplied semantic decision type. */
public record DecisionType(String value) {
    /** Validates the identifier. */
    public DecisionType {
        value = IdentifierSupport.validate(value, "decisionType");
    }

    /** Creates a validated type. */
    public static DecisionType of(String value) {
        return new DecisionType(value);
    }
}
