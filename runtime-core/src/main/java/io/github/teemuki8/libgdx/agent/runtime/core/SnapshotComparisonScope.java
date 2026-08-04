package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Explicit included and excluded observable scopes for snapshot equivalence. */
public record SnapshotComparisonScope(List<EntityId> entityIds, List<String> properties,
        List<String> excludedProperties, boolean includeEvents, boolean includeDecisions) {
    /** Validates, copies, and deterministically orders selectors. */
    public SnapshotComparisonScope {
        entityIds = entities(entityIds);
        properties = identifiers(properties, "property");
        excludedProperties = identifiers(excludedProperties, "excluded property");
        if (entityIds.isEmpty() && properties.isEmpty() && !includeEvents && !includeDecisions) {
            throw new IllegalArgumentException("snapshot comparison scope has no included observable");
        }
        if (properties.stream().anyMatch(excludedProperties::contains)) {
            throw new IllegalArgumentException("snapshot property cannot be both included and excluded");
        }
    }

    private static List<EntityId> entities(List<EntityId> values) {
        Objects.requireNonNull(values, "entityIds");
        requireSelectorLimit(values.size());
        return values.stream().map(value -> Objects.requireNonNull(value, "entityId"))
                .sorted().distinct().toList();
    }

    private static List<String> identifiers(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        requireSelectorLimit(values.size());
        return values.stream().map(value -> IdentifierSupport.validate(value, name))
                .sorted(Comparator.naturalOrder()).distinct().toList();
    }

    private static void requireSelectorLimit(int size) {
        if (size > AssertionScope.MAX_EVIDENCE) {
            throw new IllegalArgumentException("snapshot comparison selector limit exceeded");
        }
    }
}
