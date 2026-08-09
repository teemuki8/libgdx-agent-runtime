package io.github.teemuki8.libgdx.agent.runtime.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Package-private implementation for simulation-scoped declarative assertions. */
final class SimulationAssertionEvaluation {
    private final AgentRuntime runtime;

    SimulationAssertionEvaluation(AgentRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    SimulationAssertionResult evaluate(SimulationAssertionSpec spec,
            SimulationAssertionScope scope) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(scope, "scope");
        validateValues(spec.assertion());
        Context context = load(spec, scope);
        Outcome outcome = evaluate(spec.assertion(), context);
        ArrayList<SimulationAssertionEvidence> retained = new ArrayList<>(outcome.evidence);
        if (outcome.incomplete && retained.size() < scope.evidenceLimit()) {
            context.slots.stream().filter(slot -> !slot.complete).findFirst()
                    .map(SimulationAssertionEvaluation::incompleteEvidence)
                    .ifPresent(retained::add);
        }
        List<SimulationAssertionEvidence> evidence = retained.stream()
                .sorted(Comparator.comparingLong(SimulationAssertionEvidence::epochTick)
                        .thenComparing(value -> value.entityId().map(EntityId::value).orElse(""))
                        .thenComparing(value -> value.property().orElse(""))
                        .thenComparing(SimulationAssertionEvidence::kind))
                .limit(scope.evidenceLimit()).toList();
        return new SimulationAssertionResult(outcome.status, outcome.type, scope,
                outcome.expected, outcome.observed, evidence, outcome.incomplete,
                message(outcome.status));
    }

    private Outcome evaluate(SimulationAssertion assertion, Context context) {
        return switch (assertion) {
            case SimulationAssertion.EntityExists value -> entityExists(value, context);
            case SimulationAssertion.PropertyEquals value -> propertyEquals(value, context);
            case SimulationAssertion.ScalarApproximatelyEquals value ->
                    scalarApproximatelyEquals(value, context);
            case SimulationAssertion.VectorApproximatelyEquals value ->
                    vectorApproximatelyEquals(value, context);
            case SimulationAssertion.VectorInArea value -> vectorInArea(value, context);
            case SimulationAssertion.VectorMagnitudeAtMost value ->
                    vectorMagnitudeAtMost(value, context);
            case SimulationAssertion.VectorDistanceApproximatelyEquals value ->
                    vectorDistanceApproximatelyEquals(value, context);
            case SimulationAssertion.WrappedAngleApproximatelyEquals value ->
                    wrappedAngleApproximatelyEquals(value, context);
            case SimulationAssertion.EventCount value -> eventCount(value, context);
            case SimulationAssertion.ObjectListContains value ->
                    objectListContains(value, context);
            case SimulationAssertion.AllOf value -> allOf(value, context);
        };
    }

    private Outcome entityExists(SimulationAssertion.EntityExists assertion, Context context) {
        Slot slot = context.terminal();
        boolean exists = slot.frame != null
                && slot.frame.entity(assertion.entityId()).isPresent();
        if (!slot.complete) {
            return inconclusive("entityExists", RuntimeValues.bool(true),
                    RuntimeValues.bool(exists));
        }
        if (exists) {
            return pass("entityExists", RuntimeValues.bool(true), RuntimeValues.bool(true));
        }
        return fail("entityExists", RuntimeValues.bool(true), RuntimeValues.bool(false),
                evidence(slot, "entity", assertion.entityId(), null,
                        Optional.of(RuntimeValues.bool(false))));
    }

    private Outcome propertyEquals(
            SimulationAssertion.PropertyEquals assertion, Context context) {
        Slot slot = context.terminal();
        Optional<RuntimeValue> observed = property(slot, assertion.entityId(), assertion.property());
        if (!slot.complete) {
            return inconclusive("propertyEquals", assertion.expected(), observed.orElse(null));
        }
        if (observed.filter(assertion.expected()::equals).isPresent()) {
            return pass("propertyEquals", assertion.expected(), observed.orElseThrow());
        }
        return fail("propertyEquals", assertion.expected(), observed.orElse(null),
                evidence(slot, "property", assertion.entityId(), assertion.property(), observed));
    }

    private Outcome scalarApproximatelyEquals(
            SimulationAssertion.ScalarApproximatelyEquals assertion, Context context) {
        Slot slot = context.terminal();
        Optional<RuntimeValue> observed = property(slot, assertion.entityId(), assertion.property());
        if (!slot.complete) {
            return inconclusive("scalarApproximatelyEquals",
                    approximateScalar(assertion.expected(), assertion.absoluteTolerance()),
                    observed.orElse(null));
        }
        Optional<BigDecimal> numeric = observed.flatMap(SimulationAssertionEvaluation::numeric);
        boolean matches = numeric.map(value -> value.subtract(assertion.expected()).abs()
                .compareTo(assertion.absoluteTolerance()) <= 0).orElse(false);
        RuntimeValue expected = approximateScalar(
                assertion.expected(), assertion.absoluteTolerance());
        if (matches) {
            return pass("scalarApproximatelyEquals", expected, observed.orElseThrow());
        }
        return fail("scalarApproximatelyEquals", expected, observed.orElse(null),
                evidence(slot, "property", assertion.entityId(), assertion.property(), observed));
    }

    private Outcome vectorApproximatelyEquals(
            SimulationAssertion.VectorApproximatelyEquals assertion, Context context) {
        Slot slot = context.terminal();
        Optional<RuntimeValue> observed = property(slot, assertion.entityId(), assertion.property());
        if (!slot.complete) {
            return inconclusive("vectorApproximatelyEquals", assertion.expected(),
                    observed.orElse(null));
        }
        Optional<RuntimeValue.Vector2Value> vector = observed.flatMap(
                SimulationAssertionEvaluation::vector);
        boolean matches = vector.map(value -> vectorApproximately(value, assertion.expected(),
                assertion.absoluteTolerance(), assertion.toleranceMode())).orElse(false);
        if (matches) {
            return pass("vectorApproximatelyEquals", assertion.expected(), observed.orElseThrow());
        }
        return fail("vectorApproximatelyEquals", assertion.expected(), observed.orElse(null),
                evidence(slot, "property", assertion.entityId(), assertion.property(), observed));
    }

    private Outcome vectorInArea(SimulationAssertion.VectorInArea assertion, Context context) {
        RuntimeValue expected = area(assertion.area(), assertion.relation());
        if (assertion.extent() == SimulationAssertion.Extent.FINAL) {
            Slot slot = context.terminal();
            Optional<RuntimeValue> observed = property(
                    slot, assertion.entityId(), assertion.property());
            if (!slot.complete) {
                return inconclusive("vectorInArea", expected, observed.orElse(null));
            }
            boolean matches = observed.flatMap(SimulationAssertionEvaluation::vector)
                    .map(value -> inArea(value, assertion.area())
                            == (assertion.relation() == SimulationAssertion.AreaRelation.INSIDE))
                    .orElse(false);
            if (matches) {
                return pass("vectorInArea", expected, observed.orElseThrow());
            }
            return fail("vectorInArea", expected, observed.orElse(null), evidence(slot,
                    "property", assertion.entityId(), assertion.property(), observed));
        }
        return everyTick("vectorInArea", assertion.entityId(), assertion.property(), expected,
                context, value -> vector(value).map(vector -> inArea(vector, assertion.area())
                        == (assertion.relation()
                                == SimulationAssertion.AreaRelation.INSIDE)).orElse(false));
    }

    private Outcome vectorMagnitudeAtMost(
            SimulationAssertion.VectorMagnitudeAtMost assertion, Context context) {
        RuntimeValue expected = RuntimeValues.object(
                RuntimeValues.field("maximum", decimal(assertion.maximum())));
        if (assertion.extent() == SimulationAssertion.Extent.FINAL) {
            Slot slot = context.terminal();
            Optional<RuntimeValue> observed = property(
                    slot, assertion.entityId(), assertion.property());
            if (!slot.complete) {
                return inconclusive("vectorMagnitudeAtMost", expected, observed.orElse(null));
            }
            boolean matches = observed.flatMap(SimulationAssertionEvaluation::vector)
                    .map(value -> squaredMagnitude(value)
                            .compareTo(assertion.maximum().multiply(assertion.maximum())) <= 0)
                    .orElse(false);
            if (matches) {
                return pass("vectorMagnitudeAtMost", expected, observed.orElseThrow());
            }
            return fail("vectorMagnitudeAtMost", expected, observed.orElse(null), evidence(slot,
                    "property", assertion.entityId(), assertion.property(), observed));
        }
        return everyTick("vectorMagnitudeAtMost", assertion.entityId(), assertion.property(),
                expected, context, value -> vector(value).map(vector -> squaredMagnitude(vector)
                        .compareTo(assertion.maximum().multiply(assertion.maximum())) <= 0)
                        .orElse(false));
    }

    private Outcome vectorDistanceApproximatelyEquals(
            SimulationAssertion.VectorDistanceApproximatelyEquals assertion, Context context) {
        Slot slot = context.terminal();
        Optional<RuntimeValue> left = property(
                slot, assertion.leftEntityId(), assertion.leftProperty());
        Optional<RuntimeValue> right = property(
                slot, assertion.rightEntityId(), assertion.rightProperty());
        RuntimeValue observed = RuntimeValues.object(
                RuntimeValues.field("left", left.orElseGet(RuntimeValues::nullValue)),
                RuntimeValues.field("right", right.orElseGet(RuntimeValues::nullValue)));
        RuntimeValue expected = approximateScalar(
                assertion.expectedDistance(), assertion.absoluteTolerance());
        if (!slot.complete) {
            return inconclusive("vectorDistanceApproximatelyEquals", expected, observed);
        }
        Optional<RuntimeValue.Vector2Value> leftVector = left.flatMap(
                SimulationAssertionEvaluation::vector);
        Optional<RuntimeValue.Vector2Value> rightVector = right.flatMap(
                SimulationAssertionEvaluation::vector);
        boolean matches = leftVector.isPresent() && rightVector.isPresent()
                && distanceWithin(leftVector.orElseThrow(), rightVector.orElseThrow(),
                        assertion.expectedDistance(), assertion.absoluteTolerance());
        if (matches) {
            return pass("vectorDistanceApproximatelyEquals", expected, observed);
        }
        return fail("vectorDistanceApproximatelyEquals", expected, observed,
                evidence(slot, "distance", assertion.leftEntityId(), assertion.leftProperty(),
                        Optional.of(observed)));
    }

    private Outcome wrappedAngleApproximatelyEquals(
            SimulationAssertion.WrappedAngleApproximatelyEquals assertion, Context context) {
        Slot slot = context.terminal();
        Optional<RuntimeValue> observed = property(slot, assertion.entityId(), assertion.property());
        RuntimeValue expected = RuntimeValues.object(
                RuntimeValues.field("expected", decimal(assertion.expected())),
                RuntimeValues.field("period", decimal(assertion.period())),
                RuntimeValues.field("tolerance", decimal(assertion.absoluteTolerance())));
        if (!slot.complete) {
            return inconclusive("wrappedAngleApproximatelyEquals", expected,
                    observed.orElse(null));
        }
        boolean matches = observed.flatMap(SimulationAssertionEvaluation::numeric)
                .map(value -> wrappedDistance(value, assertion.expected(), assertion.period())
                        .compareTo(assertion.absoluteTolerance()) <= 0).orElse(false);
        if (matches) {
            return pass("wrappedAngleApproximatelyEquals", expected, observed.orElseThrow());
        }
        return fail("wrappedAngleApproximatelyEquals", expected, observed.orElse(null),
                evidence(slot, "property", assertion.entityId(), assertion.property(), observed));
    }

    private Outcome eventCount(SimulationAssertion.EventCount assertion, Context context) {
        ArrayList<SimulationAssertionEvidence> matches = new ArrayList<>();
        long matchCount = 0;
        for (Slot slot : context.slots) {
            if (slot.frame == null) {
                continue;
            }
            for (RuntimeEvent event : slot.frame.events()) {
                if (matches(assertion.selector(), event)) {
                    if (!slot.complete) {
                        continue;
                    }
                    matchCount = matchCount == Long.MAX_VALUE ? Long.MAX_VALUE : matchCount + 1;
                    if (matches.size() < context.evidenceLimit) {
                        matches.add(evidence(slot, "event", event.subject().orElse(null), null,
                                Optional.of(RuntimeValues.string(event.type().value()))));
                    }
                    if (assertion.expectation()
                            == SimulationAssertion.EventExpectation.AT_LEAST_ONE) {
                        return new Outcome(AssertionStatus.PASS, "eventCount", Optional.empty(),
                                Optional.of(RuntimeValues.integer(1)), List.of(matches.getFirst()),
                                context.incomplete());
                    }
                    if (assertion.expectation() == SimulationAssertion.EventExpectation.NONE) {
                        return new Outcome(AssertionStatus.FAIL, "eventCount", Optional.of(
                                RuntimeValues.integer(0)), Optional.of(RuntimeValues.integer(1)),
                                List.of(matches.getFirst()), context.incomplete());
                    }
                    if (matchCount > assertion.exactCount()) {
                        return new Outcome(AssertionStatus.FAIL, "eventCount",
                                Optional.of(RuntimeValues.integer(assertion.exactCount())),
                                Optional.of(RuntimeValues.integer(matchCount)), matches,
                                context.incomplete());
                    }
                }
            }
        }
        RuntimeValue expected = switch (assertion.expectation()) {
            case AT_LEAST_ONE -> RuntimeValues.integer(1);
            case NONE -> RuntimeValues.integer(0);
            case EXACT -> RuntimeValues.integer(assertion.exactCount());
        };
        RuntimeValue observed = RuntimeValues.integer(matchCount);
        if (context.incomplete()) {
            return inconclusive("eventCount", expected, observed, matches);
        }
        boolean pass = assertion.expectation() == SimulationAssertion.EventExpectation.NONE
                || assertion.expectation() == SimulationAssertion.EventExpectation.EXACT
                        && matchCount == assertion.exactCount();
        return new Outcome(pass ? AssertionStatus.PASS : AssertionStatus.FAIL, "eventCount",
                Optional.of(expected), Optional.of(observed), matches, false);
    }

    private Outcome objectListContains(
            SimulationAssertion.ObjectListContains assertion, Context context) {
        RuntimeValue expected = assertion.selector();
        if (assertion.extent() == SimulationAssertion.Extent.FINAL) {
            Slot slot = context.terminal();
            Optional<RuntimeValue> observed = property(
                    slot, assertion.entityId(), assertion.property());
            if (!slot.complete) {
                return inconclusive("objectListContains", expected, observed.orElse(null));
            }
            boolean matches = observed.map(value -> listContains(value, assertion.selector()))
                    .orElse(false);
            if (matches) {
                return pass("objectListContains", expected, observed.orElseThrow());
            }
            return fail("objectListContains", expected, observed.orElse(null), evidence(slot,
                    "property", assertion.entityId(), assertion.property(), observed));
        }
        return everyTick("objectListContains", assertion.entityId(), assertion.property(),
                expected, context, value -> listContains(value, assertion.selector()));
    }

    private Outcome allOf(SimulationAssertion.AllOf assertion, Context context) {
        ArrayList<SimulationAssertionEvidence> evidence = new ArrayList<>();
        boolean incomplete = false;
        for (SimulationAssertion term : assertion.terms()) {
            Outcome outcome = evaluate(term, context);
            evidence.addAll(outcome.evidence);
            incomplete |= outcome.incomplete;
            if (outcome.status == AssertionStatus.FAIL) {
                return new Outcome(AssertionStatus.FAIL, "allOf", outcome.expected,
                        outcome.observed, evidence, incomplete);
            }
            if (outcome.status == AssertionStatus.INCONCLUSIVE) {
                incomplete = true;
            }
        }
        return new Outcome(incomplete ? AssertionStatus.INCONCLUSIVE : AssertionStatus.PASS,
                "allOf", Optional.empty(), Optional.empty(), evidence, incomplete);
    }

    private Outcome everyTick(String type, EntityId entityId, String property,
            RuntimeValue expected, Context context, java.util.function.Predicate<RuntimeValue> test) {
        boolean incomplete = false;
        for (Slot slot : context.slots) {
            Optional<RuntimeValue> observed = property(slot, entityId, property);
            if (!slot.complete) {
                incomplete = true;
                continue;
            }
            if (observed.isEmpty() || !test.test(observed.orElseThrow())) {
                return new Outcome(AssertionStatus.FAIL, type, Optional.of(expected), observed,
                        List.of(evidence(slot, "property", entityId, property, observed)),
                        incomplete);
            }
        }
        return new Outcome(incomplete ? AssertionStatus.INCONCLUSIVE : AssertionStatus.PASS,
                type, Optional.of(expected), Optional.empty(), List.of(), incomplete);
    }

    private Context load(SimulationAssertionSpec spec, SimulationAssertionScope scope) {
        Map<Long, SimulationTick> ticks = new LinkedHashMap<>();
        int pageSize = runtime.simulation().limits().queryPageSize();
        long cursor = scope.fromEpochTick();
        while (cursor <= scope.toEpochTick()) {
            int length = (int) Math.min(pageSize, scope.toEpochTick() - cursor + 1);
            long end = cursor + length - 1;
            SimulationTickPage page = runtime.simulation().ticks(new SimulationTickQuery(
                    scope.executionEpochId(), cursor, end, length));
            page.ticks().forEach(tick -> ticks.put(tick.epochTick(), tick));
            if (end == scope.toEpochTick()) {
                break;
            }
            cursor = end + 1;
        }
        ArrayList<Slot> slots = new ArrayList<>();
        long epochTick = scope.fromEpochTick();
        while (true) {
            SimulationTick tick = ticks.get(epochTick);
            FrameSnapshot frame = tick == null ? null : tick.resultingFrameId()
                    .flatMap(runtime::frame).orElse(null);
            boolean complete = tick != null
                    && tick.executionEpochId().equals(scope.executionEpochId())
                    && tick.outcome() == SimulationTickOutcome.COMPLETED
                    && tick.mutationOutcome() == SimulationMutationOutcome.KNOWN_COMPLETED
                    && frame != null
                    && frame.executionEpochId().equals(scope.executionEpochId())
                    && !incomplete(frame)
                    && requirementsComplete(frame, spec.evidenceRequirements());
            slots.add(new Slot(scope.executionEpochId(), epochTick, tick, frame, complete));
            if (epochTick == scope.toEpochTick()) {
                break;
            }
            epochTick++;
        }
        return new Context(List.copyOf(slots), scope.evidenceLimit());
    }

    private static boolean requirementsComplete(FrameSnapshot frame,
            List<SimulationEvidenceRequirement> requirements) {
        for (SimulationEvidenceRequirement requirement : requirements) {
            Optional<RuntimeValue> value = frame.entity(requirement.entityId())
                    .flatMap(entity -> entity.property(requirement.property()));
            if (value.filter(RuntimeValue.BooleanValue.class::isInstance)
                    .map(RuntimeValue.BooleanValue.class::cast)
                    .map(RuntimeValue.BooleanValue::value).filter(Boolean::booleanValue).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean incomplete(FrameSnapshot frame) {
        return !frame.stats().diagnostics().isEmpty() || !frame.stats().truncations().isEmpty()
                || frame.entities().stream().anyMatch(EntitySnapshot::truncated)
                || frame.events().stream().anyMatch(event -> !event.truncations().isEmpty())
                || frame.decisions().stream().anyMatch(decision ->
                        decision.completion() != DecisionTrace.Completion.COMPLETED
                                || !decision.truncations().isEmpty());
    }

    private void validateValues(SimulationAssertion assertion) {
        RuntimeLimits limits = runtime.configuration().limits();
        switch (assertion) {
            case SimulationAssertion.PropertyEquals value ->
                    RuntimeValueValidator.validate(value.expected(), limits);
            case SimulationAssertion.VectorApproximatelyEquals value ->
                    RuntimeValueValidator.validate(value.expected(), limits);
            case SimulationAssertion.EventCount value ->
                    RuntimeValueValidator.validate(value.selector().attributes(), limits);
            case SimulationAssertion.ObjectListContains value ->
                    RuntimeValueValidator.validate(value.selector(), limits);
            case SimulationAssertion.AllOf value -> value.terms().forEach(this::validateValues);
            default -> {
                // Remaining assertions contain validated identifiers and canonical decimals only.
            }
        }
    }

    private static Optional<RuntimeValue> property(Slot slot, EntityId entityId, String property) {
        return slot.frame == null ? Optional.empty()
                : slot.frame.entity(entityId).flatMap(entity -> entity.property(property));
    }

    private static boolean vectorApproximately(RuntimeValue.Vector2Value observed,
            RuntimeValue.Vector2Value expected, BigDecimal tolerance,
            SimulationAssertion.VectorToleranceMode mode) {
        BigDecimal differenceX = observed.x().value().subtract(expected.x().value()).abs();
        BigDecimal differenceY = observed.y().value().subtract(expected.y().value()).abs();
        return mode == SimulationAssertion.VectorToleranceMode.COMPONENT
                ? differenceX.compareTo(tolerance) <= 0 && differenceY.compareTo(tolerance) <= 0
                : differenceX.multiply(differenceX).add(differenceY.multiply(differenceY))
                        .compareTo(tolerance.multiply(tolerance)) <= 0;
    }

    private static boolean inArea(RuntimeValue.Vector2Value value, SimulationAssertion.Area area) {
        return value.x().value().compareTo(area.minimumX()) >= 0
                && value.x().value().compareTo(area.maximumX()) <= 0
                && value.y().value().compareTo(area.minimumY()) >= 0
                && value.y().value().compareTo(area.maximumY()) <= 0;
    }

    private static BigDecimal squaredMagnitude(RuntimeValue.Vector2Value value) {
        return value.x().value().multiply(value.x().value())
                .add(value.y().value().multiply(value.y().value()));
    }

    private static boolean distanceWithin(RuntimeValue.Vector2Value left,
            RuntimeValue.Vector2Value right, BigDecimal expected, BigDecimal tolerance) {
        BigDecimal differenceX = left.x().value().subtract(right.x().value());
        BigDecimal differenceY = left.y().value().subtract(right.y().value());
        BigDecimal squared = differenceX.multiply(differenceX)
                .add(differenceY.multiply(differenceY));
        BigDecimal minimum = expected.subtract(tolerance).max(BigDecimal.ZERO);
        BigDecimal maximum = expected.add(tolerance);
        return squared.compareTo(minimum.multiply(minimum)) >= 0
                && squared.compareTo(maximum.multiply(maximum)) <= 0;
    }

    private static BigDecimal wrappedDistance(
            BigDecimal observed, BigDecimal expected, BigDecimal period) {
        BigDecimal remainder = observed.subtract(expected).remainder(period).abs();
        return remainder.min(period.subtract(remainder));
    }

    private static boolean matches(
            SimulationAssertion.EventSelector selector, RuntimeEvent event) {
        return event.type().equals(selector.eventType())
                && selector.subject().map(value -> event.subject().filter(value::equals).isPresent())
                        .orElse(true)
                && selector.source().map(value -> event.source().filter(value::equals).isPresent())
                        .orElse(true)
                && matchesValue(selector.attributes(),
                        new RuntimeValue.ObjectValue(event.attributes()));
    }

    private static boolean listContains(RuntimeValue value, RuntimeValue.ObjectValue selector) {
        return value instanceof RuntimeValue.ListValue list && list.values().stream()
                .anyMatch(item -> matchesValue(selector, item));
    }

    private static boolean matchesValue(RuntimeValue selector, RuntimeValue observed) {
        if (selector instanceof RuntimeValue.ObjectValue selected) {
            if (!(observed instanceof RuntimeValue.ObjectValue actual)) {
                return false;
            }
            return selected.fields().stream().allMatch(field -> actual.fields().stream()
                    .filter(candidate -> candidate.name().equals(field.name())).findFirst()
                    .map(candidate -> matchesValue(field.value(), candidate.value()))
                    .orElse(false));
        }
        return selector.equals(observed);
    }

    private static Optional<BigDecimal> numeric(RuntimeValue value) {
        return switch (value) {
            case RuntimeValue.IntegerValue integer -> Optional.of(BigDecimal.valueOf(integer.value()));
            case RuntimeValue.DecimalValue decimal -> Optional.of(decimal.value());
            default -> Optional.empty();
        };
    }

    private static Optional<RuntimeValue.Vector2Value> vector(RuntimeValue value) {
        return value instanceof RuntimeValue.Vector2Value vector
                ? Optional.of(vector) : Optional.empty();
    }

    private static RuntimeValue decimal(BigDecimal value) {
        return new RuntimeValue.DecimalValue(value);
    }

    private static RuntimeValue approximateScalar(BigDecimal expected, BigDecimal tolerance) {
        return RuntimeValues.object(
                RuntimeValues.field("expected", decimal(expected)),
                RuntimeValues.field("tolerance", decimal(tolerance)));
    }

    private static RuntimeValue area(
            SimulationAssertion.Area area, SimulationAssertion.AreaRelation relation) {
        return RuntimeValues.object(
                RuntimeValues.field("maximumX", decimal(area.maximumX())),
                RuntimeValues.field("maximumY", decimal(area.maximumY())),
                RuntimeValues.field("minimumX", decimal(area.minimumX())),
                RuntimeValues.field("minimumY", decimal(area.minimumY())),
                RuntimeValues.field("relation", RuntimeValues.enumValue(relation.name())));
    }

    private static SimulationAssertionEvidence evidence(Slot slot, String kind,
            EntityId entityId, String property, Optional<RuntimeValue> observed) {
        return new SimulationAssertionEvidence(Optional.of(slot.tick.simulationTickId()),
                slot.tick.executionEpochId(), slot.epochTick,
                Optional.of(slot.frame.frameId()), kind,
                Optional.ofNullable(entityId), Optional.ofNullable(property), observed);
    }

    private static SimulationAssertionEvidence incompleteEvidence(Slot slot) {
        String kind;
        Optional<RuntimeValue> observed = Optional.empty();
        if (slot.tick == null) {
            kind = "missingTick";
        } else if (slot.frame == null) {
            kind = "missingFrame";
            observed = Optional.of(RuntimeValues.enumValue(slot.tick.outcome().name()));
        } else if (slot.tick.outcome() != SimulationTickOutcome.COMPLETED
                || slot.tick.mutationOutcome() != SimulationMutationOutcome.KNOWN_COMPLETED) {
            kind = "tickOutcome";
            observed = Optional.of(RuntimeValues.enumValue(slot.tick.outcome().name()));
        } else {
            kind = "incompleteFrame";
        }
        return new SimulationAssertionEvidence(
                Optional.ofNullable(slot.tick).map(SimulationTick::simulationTickId),
                slot.executionEpochId,
                slot.epochTick,
                Optional.ofNullable(slot.frame).map(FrameSnapshot::frameId), kind,
                Optional.empty(), Optional.empty(), observed);
    }

    private static Outcome pass(String type, RuntimeValue expected, RuntimeValue observed) {
        return new Outcome(AssertionStatus.PASS, type, Optional.ofNullable(expected),
                Optional.ofNullable(observed), List.of(), false);
    }

    private static Outcome fail(String type, RuntimeValue expected, RuntimeValue observed,
            SimulationAssertionEvidence evidence) {
        return new Outcome(AssertionStatus.FAIL, type, Optional.ofNullable(expected),
                Optional.ofNullable(observed), List.of(evidence), false);
    }

    private static Outcome inconclusive(String type, RuntimeValue expected, RuntimeValue observed) {
        return inconclusive(type, expected, observed, List.of());
    }

    private static Outcome inconclusive(String type, RuntimeValue expected, RuntimeValue observed,
            List<SimulationAssertionEvidence> evidence) {
        return new Outcome(AssertionStatus.INCONCLUSIVE, type, Optional.ofNullable(expected),
                Optional.ofNullable(observed), evidence, true);
    }

    private static String message(AssertionStatus status) {
        return switch (status) {
            case PASS -> "simulation assertion passed";
            case FAIL -> "simulation assertion failed";
            case INCONCLUSIVE -> "simulation tick evidence is incomplete";
        };
    }

    private record Slot(ExecutionEpochId executionEpochId, long epochTick, SimulationTick tick,
            FrameSnapshot frame, boolean complete) {}

    private record Context(List<Slot> slots, int evidenceLimit) {
        private Slot terminal() { return slots.getLast(); }

        private boolean incomplete() { return slots.stream().anyMatch(slot -> !slot.complete); }
    }

    private record Outcome(AssertionStatus status, String type, Optional<RuntimeValue> expected,
            Optional<RuntimeValue> observed, List<SimulationAssertionEvidence> evidence,
            boolean incomplete) {}
}
