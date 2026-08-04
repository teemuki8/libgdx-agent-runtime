package io.github.teemuki8.libgdx.agent.runtime.core;

/** Application-selected recording policy for one explicitly registered input type. */
public enum InputRedactionPolicy {
    /** Retain validated parameters with injection evidence. */
    INCLUDE_PARAMETERS,
    /** Retain that the registered input occurred but omit its parameters. */
    OMIT_PARAMETERS
}
