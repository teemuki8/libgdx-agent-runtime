package io.github.teemuki8.libgdx.agent.runtime.box2d;

import java.util.Objects;
import java.util.Optional;

/** Explicit fixture metadata that libGDX cannot recover from every native fixture wrapper. */
public record Box2dFixtureSpec(Optional<Boolean> chainLoop) {
    /** Copies and validates optional application testimony. */
    public Box2dFixtureSpec {
        chainLoop = Objects.requireNonNull(chainLoop, "chainLoop");
    }

    /** Returns a specification with no additional testimony. */
    public static Box2dFixtureSpec unspecified() {
        return new Box2dFixtureSpec(Optional.empty());
    }

    /** Returns explicit chain loop-state testimony. */
    public static Box2dFixtureSpec chainLoop(boolean loop) {
        return new Box2dFixtureSpec(Optional.of(loop));
    }
}
