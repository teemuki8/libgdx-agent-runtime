package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Explicit bounded bidirectional runtime/UI bindings and cross-system frame mappings. */
public final class UiCorrelationRegistry {
    private final AgentRuntime runtime;
    private final UiCorrelationLimits limits;
    private final LinkedHashMap<String, UiBinding> bindings = new LinkedHashMap<>();
    private final ArrayDeque<UiFrameCorrelation> frames = new ArrayDeque<>();
    private long evictedFrames;

    UiCorrelationRegistry(AgentRuntime runtime, UiCorrelationLimits limits) {
        this.runtime = runtime;
        this.limits = limits;
    }

    /** Registers one explicit binding and returns its idempotent lifecycle handle. */
    public synchronized UiBindingRegistration register(UiBinding binding) {
        runtime.requireUiCorrelationMutation();
        Objects.requireNonNull(binding, "binding");
        requireConfiguredLengths(binding);
        if (bindings.containsKey(binding.id())) {
            throw new IllegalArgumentException("UI binding id is already registered");
        }
        if (bindings.size() >= limits.registeredBindings()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, "UI binding registration limit reached");
        }
        bindings.put(binding.id(), binding);
        return new Registration(binding.id());
    }

    /** Lists registered bindings in deterministic registration order. */
    public synchronized List<UiBinding> list() {
        return List.copyOf(bindings.values());
    }

    /** Returns whether explicit binding or frame-correlation evidence is registered. */
    public synchronized boolean available() {
        return !bindings.isEmpty() || !frames.isEmpty();
    }

    /** Resolves one runtime entity/property selector to semantic UI controls. */
    public synchronized UiBindingResult runtimeToUi(EntityId entityId, Optional<String> property,
            ExecutionEpochId epochId, FrameId frameId, Optional<String> uiGeneration, int limit) {
        Objects.requireNonNull(entityId, "entityId");
        requireString(entityId.value(), "runtime entity id");
        Optional<String> selectorProperty = Objects.requireNonNull(property, "property");
        selectorProperty.ifPresent(value -> requireString(value, "runtime property"));
        return resolve(binding -> binding.runtimeEntityId().equals(entityId)
                        && binding.runtimeProperty().equals(selectorProperty),
                epochId, frameId, uiGeneration, limit);
    }

    /** Resolves one semantic UI control to runtime entity/property selectors. */
    public synchronized UiBindingResult uiToRuntime(String uiSessionId, String uiControlId,
            ExecutionEpochId epochId, FrameId frameId, Optional<String> uiGeneration, int limit) {
        requireString(uiSessionId, "UI session id");
        requireString(uiControlId, "UI control id");
        return resolve(binding -> binding.uiSessionId().equals(uiSessionId)
                        && binding.uiControlId().equals(uiControlId),
                epochId, frameId, uiGeneration, limit);
    }

    /** Records one explicit runtime/UI frame mapping on the capture thread. */
    public synchronized void recordFrame(UiFrameCorrelation correlation) {
        runtime.requireUiCorrelationMutation();
        Objects.requireNonNull(correlation, "correlation");
        requireString(correlation.uiSessionId(), "UI session id");
        correlation.uiFrameId().ifPresent(value -> requireString(value, "UI frame id"));
        correlation.correlationToken().ifPresent(value ->
                requireString(value, "correlation token"));
        frames.addLast(correlation);
        while (frames.size() > limits.retainedFrameCorrelations()) {
            frames.removeFirst();
            evictedFrames++;
        }
    }

    /** Queries retained frame mappings for one UI session in insertion order. */
    public synchronized UiFrameCorrelationPage framesForUiSession(
            String uiSessionId, int limit) {
        requireString(uiSessionId, "UI session id");
        return framePage(value -> value.uiSessionId().equals(uiSessionId), limit);
    }

    /** Queries retained frame mappings carrying one explicit shared correlation token. */
    public synchronized UiFrameCorrelationPage framesForToken(String token, int limit) {
        requireString(token, "correlation token");
        return framePage(value -> value.correlationToken().filter(token::equals).isPresent(), limit);
    }

    synchronized List<UiFrameCorrelation> correlationsFor(
            ExecutionEpochId epochId, FrameId frameId) {
        return frames.stream()
                .filter(value -> value.runtimeEpochId().equals(epochId)
                        && value.runtimeFrameId().equals(frameId))
                .toList();
    }

    synchronized long evictedFrameCount() {
        return evictedFrames;
    }

    /** Returns configured effective limits. */
    public UiCorrelationLimits limits() {
        return limits;
    }

    private UiBindingResult resolve(Predicate<UiBinding> selector, ExecutionEpochId epochId,
            FrameId frameId, Optional<String> uiGeneration, int limit) {
        Objects.requireNonNull(epochId, "epochId");
        Objects.requireNonNull(frameId, "frameId");
        uiGeneration = Objects.requireNonNull(uiGeneration, "uiGeneration");
        uiGeneration.ifPresent(value -> requireString(value, "UI generation"));
        requireLimit(limit);
        List<UiBinding> candidates = bindings.values().stream().filter(selector).toList();
        if (candidates.isEmpty()) {
            return new UiBindingResult(
                    UiBindingStatus.MISSING, List.of(), 0, 0, limit, false);
        }
        Optional<String> generation = uiGeneration;
        List<UiBinding> active = candidates.stream()
                .filter(binding -> binding.validity().includes(epochId, frameId, generation))
                .toList();
        if (active.isEmpty()) {
            return new UiBindingResult(
                    UiBindingStatus.EXPIRED, List.of(), 0, 0, limit, false);
        }
        List<UiBinding> retained = active.stream().limit(limit).toList();
        return new UiBindingResult(active.size() > 1
                ? UiBindingStatus.AMBIGUOUS : UiBindingStatus.MATCHED,
                retained, active.size(), retained.size(), limit, active.size() > retained.size());
    }

    private UiFrameCorrelationPage framePage(Predicate<UiFrameCorrelation> selector, int limit) {
        requireLimit(limit);
        ArrayList<UiFrameCorrelation> matching = new ArrayList<>();
        for (UiFrameCorrelation correlation : frames) {
            if (selector.test(correlation)) {
                matching.add(correlation);
            }
        }
        int fromIndex = Math.max(0, matching.size() - limit);
        return new UiFrameCorrelationPage(
                List.copyOf(matching.subList(fromIndex, matching.size())),
                fromIndex > 0, evictedFrames);
    }

    private void requireConfiguredLengths(UiBinding binding) {
        requireString(binding.id(), "UI binding id");
        requireString(binding.runtimeEntityId().value(), "runtime entity id");
        binding.runtimeProperty().ifPresent(value -> requireString(value, "runtime property"));
        requireString(binding.uiSessionId(), "UI session id");
        requireString(binding.uiControlId(), "UI control id");
        binding.validity().uiGeneration().ifPresent(value -> requireString(value, "UI generation"));
    }

    private void requireString(String value, String name) {
        IdentifierSupport.validate(value, name);
        if (value.length() > limits.stringLength()) {
            throw new AgentRuntimeException(
                    RuntimeErrorCode.LIMIT_EXCEEDED, name + " exceeds configured length");
        }
    }

    private void requireLimit(int limit) {
        if (limit <= 0 || limit > limits.queryResults()) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "UI correlation query limit is outside configured range");
        }
    }

    synchronized void close() {
        bindings.clear();
        frames.clear();
    }

    private final class Registration implements UiBindingRegistration {
        private final String id;
        private boolean closed;

        private Registration(String id) {
            this.id = id;
        }

        @Override
        public void close() {
            synchronized (UiCorrelationRegistry.this) {
                if (closed) {
                    return;
                }
                runtime.requireUiCorrelationMutation();
                bindings.remove(id);
                closed = true;
            }
        }
    }
}
