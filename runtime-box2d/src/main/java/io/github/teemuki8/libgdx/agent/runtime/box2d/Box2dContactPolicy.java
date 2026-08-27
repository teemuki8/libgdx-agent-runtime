package io.github.teemuki8.libgdx.agent.runtime.box2d;

/** Explicit Box2D 3 post-step event phases retained as structured contact evidence. */
public record Box2dContactPolicy(boolean begin, boolean end, boolean postSolve) {
    /** Retains begin, end, and hit-derived post-solve evidence. */
    public static Box2dContactPolicy developmentDefaults() {
        return new Box2dContactPolicy(true, true, true);
    }
}
