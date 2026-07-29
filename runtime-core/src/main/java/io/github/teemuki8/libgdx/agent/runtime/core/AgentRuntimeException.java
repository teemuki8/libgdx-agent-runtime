package io.github.teemuki8.libgdx.agent.runtime.core;

import java.io.Serial;
import java.util.Objects;

/** Typed programmer or query failure from the core runtime. */
@SuppressWarnings("serial")
public final class AgentRuntimeException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final RuntimeErrorCode code;

    /** Creates a typed failure. */
    public AgentRuntimeException(RuntimeErrorCode code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    /** Returns the stable failure category. */
    public RuntimeErrorCode code() {
        return code;
    }
}
