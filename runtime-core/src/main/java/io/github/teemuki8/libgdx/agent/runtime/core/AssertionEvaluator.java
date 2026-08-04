package io.github.teemuki8.libgdx.agent.runtime.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Read-only deterministic evaluator over completed immutable runtime evidence. */
public final class AssertionEvaluator {
    private final AgentRuntime runtime;

    AssertionEvaluator(AgentRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** Evaluates one closed assertion without advancing or waiting for simulation work. */
    public AssertionResult evaluate(RuntimeAssertion assertion, AssertionScope scope) {
        Objects.requireNonNull(assertion, "assertion");
        Objects.requireNonNull(scope, "scope");
        validateValues(assertion);
        EvidenceContext context = load(scope);
        return switch (assertion) {
            case RuntimeAssertion.EntityExists value -> entityExists(value, scope, context, true);
            case RuntimeAssertion.EntityDoesNotExist value ->
                    entityExists(value, scope, context, false);
            case RuntimeAssertion.PropertyEquals value -> propertyEquals(value, scope, context);
            case RuntimeAssertion.PropertyChangesFrom value ->
                    propertyChangesFrom(value, scope, context);
            case RuntimeAssertion.PropertyRemainsWithinRange value ->
                    propertyRemainsWithinRange(value, scope, context);
            case RuntimeAssertion.EventOccurs value -> eventOccurs(value, scope, context);
            case RuntimeAssertion.EventDoesNotOccur value ->
                    eventDoesNotOccur(value, scope, context);
            case RuntimeAssertion.EventOccursExactly value ->
                    eventOccursExactly(value, scope, context);
            case RuntimeAssertion.DecisionSelected value ->
                    decision(value.decisionType(), value.candidate(), true, scope, context);
            case RuntimeAssertion.DecisionRejected value ->
                    decision(value.decisionType(), value.candidate(), false, scope, context);
            case RuntimeAssertion.EntityCountStaysBelow value ->
                    entityCountStaysBelow(value, scope, context);
            case RuntimeAssertion.SnapshotsEquivalent value ->
                    snapshotsEquivalent(value, scope, context);
        };
    }

    private void validateValues(RuntimeAssertion assertion) {
        RuntimeLimits limits = runtime.configuration().limits();
        switch (assertion) {
            case RuntimeAssertion.PropertyEquals value ->
                    RuntimeValueValidator.validate(value.expected(), limits);
            case RuntimeAssertion.PropertyChangesFrom value ->
                    RuntimeValueValidator.validate(value.from(), limits);
            default -> {
                // Other assertion inputs contain bounded scalars and identifiers only.
            }
        }
    }

    private AssertionResult entityExists(RuntimeAssertion assertion, AssertionScope scope,
            EvidenceContext context, boolean expectedExists) {
        FrameSnapshot terminal = context.terminal(scope.range().to());
        boolean exists = terminal != null && terminal.entity(entityId(assertion)).isPresent();
        AssertionStatus status;
        if (terminal == null) {
            status = AssertionStatus.INCONCLUSIVE;
        } else if (exists == expectedExists) {
            status = expectedExists || !context.incomplete
                    ? AssertionStatus.PASS : AssertionStatus.INCONCLUSIVE;
        } else {
            status = expectedExists && context.incomplete
                    ? AssertionStatus.INCONCLUSIVE : AssertionStatus.FAIL;
        }
        RuntimeValue expected = RuntimeValues.bool(expectedExists);
        RuntimeValue observed = RuntimeValues.bool(exists);
        List<AssertionEvidence> evidence = status == AssertionStatus.FAIL ? List.of(
                new AssertionEvidence(terminal.frameId(), "entity",
                        Optional.of(entityId(assertion)), Optional.empty(),
                        Optional.of(observed))) : List.of();
        return result(status, expectedExists ? "entityExists" : "entityDoesNotExist", scope,
                Optional.of(expected), Optional.of(observed), evidence, context.incomplete,
                statusMessage(status));
    }

    private static EntityId entityId(RuntimeAssertion assertion) {
        return switch (assertion) {
            case RuntimeAssertion.EntityExists value -> value.entityId();
            case RuntimeAssertion.EntityDoesNotExist value -> value.entityId();
            default -> throw new IllegalArgumentException("assertion is not an entity assertion");
        };
    }

    private AssertionResult propertyEquals(RuntimeAssertion.PropertyEquals assertion,
            AssertionScope scope, EvidenceContext context) {
        FrameSnapshot terminal = context.terminal(scope.range().to());
        Optional<RuntimeValue> observed = terminal == null ? Optional.empty()
                : terminal.entity(assertion.entityId())
                        .flatMap(entity -> entity.property(assertion.property()));
        boolean equal = observed.map(assertion.expected()::equals).orElse(false);
        AssertionStatus status = equal ? AssertionStatus.PASS
                : context.incomplete || terminal == null ? AssertionStatus.INCONCLUSIVE
                        : AssertionStatus.FAIL;
        List<AssertionEvidence> evidence;
        if (observed.isPresent()) {
            evidence = List.of(new AssertionEvidence(terminal.frameId(), "property",
                    Optional.of(assertion.entityId()), Optional.of(assertion.property()), observed));
        } else if (terminal != null && status == AssertionStatus.FAIL) {
            evidence = List.of(new AssertionEvidence(terminal.frameId(), "property",
                    Optional.of(assertion.entityId()), Optional.of(assertion.property()),
                    Optional.empty()));
        } else {
            evidence = List.of();
        }
        return result(status, "propertyEquals", scope, Optional.of(assertion.expected()), observed,
                evidence, context.incomplete, statusMessage(status));
    }

    private AssertionResult propertyChangesFrom(RuntimeAssertion.PropertyChangesFrom assertion,
            AssertionScope scope, EvidenceContext context) {
        for (FrameSnapshot frame : context.frames) {
            for (PropertyChange change : frame.changes()) {
                if (change.entityId().equals(assertion.entityId())
                        && change.property().filter(assertion.property()::equals).isPresent()
                        && change.before().filter(assertion.from()::equals).isPresent()) {
                    AssertionEvidence evidence = new AssertionEvidence(frame.frameId(), "change",
                            Optional.of(assertion.entityId()), Optional.of(assertion.property()),
                            change.after());
                    return result(AssertionStatus.PASS, "propertyChangesFrom", scope,
                            Optional.of(assertion.from()), change.after(), List.of(evidence),
                            context.incomplete, statusMessage(AssertionStatus.PASS));
                }
            }
        }
        AssertionStatus status = context.incomplete
                ? AssertionStatus.INCONCLUSIVE : AssertionStatus.FAIL;
        return result(status, "propertyChangesFrom", scope, Optional.of(assertion.from()),
                Optional.empty(), List.of(), context.incomplete, statusMessage(status));
    }

    private AssertionResult propertyRemainsWithinRange(
            RuntimeAssertion.PropertyRemainsWithinRange assertion, AssertionScope scope,
            EvidenceContext context) {
        ArrayList<AssertionEvidence> evidence = new ArrayList<>();
        boolean missing = false;
        for (FrameSnapshot frame : context.frames) {
            Optional<RuntimeValue> observed = frame.entity(assertion.entityId())
                    .flatMap(entity -> entity.property(assertion.property()));
            if (observed.isEmpty()) {
                missing = true;
                continue;
            }
            Optional<BigDecimal> numeric = numeric(observed.orElseThrow());
            if (numeric.isEmpty()) {
                return result(AssertionStatus.FAIL, "propertyRemainsWithinRange", scope,
                        Optional.of(rangeValue(assertion.minimum(), assertion.maximum())), observed,
                        limited(evidence, scope), context.incomplete, "observed property is not numeric");
            }
            BigDecimal value = numeric.orElseThrow();
            if (value.compareTo(assertion.minimum()) < 0
                    || value.compareTo(assertion.maximum()) > 0) {
                evidence.add(new AssertionEvidence(frame.frameId(), "property",
                        Optional.of(assertion.entityId()), Optional.of(assertion.property()), observed));
                return result(AssertionStatus.FAIL, "propertyRemainsWithinRange", scope,
                        Optional.of(rangeValue(assertion.minimum(), assertion.maximum())), observed,
                        limited(evidence, scope), context.incomplete, statusMessage(AssertionStatus.FAIL));
            }
        }
        boolean incomplete = context.incomplete || missing;
        AssertionStatus status = incomplete ? AssertionStatus.INCONCLUSIVE : AssertionStatus.PASS;
        return result(status, "propertyRemainsWithinRange", scope,
                Optional.of(rangeValue(assertion.minimum(), assertion.maximum())), Optional.empty(),
                limited(evidence, scope), incomplete, statusMessage(status));
    }

    private AssertionResult eventOccurs(RuntimeAssertion.EventOccurs assertion, AssertionScope scope,
            EvidenceContext context) {
        List<RuntimeEvent> matches = events(assertion.eventType(), context);
        if (!matches.isEmpty()) {
            RuntimeEvent event = matches.getFirst();
            return result(AssertionStatus.PASS, "eventOccurs", scope, Optional.empty(),
                    Optional.of(RuntimeValues.string(event.type().value())),
                    List.of(eventEvidence(event)), context.incomplete,
                    statusMessage(AssertionStatus.PASS));
        }
        AssertionStatus status = context.incomplete
                ? AssertionStatus.INCONCLUSIVE : AssertionStatus.FAIL;
        return result(status, "eventOccurs", scope, Optional.empty(), Optional.empty(), List.of(),
                context.incomplete, statusMessage(status));
    }

    private AssertionResult eventDoesNotOccur(RuntimeAssertion.EventDoesNotOccur assertion,
            AssertionScope scope, EvidenceContext context) {
        List<RuntimeEvent> matches = events(assertion.eventType(), context);
        if (!matches.isEmpty()) {
            RuntimeEvent event = matches.getFirst();
            return result(AssertionStatus.FAIL, "eventDoesNotOccur", scope, Optional.empty(),
                    Optional.of(RuntimeValues.string(event.type().value())),
                    List.of(eventEvidence(event)), context.incomplete,
                    statusMessage(AssertionStatus.FAIL));
        }
        AssertionStatus status = context.incomplete
                ? AssertionStatus.INCONCLUSIVE : AssertionStatus.PASS;
        return result(status, "eventDoesNotOccur", scope, Optional.empty(), Optional.empty(),
                List.of(), context.incomplete, statusMessage(status));
    }

    private AssertionResult eventOccursExactly(RuntimeAssertion.EventOccursExactly assertion,
            AssertionScope scope, EvidenceContext context) {
        List<RuntimeEvent> matches = events(assertion.eventType(), context);
        AssertionStatus status;
        if (matches.size() > assertion.count()) {
            status = AssertionStatus.FAIL;
        } else if (context.incomplete) {
            status = AssertionStatus.INCONCLUSIVE;
        } else {
            status = matches.size() == assertion.count()
                    ? AssertionStatus.PASS : AssertionStatus.FAIL;
        }
        return result(status, "eventOccursExactly", scope,
                Optional.of(RuntimeValues.integer(assertion.count())),
                Optional.of(RuntimeValues.integer(matches.size())),
                limited(matches.stream().map(AssertionEvaluator::eventEvidence).toList(), scope),
                context.incomplete, statusMessage(status));
    }

    private AssertionResult decision(DecisionType type, EntityId candidate, boolean selected,
            AssertionScope scope, EvidenceContext context) {
        for (FrameSnapshot frame : context.frames) {
            for (DecisionTrace trace : frame.decisions()) {
                boolean matches = trace.type().equals(type) && (selected
                        ? trace.chosenCandidate().filter(candidate::equals).isPresent()
                        : trace.candidates().stream().anyMatch(value ->
                                value.entityId().equals(candidate)
                                        && value.status() == DecisionCandidate.Status.REJECTED));
                if (matches && trace.completion() == DecisionTrace.Completion.COMPLETED) {
                    AssertionEvidence evidence = new AssertionEvidence(frame.frameId(), "decision",
                            Optional.of(candidate), Optional.empty(),
                            Optional.of(RuntimeValues.string(type.value())));
                    return result(AssertionStatus.PASS,
                            selected ? "decisionSelected" : "decisionRejected", scope,
                            Optional.of(RuntimeValues.string(candidate.value())),
                            Optional.of(RuntimeValues.string(candidate.value())), List.of(evidence),
                            context.incomplete, statusMessage(AssertionStatus.PASS));
                }
            }
        }
        AssertionStatus status = context.incomplete
                ? AssertionStatus.INCONCLUSIVE : AssertionStatus.FAIL;
        return result(status, selected ? "decisionSelected" : "decisionRejected", scope,
                Optional.of(RuntimeValues.string(candidate.value())), Optional.empty(), List.of(),
                context.incomplete, statusMessage(status));
    }

    private AssertionResult entityCountStaysBelow(
            RuntimeAssertion.EntityCountStaysBelow assertion, AssertionScope scope,
            EvidenceContext context) {
        long maximum = 0;
        FrameId maximumFrame = scope.range().from();
        for (FrameSnapshot frame : context.frames) {
            long count = frame.entities().stream()
                    .filter(entity -> assertion.entityType().map(entity.type()::equals).orElse(true))
                    .count();
            if (count > maximum) {
                maximum = count;
                maximumFrame = frame.frameId();
            }
            if (count >= assertion.limit()) {
                AssertionEvidence evidence = new AssertionEvidence(frame.frameId(), "entityCount",
                        Optional.empty(), Optional.empty(),
                        Optional.of(RuntimeValues.integer(count)));
                return result(AssertionStatus.FAIL, "entityCountStaysBelow", scope,
                        Optional.of(RuntimeValues.integer(assertion.limit())),
                        Optional.of(RuntimeValues.integer(count)), List.of(evidence),
                        context.incomplete, statusMessage(AssertionStatus.FAIL));
            }
        }
        AssertionStatus status = context.incomplete
                ? AssertionStatus.INCONCLUSIVE : AssertionStatus.PASS;
        AssertionEvidence evidence = new AssertionEvidence(maximumFrame, "entityCount",
                Optional.empty(), Optional.empty(), Optional.of(RuntimeValues.integer(maximum)));
        return result(status, "entityCountStaysBelow", scope,
                Optional.of(RuntimeValues.integer(assertion.limit())),
                Optional.of(RuntimeValues.integer(maximum)), List.of(evidence), context.incomplete,
                statusMessage(status));
    }

    private AssertionResult snapshotsEquivalent(RuntimeAssertion.SnapshotsEquivalent assertion,
            AssertionScope scope, EvidenceContext context) {
        FrameSnapshot left = context.frame(assertion.leftFrameId());
        FrameSnapshot right = context.frame(assertion.rightFrameId());
        if (left == null || right == null || context.incomplete) {
            return result(AssertionStatus.INCONCLUSIVE, "snapshotsEquivalent", scope,
                    Optional.empty(), Optional.empty(), List.of(), true,
                    statusMessage(AssertionStatus.INCONCLUSIVE));
        }
        boolean equal = comparable(left, assertion.comparisonScope())
                .equals(comparable(right, assertion.comparisonScope()));
        AssertionStatus status = equal ? AssertionStatus.PASS : AssertionStatus.FAIL;
        List<AssertionEvidence> evidence = equal ? List.of() : List.of(
                new AssertionEvidence(left.frameId(), "snapshot", Optional.empty(),
                        Optional.empty(), Optional.empty()),
                new AssertionEvidence(right.frameId(), "snapshot", Optional.empty(),
                        Optional.empty(), Optional.empty()));
        return result(status, "snapshotsEquivalent", scope, Optional.empty(), Optional.empty(),
                evidence, false, statusMessage(status));
    }

    private EvidenceContext load(AssertionScope scope) {
        ArrayList<FrameSnapshot> frames = new ArrayList<>();
        boolean incomplete = false;
        long value = scope.range().from().value();
        while (true) {
            Optional<FrameSnapshot> frame = runtime.frame(new FrameId(value));
            if (frame.isEmpty() || !frame.orElseThrow().executionEpochId()
                    .equals(scope.executionEpochId())) {
                incomplete = true;
            } else {
                FrameSnapshot snapshot = frame.orElseThrow();
                frames.add(snapshot);
                incomplete |= incomplete(snapshot);
            }
            if (value == scope.range().to().value()) {
                break;
            }
            value++;
        }
        return new EvidenceContext(List.copyOf(frames), incomplete);
    }

    private static boolean incomplete(FrameSnapshot frame) {
        return !frame.stats().diagnostics().isEmpty() || !frame.stats().truncations().isEmpty()
                || frame.entities().stream().anyMatch(EntitySnapshot::truncated)
                || frame.events().stream().anyMatch(event -> !event.truncations().isEmpty())
                || frame.decisions().stream().anyMatch(decision ->
                        decision.completion() != DecisionTrace.Completion.COMPLETED
                                || !decision.truncations().isEmpty());
    }

    private static List<RuntimeEvent> events(EventType type, EvidenceContext context) {
        return context.frames.stream().flatMap(frame -> frame.events().stream())
                .filter(event -> event.type().equals(type)).toList();
    }

    private static AssertionEvidence eventEvidence(RuntimeEvent event) {
        return new AssertionEvidence(event.frameId(), "event", event.subject(), Optional.empty(),
                Optional.of(RuntimeValues.string(event.type().value())));
    }

    private static Optional<BigDecimal> numeric(RuntimeValue value) {
        return switch (value) {
            case RuntimeValue.IntegerValue integer -> Optional.of(BigDecimal.valueOf(integer.value()));
            case RuntimeValue.DecimalValue decimal -> Optional.of(decimal.value());
            default -> Optional.empty();
        };
    }

    private static RuntimeValue rangeValue(BigDecimal minimum, BigDecimal maximum) {
        return RuntimeValues.object(
                RuntimeValues.field("maximum", new RuntimeValue.DecimalValue(maximum)),
                RuntimeValues.field("minimum", new RuntimeValue.DecimalValue(minimum)));
    }

    private static ComparableSnapshot comparable(
            FrameSnapshot frame, SnapshotComparisonScope scope) {
        List<ComparableEntity> entities = frame.entities().stream()
                .filter(entity -> scope.entityIds().isEmpty()
                        || scope.entityIds().contains(entity.id()))
                .map(entity -> new ComparableEntity(entity.id(), entity.type(),
                        entity.properties().stream()
                                .filter(property -> scope.properties().isEmpty()
                                        || scope.properties().contains(property.name()))
                                .filter(property ->
                                        !scope.excludedProperties().contains(property.name()))
                                .toList()))
                .toList();
        List<ComparableEvent> events = scope.includeEvents()
                ? frame.events().stream().map(event -> new ComparableEvent(event.type(),
                        event.subject(), event.source(), event.metadata(), event.attributes()))
                        .toList() : List.of();
        List<ComparableDecision> decisions = scope.includeDecisions()
                ? frame.decisions().stream().map(decision -> new ComparableDecision(decision.type(),
                        decision.actor(), decision.candidates(), decision.chosenCandidate(),
                        decision.choiceReason(), decision.metadata(), decision.completion()))
                        .toList() : List.of();
        return new ComparableSnapshot(entities, events, decisions);
    }

    private static <T> List<T> limited(List<T> values, AssertionScope scope) {
        return values.stream().limit(scope.evidenceLimit()).toList();
    }

    private static AssertionResult result(AssertionStatus status, String type, AssertionScope scope,
            Optional<RuntimeValue> expected, Optional<RuntimeValue> observed,
            List<AssertionEvidence> evidence, boolean incomplete, String message) {
        return new AssertionResult(status, type, scope, expected, observed,
                limited(evidence, scope), incomplete, message);
    }

    private static String statusMessage(AssertionStatus status) {
        return switch (status) {
            case PASS -> "assertion passed";
            case FAIL -> "assertion failed";
            case INCONCLUSIVE -> "retained evidence is incomplete";
        };
    }

    private record EvidenceContext(List<FrameSnapshot> frames, boolean incomplete) {
        private FrameSnapshot frame(FrameId id) {
            return frames.stream().filter(value -> value.frameId().equals(id)).findFirst().orElse(null);
        }

        private FrameSnapshot terminal(FrameId id) {
            return frame(id);
        }
    }

    private record ComparableSnapshot(List<ComparableEntity> entities,
            List<ComparableEvent> events, List<ComparableDecision> decisions) {}

    private record ComparableEntity(EntityId id, EntityType type,
            List<RuntimeValue.Field> properties) {}

    private record ComparableEvent(EventType type, Optional<EntityId> subject,
            Optional<EntityId> source, FactMetadata metadata,
            List<RuntimeValue.Field> attributes) {}

    private record ComparableDecision(DecisionType type, EntityId actor,
            List<DecisionCandidate> candidates, Optional<EntityId> chosenCandidate,
            Optional<Reason> choiceReason, FactMetadata metadata,
            DecisionTrace.Completion completion) {}
}
