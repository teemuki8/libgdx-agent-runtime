package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.OptionalLong;

/** Explicit application-owned inputs for one scenario reset. */
public record ScenarioResetContext(OptionalLong randomSeed,
        RuntimeValue.ObjectValue configuration) {
    /** Validates immutable reset inputs. */
    public ScenarioResetContext {
        randomSeed = Objects.requireNonNull(randomSeed, "randomSeed");
        Objects.requireNonNull(configuration, "configuration");
    }

    /** Creates an ordinary reset without determinism inputs. */
    public static ScenarioResetContext ordinary() {
        return new ScenarioResetContext(OptionalLong.empty(), RuntimeValues.object());
    }
}
