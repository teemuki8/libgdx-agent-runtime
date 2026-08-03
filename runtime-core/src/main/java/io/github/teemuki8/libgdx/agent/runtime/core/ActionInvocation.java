package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Correlated semantic action status and completed-frame evidence. */
public record ActionInvocation(String actionId, String requestId, CommandLookup command,
        Optional<FrameId> submittedFrameId, Optional<FrameId> completedFrameId,
        Optional<String> correlationId) {
    public ActionInvocation {
        IdentifierSupport.validate(actionId, "action id");
        IdentifierSupport.validate(requestId, "request id");
        Objects.requireNonNull(command, "command");
        submittedFrameId = Objects.requireNonNull(submittedFrameId, "submittedFrameId");
        completedFrameId = Objects.requireNonNull(completedFrameId, "completedFrameId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        correlationId.ifPresent(value -> IdentifierSupport.validate(value, "correlation id"));
    }
}
