package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Optional explicit epoch, runtime-frame range, or UI-generation validity constraints. */
public record UiBindingValidity(Optional<ExecutionEpochId> executionEpochId,
        Optional<FrameRange> runtimeFrames, Optional<String> uiGeneration) {
    /** Validates immutable validity constraints. */
    public UiBindingValidity {
        executionEpochId = Objects.requireNonNull(executionEpochId, "executionEpochId");
        runtimeFrames = Objects.requireNonNull(runtimeFrames, "runtimeFrames");
        uiGeneration = Objects.requireNonNull(uiGeneration, "uiGeneration");
        uiGeneration.ifPresent(value -> IdentifierSupport.validate(value, "UI generation"));
    }

    /** Returns an unconstrained binding validity. */
    public static UiBindingValidity always() {
        return new UiBindingValidity(Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Constrains a binding to one execution epoch and inclusive runtime-frame range. */
    public static UiBindingValidity frames(ExecutionEpochId epochId, FrameRange frames) {
        return new UiBindingValidity(Optional.of(Objects.requireNonNull(epochId, "epochId")),
                Optional.of(Objects.requireNonNull(frames, "frames")), Optional.empty());
    }

    /** Constrains a binding to one application-provided UI generation. */
    public static UiBindingValidity generation(String value) {
        return new UiBindingValidity(Optional.empty(), Optional.empty(), Optional.of(value));
    }

    boolean includes(ExecutionEpochId epochId, FrameId frameId, Optional<String> generation) {
        return executionEpochId.map(epochId::equals).orElse(true)
                && runtimeFrames.map(range -> frameId.compareTo(range.from()) >= 0
                        && frameId.compareTo(range.to()) <= 0).orElse(true)
                && uiGeneration.map(value -> generation.filter(value::equals).isPresent())
                        .orElse(true);
    }
}
