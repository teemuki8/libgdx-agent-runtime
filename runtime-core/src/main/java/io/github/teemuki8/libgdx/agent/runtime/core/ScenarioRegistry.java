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
    private final LinkedHashMap<String, CatalogEntry> entries = new LinkedHashMap<>();
    private final LinkedHashMap<String, ScenarioResetHandler> resetHandlers = new LinkedHashMap<>();
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

    /** Registers one stable scenario that accepts explicit deterministic reset inputs. */
    public synchronized void register(String id, ScenarioResetHandler reset) {
        register(new ScenarioDescriptor(id, Optional.empty()), reset);
    }

    /** Registers one described scenario and its application-owned reset callback. */
    public synchronized void register(String id, String description, Runnable reset) {
        register(new ScenarioDescriptor(id, description), reset);
    }

    /** Registers one described scenario that accepts explicit deterministic reset inputs. */
    public synchronized void register(
            String id, String description, ScenarioResetHandler reset) {
        register(new ScenarioDescriptor(id, description), reset);
    }

    /** Registers one scenario descriptor. Duplicate IDs are rejected. */
    public synchronized void register(ScenarioDescriptor descriptor, Runnable reset) {
        Objects.requireNonNull(reset, "reset");
        register(descriptor, ignored -> reset.run(), false);
    }

    /** Registers a descriptor and deterministic reset-input handler. */
    public synchronized void register(
            ScenarioDescriptor descriptor, ScenarioResetHandler reset) {
        register(descriptor, reset, true);
    }

    private void register(
            ScenarioDescriptor descriptor, ScenarioResetHandler reset, boolean deterministic) {
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
        entries.put(descriptor.id(), new CatalogEntry(descriptor, deterministic));
        resetHandlers.put(descriptor.id(), reset);
    }

    /** Lists immutable metadata in registration order. */
    public synchronized List<ScenarioDescriptor> list() {
        return entries.values().stream().map(CatalogEntry::descriptor).toList();
    }

    /** Reports whether any scenario acknowledges deterministic reset inputs. */
    public synchronized boolean determinismAvailable() {
        return entries.values().stream().anyMatch(CatalogEntry::deterministic);
    }

    /** Resets a registered scenario through application command dispatch. */
    public ScenarioReset reset(String scenarioId, String requestId, Duration timeout) {
        runtime.requireSubmissionsOpen();
        CatalogEntry entry;
        ScenarioResetHandler reset;
        boolean existing;
        CommandDispatch dispatch = runtime.commands().orElseThrow(() ->
                new IllegalStateException("scenario reset requires application command dispatch"));
        requireValidTimeout(timeout, dispatch.limits().maximumTimeoutNanos());
        synchronized (this) {
            entry = entries.get(scenarioId);
            if (entry == null) {
                throw new IllegalArgumentException("unknown scenario id");
            }
            reset = resetHandlers.get(scenarioId);
            String previous = requests.get(requestId);
            existing = previous != null;
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
        if (existing) {
            Baseline baseline;
            synchronized (this) {
                baseline = completed.get(requestId);
            }
            return new ScenarioReset(scenarioId, dispatch.status(requestId),
                    Optional.ofNullable(baseline).map(Baseline::epoch),
                    Optional.ofNullable(baseline).map(Baseline::frame));
        }
        CommandLookup lookup = dispatch.submit(requestId, timeout, () -> {
            FrameId frame = runtime.executeScenarioReset(
                    () -> reset.reset(ScenarioResetContext.ordinary()));
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

    FrameId resetForDeterminism(String scenarioId, ScenarioResetContext context) {
        ScenarioResetHandler reset;
        synchronized (this) {
            CatalogEntry entry = entries.get(scenarioId);
            if (entry == null) {
                throw new IllegalArgumentException("unknown scenario id");
            }
            if (!entry.deterministic()) {
                throw new IllegalArgumentException(
                        "scenario does not acknowledge deterministic reset inputs");
            }
            reset = resetHandlers.get(scenarioId);
        }
        return runtime.executeScenarioReset(() -> reset.reset(context));
    }

    public ScenarioLimits limits() {
        return limits;
    }

    /** Package-private close observation: number of retained application reset handlers. */
    synchronized int retainedResetCallbacks() {
        return resetHandlers.size();
    }

    /** Package-private close observation: number of retained pending reset requests. */
    synchronized int retainedPendingResets() {
        return requests.size();
    }

    /** Releases application reset callbacks and pending reset requests, keeping the catalog. */
    synchronized void close() {
        resetHandlers.clear();
        requests.clear();
    }

    private static void requireValidTimeout(Duration timeout, long maximumNanos) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        final long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("timeout exceeds the supported range", failure);
        }
        if (nanos > maximumNanos) {
            throw new IllegalArgumentException("timeout exceeds the configured limit");
        }
    }

    private <V> void trim(LinkedHashMap<String, V> values) {
        while (values.size() > limits.retainedResetResults()) {
            values.remove(values.keySet().iterator().next());
        }
    }

    private record CatalogEntry(ScenarioDescriptor descriptor, boolean deterministic) {}
    private record Baseline(ExecutionEpochId epoch, FrameId frame) {}
}
