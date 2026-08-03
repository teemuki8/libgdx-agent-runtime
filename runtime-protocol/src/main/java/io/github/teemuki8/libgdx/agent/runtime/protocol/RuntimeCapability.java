package io.github.teemuki8.libgdx.agent.runtime.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** One bounded, transport-aware runtime capability descriptor. */
public record RuntimeCapability(
        String id,
        ProtocolVersion capabilityVersion,
        Availability availability,
        Optional<String> unavailableReason,
        Access access,
        List<String> javaApis,
        List<String> protocolCommands,
        List<String> mcpTools,
        Map<String, Long> limits,
        List<String> modes,
        List<String> requiredCapabilities) {
    private static final int MAX_METADATA_ITEMS = 64;

    /** Whether a concrete implementation is available for the session. */
    public enum Availability {
        /** The capability can be used. */
        AVAILABLE,
        /** The capability is known but cannot be used. */
        UNAVAILABLE
    }

    /** Whether using the capability can mutate application state. */
    public enum Access {
        /** The capability only reads completed evidence. */
        READ_ONLY,
        /** The capability may mutate or advance application state. */
        MUTATING
    }

    /** Validates, orders, and copies descriptor metadata. */
    public RuntimeCapability {
        id = ProtocolJson.requireIdentifier(id, "capability id");
        Objects.requireNonNull(capabilityVersion, "capabilityVersion");
        Objects.requireNonNull(availability, "availability");
        unavailableReason = Objects.requireNonNull(unavailableReason, "unavailableReason");
        unavailableReason.ifPresent(reason ->
                ProtocolJson.requireIdentifier(reason, "unavailable reason"));
        if ((availability == Availability.AVAILABLE) == unavailableReason.isPresent()) {
            throw new IllegalArgumentException(
                    "capability availability and unavailable reason disagree");
        }
        Objects.requireNonNull(access, "access");
        javaApis = orderedIdentifiers(javaApis, "Java API");
        protocolCommands = orderedIdentifiers(protocolCommands, "protocol command");
        mcpTools = orderedIdentifiers(mcpTools, "MCP tool");
        limits = orderedLimits(limits);
        modes = orderedIdentifiers(modes, "capability mode");
        requiredCapabilities = orderedIdentifiers(
                requiredCapabilities, "required capability");
    }

    private static List<String> orderedIdentifiers(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_METADATA_ITEMS) {
            throw new IllegalArgumentException("too many " + name + " values");
        }
        ArrayList<String> ordered = new ArrayList<>(values.size());
        values.forEach(value -> ordered.add(ProtocolJson.requireIdentifier(value, name)));
        Collections.sort(ordered);
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index - 1).equals(ordered.get(index))) {
                throw new IllegalArgumentException("duplicate " + name + ": " + ordered.get(index));
            }
        }
        return List.copyOf(ordered);
    }

    private static Map<String, Long> orderedLimits(Map<String, Long> values) {
        Objects.requireNonNull(values, "limits");
        if (values.size() > MAX_METADATA_ITEMS) {
            throw new IllegalArgumentException("too many capability limits");
        }
        TreeMap<String, Long> ordered = new TreeMap<>();
        values.forEach((name, value) -> {
            String validated = ProtocolJson.requireIdentifier(name, "capability limit");
            Objects.requireNonNull(value, "capability limit value");
            if (value < 0) {
                throw new IllegalArgumentException("capability limits must be non-negative");
            }
            ordered.put(validated, value);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }
}
