package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Explicit, bounded registry of application-owned reset callbacks. */
public final class ScenarioRegistry {
    private final AgentRuntime runtime;
    private final ScenarioLimits limits;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> requests = new LinkedHashMap<>();
    private final LinkedHashMap<String, Baseline> completed = new LinkedHashMap<>();

    ScenarioRegistry(AgentRuntime runtime, ScenarioLimits limits) {
        this.runtime = runtime;
        this.limits = limits;
    }

    /** Registers one stable scenario and its application-owned reset callback. */
    public synchronized void register(String id, Runnable reset) {
        register(new ScenarioDescriptor(id, Optional.empty()), reset);
    }

    /** Registers one described scenario and its application-owned reset callback. */
    public synchronized void register(String id, String description, Runnable reset) {
        register(new ScenarioDescriptor(id, description), reset);
    }

    /** Registers one scenario descriptor. Duplicate IDs are rejected. */
    public synchronized void register(ScenarioDescriptor descriptor, Runnable reset) {
        runtime.requireScenarioRegistration();
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(reset, "reset");
        if (entries.containsKey(descriptor.id())) {
            throw new IllegalArgumentException("scenario id is already registered");
        }
        if (entries.size() >= limits.registeredScenarios()) {
            throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                    "registered scenario limit reached");
        }
        entries.put(descriptor.id(), new Entry(descriptor, reset));
    }

    /** Lists immutable metadata in registration order. */
    public synchronized List<ScenarioDescriptor> list() {
        return entries.values().stream().map(Entry::descriptor).toList();
    }

    /** Resets a registered scenario through application command dispatch. */
    public ScenarioReset reset(String scenarioId, String requestId, Duration timeout) {
        Entry entry;
        CommandDispatch dispatch = runtime.commands().orElseThrow(() ->
                new IllegalStateException("scenario reset requires application command dispatch"));
        synchronized (this) {
            entry = entries.get(scenarioId);
            if (entry == null) {
                throw new IllegalArgumentException("unknown scenario id");
            }
            String previous = requests.get(requestId);
            if (previous != null && !previous.equals(scenarioId)) {
                throw new IllegalArgumentException("request id is already bound to another scenario");
            }
            if (previous == null && dispatch.status(requestId).kind() != CommandLookup.Kind.UNKNOWN) {
                throw new IllegalArgumentException(
                        "request id is no longer correlated with retained scenario evidence");
            }
            requests.putIfAbsent(requestId, scenarioId);
            trim(requests);
        }
        CommandLookup lookup = dispatch.submit(requestId, timeout, () -> {
            FrameId frame = runtime.executeScenarioReset(entry.reset());
            synchronized (ScenarioRegistry.this) {
                completed.put(requestId, new Baseline(runtime.currentEpoch(), frame));
                trim(completed);
            }
        });
        Baseline baseline;
        synchronized (this) {
            baseline = completed.get(requestId);
        }
        return new ScenarioReset(scenarioId, lookup,
                Optional.ofNullable(baseline).map(Baseline::epoch),
                Optional.ofNullable(baseline).map(Baseline::frame));
    }

    public ScenarioLimits limits() {
        return limits;
    }

    private <V> void trim(LinkedHashMap<String, V> values) {
        while (values.size() > limits.retainedResetResults()) {
            values.remove(values.keySet().iterator().next());
        }
    }

    private record Entry(ScenarioDescriptor descriptor, Runnable reset) {}
    private record Baseline(ExecutionEpochId epoch, FrameId frame) {}
}
