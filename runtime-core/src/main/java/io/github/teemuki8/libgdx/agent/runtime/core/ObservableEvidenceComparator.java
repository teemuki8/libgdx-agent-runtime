package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Package-private normalized observable capture and first-difference comparison. */
final class ObservableEvidenceComparator {
    private final AgentRuntime runtime;

    ObservableEvidenceComparator(AgentRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    Optional<String> configurationProblem(FrameSnapshot frame,
            List<SimulationConfigurationRequirement> requirements) {
        if (!frame.stats().diagnostics().isEmpty() || !frame.stats().truncations().isEmpty()) {
            return Optional.of("configuration baseline contains diagnostics or truncation");
        }
        for (SimulationConfigurationRequirement requirement : requirements) {
            Optional<EntitySnapshot> entity = entity(frame, requirement.entityId());
            if (entity.isEmpty() || entity.orElseThrow().truncated()) {
                return Optional.of("configuration requirement evidence is missing or truncated: "
                        + requirement.entityId().value() + ':' + requirement.property());
            }
            Optional<RuntimeValue> observed = property(
                    frame, requirement.entityId(), requirement.property());
            if (!observed.equals(Optional.of(requirement.expected()))) {
                return Optional.of("configuration requirement does not match baseline: "
                        + requirement.entityId().value() + ':' + requirement.property());
            }
        }
        return Optional.empty();
    }

    Optional<String> evidenceProblem(FrameSnapshot frame,
            List<SimulationEvidenceRequirement> requirements) {
        for (SimulationEvidenceRequirement requirement : requirements) {
            Optional<EntitySnapshot> entity = entity(frame, requirement.entityId());
            if (entity.isEmpty() || entity.orElseThrow().truncated()) {
                return Optional.of("selected simulation evidence is missing or truncated: "
                        + requirement.entityId().value() + ':' + requirement.property());
            }
            Optional<RuntimeValue> observed = property(
                    frame, requirement.entityId(), requirement.property());
            if (!observed.equals(Optional.of(RuntimeValues.bool(true)))) {
                return Optional.of("selected simulation evidence is incomplete: "
                        + requirement.entityId().value() + ':' + requirement.property());
            }
        }
        return Optional.empty();
    }

    Optional<String> selectionProblem(FrameSnapshot frame, SnapshotComparisonScope scope) {
        for (EntityId entityId : scope.entityIds()) {
            Optional<EntitySnapshot> entity = frame.entities().stream()
                    .filter(candidate -> candidate.id().equals(entityId)).findFirst();
            if (entity.isEmpty()) {
                return Optional.of("selected simulation entity is missing: " + entityId.value());
            }
            if (entity.orElseThrow().truncated()) {
                return Optional.of(
                        "selected simulation entity is truncated: " + entityId.value());
            }
        }
        for (String property : scope.properties()) {
            boolean present = frame.entities().stream()
                    .filter(entity -> scope.entityIds().isEmpty()
                            || scope.entityIds().contains(entity.id()))
                    .anyMatch(entity -> entity.property(property).isPresent());
            if (!present) {
                return Optional.of(
                        "selected simulation property is missing: " + property);
            }
        }
        return Optional.empty();
    }

    Optional<FrameEvidence> capture(FrameSnapshot frame, DeterminismProfile profile,
            List<EventType> eventTypes, Limits limits, Counters counters) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(eventTypes, "eventTypes");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(counters, "counters");
        SnapshotComparisonScope scope = profile.comparisonScope();
        int selectedEntities = 0;
        long selectedFacts = 0;
        long entitiesBytes = 0;
        for (EntitySnapshot entity : frame.entities()) {
            if (!scope.entityIds().isEmpty() && !scope.entityIds().contains(entity.id())) {
                continue;
            }
            selectedEntities++;
            if (counters.observedEntities + selectedEntities > limits.maximumEntitiesPerFrame()) {
                return countOverrun(counters, selectedEntities, selectedFacts, limits,
                        "determinism entity count limit exceeded");
            }
            entitiesBytes = DeterminismCanonicalSize.add(entitiesBytes,
                    DeterminismCanonicalSize.entity(
                            entity.id(), entity.type(), entity.displayName()));
            long propertyBytes = 0;
            for (RuntimeValue.Field property : entity.properties()) {
                if ((scope.properties().isEmpty()
                        || scope.properties().contains(property.name()))
                        && !scope.excludedProperties().contains(property.name())) {
                    selectedFacts++;
                    if (counters.observedFacts + selectedFacts
                            > limits.maximumFactsPerFrame()) {
                        return countOverrun(counters, selectedEntities, selectedFacts, limits,
                                "determinism fact count limit exceeded");
                    }
                    propertyBytes = DeterminismCanonicalSize.add(propertyBytes,
                            DeterminismCanonicalSize.field(property));
                }
            }
            entitiesBytes = DeterminismCanonicalSize.add(entitiesBytes,
                    DeterminismCanonicalSize.listPrefix());
            entitiesBytes = DeterminismCanonicalSize.add(entitiesBytes, propertyBytes);
            entitiesBytes = DeterminismCanonicalSize.add(entitiesBytes,
                    DeterminismCanonicalSize.listPrefix());
        }
        long eventsBytes = 0;
        if (scope.includeEvents()) {
            for (RuntimeEvent event : selectedEvents(frame, eventTypes)) {
                selectedFacts++;
                if (counters.observedFacts + selectedFacts > limits.maximumFactsPerFrame()) {
                    return countOverrun(counters, selectedEntities, selectedFacts, limits,
                            "determinism fact count limit exceeded");
                }
                ComparableEvent comparable = ComparableEvent.from(event, profile);
                eventsBytes = DeterminismCanonicalSize.add(eventsBytes,
                        DeterminismCanonicalSize.event(comparable.type(), comparable.subject(),
                                comparable.source(), comparable.metadata(),
                                comparable.attributes()));
            }
        }
        long decisionsBytes = 0;
        if (scope.includeDecisions()) {
            for (DecisionTrace decision : frame.decisions()) {
                selectedFacts++;
                if (counters.observedFacts + selectedFacts > limits.maximumFactsPerFrame()) {
                    return countOverrun(counters, selectedEntities, selectedFacts, limits,
                            "determinism fact count limit exceeded");
                }
                decisionsBytes = DeterminismCanonicalSize.add(decisionsBytes,
                        DeterminismCanonicalSize.decision(decision));
            }
        }
        long uiBytes = 0;
        if (profile.includeUiCorrelations()) {
            for (UiFrameCorrelation correlation : runtime.uiCorrelations().correlationsFor(
                    frame.executionEpochId(), frame.frameId())) {
                selectedFacts++;
                if (counters.observedFacts + selectedFacts > limits.maximumFactsPerFrame()) {
                    return countOverrun(counters, selectedEntities, selectedFacts, limits,
                            "determinism fact count limit exceeded");
                }
                uiBytes = DeterminismCanonicalSize.add(uiBytes,
                        DeterminismCanonicalSize.ui(correlation));
            }
        }
        counters.observeEntities(selectedEntities, limits.maximumEntitiesPerFrame());
        counters.observeFacts(selectedFacts, limits.maximumFactsPerFrame());
        long candidateBytes = DeterminismCanonicalSize.frame(frame.frameId(),
                entitiesBytes, eventsBytes, decisionsBytes, uiBytes);
        long observedBytes = DeterminismCanonicalSize.add(
                counters.encodedEvidenceBytes, candidateBytes);
        if (observedBytes > limits.maximumEncodedEvidenceBytes()) {
            counters.incompleteReason = Optional.of(
                    "encoded determinism evidence limit exceeded");
            return Optional.empty();
        }
        if (!frame.stats().diagnostics().isEmpty() || !frame.stats().truncations().isEmpty()
                || frame.events().stream().anyMatch(value -> !value.truncations().isEmpty())
                || frame.decisions().stream().anyMatch(value -> !value.truncations().isEmpty())) {
            counters.incompleteReason = Optional.of(
                    "capture diagnostics or truncation could hide a divergence");
        }
        List<EntitySnapshot> entities = frame.entities().stream()
                .filter(entity -> scope.entityIds().isEmpty()
                        || scope.entityIds().contains(entity.id()))
                .map(entity -> comparableEntity(entity, scope))
                .toList();
        List<ComparableEvent> events = scope.includeEvents()
                ? selectedEvents(frame, eventTypes).stream()
                        .map(event -> ComparableEvent.from(event, profile)).toList() : List.of();
        List<ComparableDecision> decisions = scope.includeDecisions()
                ? frame.decisions().stream().map(ComparableDecision::from).toList() : List.of();
        List<ComparableUi> ui = profile.includeUiCorrelations()
                ? runtime.uiCorrelations().correlationsFor(
                        frame.executionEpochId(), frame.frameId()).stream()
                        .map(ComparableUi::from).toList()
                : List.of();
        counters.encodedEvidenceBytes = observedBytes;
        return Optional.of(new FrameEvidence(
                frame.frameId(), entities, events, decisions, ui));
    }

    Optional<DeterminismDifference> difference(FrameEvidence left, FrameEvidence right) {
        Map<EntityId, EntitySnapshot> leftEntities = index(left.entities);
        Map<EntityId, EntitySnapshot> rightEntities = index(right.entities);
        LinkedHashSet<EntityId> entityIds = new LinkedHashSet<>(leftEntities.keySet());
        entityIds.addAll(rightEntities.keySet());
        List<EntityId> orderedIds = entityIds.stream().sorted().toList();
        for (EntityId id : orderedIds) {
            EntitySnapshot leftEntity = leftEntities.get(id);
            EntitySnapshot rightEntity = rightEntities.get(id);
            if (leftEntity == null || rightEntity == null
                    || !leftEntity.type().equals(rightEntity.type())
                    || !leftEntity.displayName().equals(rightEntity.displayName())) {
                return Optional.of(new DeterminismDifference(
                        DeterminismDifferenceKind.ENTITY_LIFECYCLE, Optional.of(id.value()),
                        entityIdentity(leftEntity), entityIdentity(rightEntity)));
            }
            Optional<DeterminismDifference> property = propertyDifference(leftEntity, rightEntity);
            if (property.isPresent()) {
                return property;
            }
        }
        Optional<DeterminismDifference> event = listDifference(
                DeterminismDifferenceKind.EVENT, "event", left.events, right.events);
        if (event.isPresent()) {
            return event;
        }
        Optional<DeterminismDifference> decision = listDifference(
                DeterminismDifferenceKind.DECISION, "decision", left.decisions, right.decisions);
        if (decision.isPresent()) {
            return decision;
        }
        return listDifference(DeterminismDifferenceKind.UI_CORRELATION,
                "uiCorrelation", left.ui, right.ui);
    }

    private Optional<DeterminismDifference> propertyDifference(
            EntitySnapshot left, EntitySnapshot right) {
        Map<String, RuntimeValue> leftProperties = properties(left);
        Map<String, RuntimeValue> rightProperties = properties(right);
        LinkedHashSet<String> names = new LinkedHashSet<>(leftProperties.keySet());
        names.addAll(rightProperties.keySet());
        for (String name : names.stream().sorted().toList()) {
            Optional<RuntimeValue> leftValue = Optional.ofNullable(leftProperties.get(name));
            Optional<RuntimeValue> rightValue = Optional.ofNullable(rightProperties.get(name));
            if (!leftValue.equals(rightValue)) {
                return Optional.of(new DeterminismDifference(
                        DeterminismDifferenceKind.PROPERTY,
                        Optional.of(left.id().value() + ':' + name), leftValue, rightValue));
            }
        }
        return Optional.empty();
    }

    private static <T> Optional<DeterminismDifference> listDifference(
            DeterminismDifferenceKind kind, String name, List<T> left, List<T> right) {
        int maximum = Math.max(left.size(), right.size());
        for (int index = 0; index < maximum; index++) {
            Optional<T> leftValue = index < left.size()
                    ? Optional.of(left.get(index)) : Optional.empty();
            Optional<T> rightValue = index < right.size()
                    ? Optional.of(right.get(index)) : Optional.empty();
            if (!leftValue.equals(rightValue)) {
                return Optional.of(new DeterminismDifference(kind,
                        Optional.of(name + ':' + index),
                        leftValue.map(value -> RuntimeValues.string(value.toString())),
                        rightValue.map(value -> RuntimeValues.string(value.toString()))));
            }
        }
        return Optional.empty();
    }

    private static Optional<RuntimeValue> property(
            FrameSnapshot frame, EntityId entityId, String property) {
        return entity(frame, entityId).flatMap(value -> value.property(property));
    }

    private static Optional<EntitySnapshot> entity(FrameSnapshot frame, EntityId entityId) {
        return frame.entities().stream().filter(value -> value.id().equals(entityId)).findFirst();
    }

    private static List<RuntimeEvent> selectedEvents(
            FrameSnapshot frame, List<EventType> eventTypes) {
        if (eventTypes.isEmpty()) {
            return frame.events();
        }
        return frame.events().stream().filter(event -> eventTypes.contains(event.type())).toList();
    }

    private static EntitySnapshot comparableEntity(
            EntitySnapshot entity, SnapshotComparisonScope scope) {
        List<RuntimeValue.Field> properties = entity.properties().stream()
                .filter(property -> scope.properties().isEmpty()
                        || scope.properties().contains(property.name()))
                .filter(property -> !scope.excludedProperties().contains(property.name()))
                .toList();
        return new EntitySnapshot(
                entity.id(), entity.type(), entity.displayName(), properties, List.of());
    }

    private static Map<EntityId, EntitySnapshot> index(List<EntitySnapshot> entities) {
        LinkedHashMap<EntityId, EntitySnapshot> index = new LinkedHashMap<>();
        entities.stream().sorted(Comparator.comparing(EntitySnapshot::id))
                .forEach(value -> index.put(value.id(), value));
        return index;
    }

    private static Map<String, RuntimeValue> properties(EntitySnapshot entity) {
        LinkedHashMap<String, RuntimeValue> result = new LinkedHashMap<>();
        entity.properties().forEach(value -> result.put(value.name(), value.value()));
        return result;
    }

    private static Optional<RuntimeValue> entityIdentity(EntitySnapshot entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(RuntimeValues.string(
                entity.type().value() + ':' + entity.displayName().orElse("")));
    }

    private static Optional<FrameEvidence> countOverrun(Counters counters,
            int selectedEntities, long selectedFacts, Limits limits, String reason) {
        counters.observeEntities(selectedEntities, limits.maximumEntitiesPerFrame());
        counters.observeFacts(selectedFacts, limits.maximumFactsPerFrame());
        counters.incompleteReason = Optional.of(reason);
        return Optional.empty();
    }

    private static long saturate(long current, long delta, long limit) {
        if (delta >= limit - current) {
            return limit;
        }
        return current + delta;
    }

    record Limits(int maximumEntitiesPerFrame, int maximumFactsPerFrame,
            int maximumEncodedEvidenceBytes) {
        Limits {
            if (maximumEntitiesPerFrame <= 0 || maximumFactsPerFrame <= 0
                    || maximumEncodedEvidenceBytes <= 0) {
                throw new IllegalArgumentException("invalid observable evidence limits");
            }
        }
    }

    static final class Counters {
        private long observedEntities;
        private long observedFacts;
        private long encodedEvidenceBytes;
        private Optional<String> incompleteReason = Optional.empty();

        long observedEntities() {
            return observedEntities;
        }

        long observedFacts() {
            return observedFacts;
        }

        long encodedEvidenceBytes() {
            return encodedEvidenceBytes;
        }

        Optional<String> incompleteReason() {
            return incompleteReason;
        }

        private void observeEntities(long delta, int limit) {
            observedEntities = saturate(observedEntities, delta, limit);
        }

        private void observeFacts(long delta, int limit) {
            observedFacts = saturate(observedFacts, delta, limit);
        }
    }

    record FrameEvidence(FrameId frameId, List<EntitySnapshot> entities,
            List<ComparableEvent> events, List<ComparableDecision> decisions,
            List<ComparableUi> ui) {}

    private record ComparableEvent(EventType type, Optional<EntityId> subject,
            Optional<EntityId> source, FactMetadata metadata,
            List<RuntimeValue.Field> attributes) {
        private static ComparableEvent from(
                RuntimeEvent value, DeterminismProfile profile) {
            return new ComparableEvent(value.type(), value.subject(), value.source(),
                    value.metadata(), value.attributes().stream()
                            .filter(attribute -> !profile.excludedVolatileFields()
                                    .contains(attribute.name()))
                            .toList());
        }
    }

    private record ComparableDecision(DecisionType type, EntityId actor,
            List<DecisionCandidate> candidates, Optional<EntityId> chosenCandidate,
            Optional<Reason> choiceReason, FactMetadata metadata,
            DecisionTrace.Completion completion) {
        private static ComparableDecision from(DecisionTrace value) {
            return new ComparableDecision(value.type(), value.actor(), value.candidates(),
                    value.chosenCandidate(), value.choiceReason(), value.metadata(),
                    value.completion());
        }
    }

    private record ComparableUi(String uiSessionId, Optional<String> uiFrameId,
            Optional<String> correlationToken) {
        private static ComparableUi from(UiFrameCorrelation value) {
            return new ComparableUi(
                    value.uiSessionId(), value.uiFrameId(), value.correlationToken());
        }
    }
}
