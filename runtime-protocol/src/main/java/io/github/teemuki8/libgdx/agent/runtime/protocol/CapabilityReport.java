package io.github.teemuki8.libgdx.agent.runtime.protocol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Extension-aware runtime version and bounded capability catalog. */
public record CapabilityReport(String runtimeVersion, List<RuntimeCapability> capabilities) {
    private static final int MAX_CAPABILITIES = 64;

    /** Validates and orders the capability catalog by stable ID. */
    public CapabilityReport {
        runtimeVersion = ProtocolJson.requireIdentifier(runtimeVersion, "runtime version");
        ArrayList<RuntimeCapability> ordered = new ArrayList<>(
                Objects.requireNonNull(capabilities, "capabilities"));
        if (ordered.size() > MAX_CAPABILITIES) {
            throw new IllegalArgumentException("too many runtime capabilities");
        }
        ordered.sort(Comparator.comparing(RuntimeCapability::id));
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index - 1).id().equals(ordered.get(index).id())) {
                throw new IllegalArgumentException(
                        "duplicate runtime capability: " + ordered.get(index).id());
            }
        }
        capabilities = List.copyOf(ordered);
    }
}
