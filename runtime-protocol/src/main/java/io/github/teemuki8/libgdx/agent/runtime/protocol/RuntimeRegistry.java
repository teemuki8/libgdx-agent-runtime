package io.github.teemuki8.libgdx.agent.runtime.protocol;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Instance-scoped publication registry; isolated registries are the default testing surface. */
public final class RuntimeRegistry {
    private static final int MAX_SESSIONS = 1_000;
    private static final class GlobalHolder {
        private static final RuntimeRegistry INSTANCE = new RuntimeRegistry();
    }

    private final Map<SessionId, WeakReference<AgentRuntime>> runtimes = new LinkedHashMap<>();

    /** Returns the optional process-wide convenience registry. */
    public static RuntimeRegistry global() {
        return GlobalHolder.INSTANCE;
    }

    /** Publishes a runtime weakly and rejects duplicate live session IDs. */
    public synchronized PublishedRuntime publish(AgentRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        cleanup();
        WeakReference<AgentRuntime> existing = runtimes.get(runtime.sessionId());
        if (existing != null && existing.get() != null) {
            throw new IllegalStateException(
                    "duplicate runtime session: " + runtime.sessionId().value());
        }
        if (runtimes.size() >= MAX_SESSIONS) {
            throw new IllegalStateException("runtime registry session limit reached");
        }
        WeakReference<AgentRuntime> reference = new WeakReference<>(runtime);
        runtimes.put(runtime.sessionId(), reference);
        return new Publication(this, runtime.sessionId(), reference);
    }

    /** Finds a live published runtime. */
    public synchronized Optional<AgentRuntime> find(SessionId id) {
        Objects.requireNonNull(id, "id");
        WeakReference<AgentRuntime> reference = runtimes.get(id);
        AgentRuntime runtime = reference == null ? null : reference.get();
        if (reference != null && runtime == null) {
            runtimes.remove(id);
        }
        return Optional.ofNullable(runtime);
    }

    /** Lists live publications in deterministic session-ID order. */
    public synchronized List<AgentRuntime> sessions() {
        cleanup();
        List<AgentRuntime> values = new ArrayList<>();
        runtimes.values().forEach(reference -> {
            AgentRuntime runtime = reference.get();
            if (runtime != null) {
                values.add(runtime);
            }
        });
        values.sort(Comparator.comparing(runtime -> runtime.sessionId().value()));
        return List.copyOf(values);
    }

    private synchronized void remove(
            SessionId id, WeakReference<AgentRuntime> expected) {
        runtimes.remove(id, expected);
    }

    private void cleanup() {
        runtimes.entrySet().removeIf(entry -> entry.getValue().get() == null);
    }

    private static final class Publication implements PublishedRuntime {
        private final RuntimeRegistry registry;
        private final SessionId sessionId;
        private final WeakReference<AgentRuntime> reference;
        private boolean closed;

        Publication(RuntimeRegistry registry, SessionId sessionId,
                WeakReference<AgentRuntime> reference) {
            this.registry = registry;
            this.sessionId = sessionId;
            this.reference = reference;
        }

        @Override public SessionId sessionId() {
            return sessionId;
        }

        @Override public synchronized void close() {
            if (!closed) {
                registry.remove(sessionId, reference);
                closed = true;
            }
        }
    }
}
