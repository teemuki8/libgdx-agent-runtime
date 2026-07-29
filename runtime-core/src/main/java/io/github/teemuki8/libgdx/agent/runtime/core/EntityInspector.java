package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Typed builder used once to declare an entity's allowlisted properties. */
public final class EntityInspector {
    private final List<PropertyProvider> properties = new ArrayList<>();

    /** Registers a property that already returns a safe runtime value. */
    public EntityInspector property(String name, Supplier<? extends RuntimeValue> provider) {
        properties.add(new PropertyProvider(
                IdentifierSupport.validate(name, "property"), Objects.requireNonNull(provider)));
        return this;
    }

    /** Registers an integral property without allocating when capture is disabled. */
    public EntityInspector property(String name, LongSupplier provider) {
        Objects.requireNonNull(provider, "provider");
        return property(name, () -> RuntimeValues.integer(provider.getAsLong()));
    }

    /** Registers a boolean property without allocating when capture is disabled. */
    public EntityInspector property(String name, BooleanSupplier provider) {
        Objects.requireNonNull(provider, "provider");
        return property(name, () -> RuntimeValues.bool(provider.getAsBoolean()));
    }

    List<PropertyProvider> build() {
        return List.copyOf(properties);
    }

    record PropertyProvider(String name, Supplier<? extends RuntimeValue> provider) {}
}
