package io.github.teemuki8.libgdx.agent.runtime.box2d;

/** Explicit callback phases retained as structured Box2D contact evidence. */
public record Box2dContactPolicy(boolean begin, boolean end, boolean preSolve,
        boolean postSolve) {
    /** Retains begin, end, and post-solve evidence while omitting noisy pre-solve callbacks. */
    public static Box2dContactPolicy developmentDefaults() {
        return new Box2dContactPolicy(true, true, false, true);
    }
}
