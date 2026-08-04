package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Explicit immutable association between runtime state and one semantic UI control. */
public record UiBinding(String id, EntityId runtimeEntityId, Optional<String> runtimeProperty,
        String uiSessionId, String uiControlId, UiBindingValidity validity) {
    /** Validates stable binding identifiers and selector fields. */
    public UiBinding {
        IdentifierSupport.validate(id, "UI binding id");
        Objects.requireNonNull(runtimeEntityId, "runtimeEntityId");
        runtimeProperty = Objects.requireNonNull(runtimeProperty, "runtimeProperty");
        runtimeProperty.ifPresent(value -> IdentifierSupport.validate(value, "runtime property"));
        IdentifierSupport.validate(uiSessionId, "UI session id");
        IdentifierSupport.validate(uiControlId, "UI control id");
        Objects.requireNonNull(validity, "validity");
    }
}
