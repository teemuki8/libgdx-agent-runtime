package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Capture-thread-owned registration surface. */
public final class EntityRegistry {
    private final AgentRuntime runtime;

    EntityRegistry(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Validates that an adapter may mutate the target behind an existing provider now.
     *
     * <p>This performs no mutation. Adapter registration handles use it to preserve the runtime's
     * capture-thread, open-frame, and closed-runtime guarantees while rebinding application-owned
     * objects.
     */
    public void requireProviderMutationAllowed() {
        runtime.requireMutableRegistration();
    }

    /** Registers one stable entity provider. */
    public EntityRegistration register(EntityId id, EntityType type, Supplier<String> displayName,
            Consumer<EntityInspector> declaration) {
        Objects.requireNonNull(declaration, "declaration");
        EntityInspector inspector = new EntityInspector();
        declaration.accept(inspector);
        return runtime.registerStatic(
                new InspectableEntity(id, type, displayName, inspector.build()));
    }

    /** Registers a dynamic collection evaluated on each capture. */
    public EntityRegistration registerSource(String name,
            Supplier<? extends Stream<InspectableEntity>> source) {
        return runtime.registerSource(name, source);
    }
}
