package io.github.teemuki8.libgdx.agent.runtime.core;

/** Application-owned scenario reset that explicitly receives optional deterministic inputs. */
@FunctionalInterface
public interface ScenarioResetHandler {
    /** Applies one reset; normal return acknowledges the supplied seed and configuration. */
    void reset(ScenarioResetContext context);
}
