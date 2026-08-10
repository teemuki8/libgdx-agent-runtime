package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** At-most-once parent command lookup and optional terminal input timeline result. */
public record InputTimelineOperation(String requestId, CommandLookup command,
        Optional<InputTimelineResult> result) {
    /** Validates immutable operation identity and evidence containers. */
    public InputTimelineOperation {
        IdentifierSupport.validate(requestId, "input timeline request id");
        Objects.requireNonNull(command, "command");
        result = Objects.requireNonNull(result, "result");
        if (command.status().isPresent()) {
            CommandStatus status = command.status().orElseThrow();
            if (!status.requestId().equals(requestId)) {
                throw new IllegalArgumentException(
                        "input timeline command request id is inconsistent");
            }
            if (status.outcomeKnown() != result.isPresent()) {
                throw new IllegalArgumentException(
                        "input timeline command and result lifecycle are inconsistent");
            }
            if (status.state() != CommandState.SUCCEEDED
                    && result.map(InputTimelineResult::stopReason)
                            .filter(reason -> reason == InputTimelineStopReason.COMPLETED)
                            .isPresent()) {
                throw new IllegalArgumentException(
                        "completed input timeline requires a succeeded command");
            }
        }
    }
}
