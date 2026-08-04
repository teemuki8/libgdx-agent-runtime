package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Explicit included and excluded observable scopes for snapshot equivalence. */
public record SnapshotComparisonScope(List<EntityId> entityIds, List<String> properties,
        List<String> excludedProperties, boolean includeEvents, boolean includeDecisions) {
    /** Validates, copies, and deterministically orders selectors. */
    public SnapshotComparisonScope {
        entityIds = Objects.requireNonNull(entityIds, "entityIds").stream()
                .sorted().distinct().toList();
        properties = identifiers(properties, "property");
        excludedProperties = identifiers(excludedProperties, "excluded property");
        if (entityIds.size() > AssertionScope.MAX_EVIDENCE
                || properties.size() > AssertionScope.MAX_EVIDENCE
                || excludedProperties.size() > AssertionScope.MAX_EVIDENCE) {
            throw new IllegalArgumentException("snapshot comparison selector limit exceeded");
        }
        if (entityIds.isEmpty() && properties.isEmpty() && !includeEvents && !includeDecisions) {
            throw new IllegalArgumentException("snapshot comparison scope has no included observable");
        }
        if (properties.stream().anyMatch(excludedProperties::contains)) {
            throw new IllegalArgumentException("snapshot property cannot be both included and excluded");
        }
    }

    private static List<String> identifiers(List<String> values, String name) {
        return Objects.requireNonNull(values, name).stream()
                .map(value -> IdentifierSupport.validate(value, name))
                .sorted(Comparator.naturalOrder()).distinct().toList();
    }
}
