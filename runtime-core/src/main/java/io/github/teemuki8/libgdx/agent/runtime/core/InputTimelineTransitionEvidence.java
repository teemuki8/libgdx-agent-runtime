package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Redaction-safe immutable outcome evidence for one input timeline transition. */
public record InputTimelineTransitionEvidence(String transitionId, int timelineTick,
        String inputId, InputTimelineTransitionState state,
        Optional<InputInjection> injection, Optional<String> diagnostic) {
    /** Validates identity, attempted evidence, and bounded diagnostics. */
    public InputTimelineTransitionEvidence {
        IdentifierSupport.validate(transitionId, "input timeline transition id");
        if (timelineTick <= 0) {
            throw new IllegalArgumentException("input timeline evidence tick must be positive");
        }
        IdentifierSupport.validate(inputId, "input timeline input id");
        Objects.requireNonNull(state, "state");
        injection = Objects.requireNonNull(injection, "injection");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        diagnostic.ifPresent(value -> {
            if (value.isBlank()
                    || value.length() > ApplicationFailureEvidence.LEGACY_ENVELOPE_CAPACITY) {
                throw new IllegalArgumentException(
                        "input timeline transition diagnostic is outside the public bound");
            }
        });
        if (state == InputTimelineTransitionState.NOT_EXECUTED) {
            if (injection.isPresent() || diagnostic.isEmpty()) {
                throw new IllegalArgumentException(
                        "unattempted input timeline evidence is inconsistent");
            }
        } else {
            InputInjection attempted = injection.orElseThrow(() -> new IllegalArgumentException(
                    "attempted input timeline evidence requires an injection"));
            if (!attempted.requestId().equals(transitionId)
                    || !attempted.inputId().equals(inputId)
                    || state == InputTimelineTransitionState.EXECUTED
                            && attempted.state() != InputInjectionState.EXECUTED
                    || state == InputTimelineTransitionState.FAILED
                            && attempted.state() != InputInjectionState.FAILED) {
                throw new IllegalArgumentException(
                        "input timeline transition injection is inconsistent");
            }
        }
    }
}
