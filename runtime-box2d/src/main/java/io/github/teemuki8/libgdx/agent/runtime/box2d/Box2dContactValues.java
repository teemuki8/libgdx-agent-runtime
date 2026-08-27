package io.github.teemuki8.libgdx.agent.runtime.box2d;

import io.github.teemuki8.libgdx.agent.runtime.core.EventSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.Truncation;
import java.util.ArrayList;
import java.util.List;

/** Closed {@link RuntimeValue} projection of immutable Box2D contact evidence. */
final class Box2dContactValues {
    private Box2dContactValues() {}

    static RuntimeValue policy(Box2dContactPolicy policy) {
        return RuntimeValues.object(
                field("begin", bool(policy.begin())),
                field("end", bool(policy.end())),
                field("postSolve", bool(policy.postSolve())));
    }

    static RuntimeValue limits(Box2dContactLimits limits) {
        return RuntimeValues.object(
                field("callbackRecordsPerTick", integer(limits.callbackRecordsPerTick())),
                field("activeContactsPerTick", integer(limits.activeContactsPerTick())),
                field("pointsPerContact", integer(limits.pointsPerContact())),
                field("impulsesPerContact", integer(limits.impulsesPerContact())),
                field("diagnosticsPerTick", integer(limits.diagnosticsPerTick())),
                field("retainedContactTicks", integer(limits.retainedContactTicks())),
                field("queryPageSize", integer(limits.queryPageSize())));
    }

    static RuntimeValue latestTick(Box2dContactTick tick) {
        if (tick == null) {
            return RuntimeValues.nullValue();
        }
        return RuntimeValues.object(
                field("simulationTickId", integer(tick.simulationTickId().value())),
                field("executionEpochId", integer(tick.executionEpochId().value())),
                field("epochTick", integer(tick.epochTick())),
                field("runtimeFrameId", integer(tick.runtimeFrameId().value())));
    }

    static RuntimeValue records(List<Box2dContactRecord> records) {
        return list(records.stream().map(Box2dContactValues::record).toList());
    }

    static RuntimeValue activeContacts(List<Box2dContactTick.ActiveContact> contacts) {
        return list(contacts.stream().map(Box2dContactValues::activeContact).toList());
    }

    static RuntimeValue counts(long observed, int retained, int limit) {
        return RuntimeValues.object(
                field("observed", integer(observed)),
                field("retained", integer(retained)),
                field("limit", integer(limit)));
    }

    static RuntimeValue diagnostics(List<Box2dContactTick.Diagnostic> diagnostics) {
        return list(diagnostics.stream().map(value -> RuntimeValues.object(
                field("code", enumeration(value.code().name())),
                field("observed", integer(value.observed())))).toList());
    }

    static RuntimeValue truncations(List<Truncation> truncations) {
        return list(truncations.stream().map(Box2dContactValues::truncation).toList());
    }

    static EventSpec event(String worldId, Box2dContactTick tick, Box2dContactRecord record) {
        return EventSpec.type("box2d.contact." + eventSuffix(record.phase()))
                .subject(io.github.teemuki8.libgdx.agent.runtime.core.EntityId.of(
                        "box2d.body." + record.endpointA().bodyId()))
                .source(io.github.teemuki8.libgdx.agent.runtime.core.EntityId.of(
                        "box2d.body." + record.endpointB().bodyId()))
                .attribute("worldId", RuntimeValues.string(worldId))
                .attribute("simulationTickId", integer(tick.simulationTickId().value()))
                .attribute("executionEpochId", integer(tick.executionEpochId().value()))
                .attribute("epochTick", integer(tick.epochTick()))
                .attribute("key", key(record.key()))
                .attribute("endpointA", endpoint(record.endpointA()))
                .attribute("endpointB", endpoint(record.endpointB()))
                .attribute("sensor", bool(record.sensor()))
                .attribute("touching", bool(record.touching()))
                .attribute("enabled", bool(record.enabled()))
                .attribute("availability", enumeration(record.availability().name()))
                .attribute("points", vectors(record.points()))
                .attribute("normal", optionalVector(record.normal().orElse(null)))
                .attribute("impulses", impulses(record.impulses()))
                .attribute("occurrence", integer(record.occurrence()))
                .attribute("truncations", truncations(record.truncations()));
    }

    private static RuntimeValue record(Box2dContactRecord record) {
        return RuntimeValues.object(
                field("phase", enumeration(record.phase().name())),
                field("key", key(record.key())),
                field("endpointA", endpoint(record.endpointA())),
                field("endpointB", endpoint(record.endpointB())),
                field("sensor", bool(record.sensor())),
                field("touching", bool(record.touching())),
                field("enabled", bool(record.enabled())),
                field("availability", enumeration(record.availability().name())),
                field("points", vectors(record.points())),
                field("normal", optionalVector(record.normal().orElse(null))),
                field("impulses", impulses(record.impulses())),
                field("occurrence", integer(record.occurrence())),
                field("truncations", truncations(record.truncations())));
    }

    private static RuntimeValue activeContact(Box2dContactTick.ActiveContact contact) {
        return RuntimeValues.object(
                field("key", key(contact.key())),
                field("endpointA", endpoint(contact.endpointA())),
                field("endpointB", endpoint(contact.endpointB())),
                field("sensor", bool(contact.sensor())),
                field("touching", bool(contact.touching())),
                field("enabled", bool(contact.enabled())),
                field("points", vectors(contact.points())),
                field("normal", optionalVector(contact.normal().orElse(null))),
                field("impulses", impulses(contact.impulses())),
                field("truncations", truncations(contact.truncations())));
    }

    private static RuntimeValue key(Box2dContactRecord.Key key) {
        return RuntimeValues.object(
                field("fixtureAId", RuntimeValues.string(key.fixtureAId())),
                field("childIndexA", integer(key.childIndexA())),
                field("fixtureBId", RuntimeValues.string(key.fixtureBId())),
                field("childIndexB", integer(key.childIndexB())));
    }

    private static RuntimeValue endpoint(Box2dContactRecord.Endpoint endpoint) {
        return RuntimeValues.object(
                field("bodyId", RuntimeValues.string(endpoint.bodyId())),
                field("fixtureId", RuntimeValues.string(endpoint.fixtureId())),
                field("childIndex", integer(endpoint.childIndex())),
                field("sensor", bool(endpoint.sensor())));
    }

    private static RuntimeValue vectors(List<Box2dVector> values) {
        return list(values.stream().map(Box2dContactValues::vector).toList());
    }

    private static RuntimeValue optionalVector(Box2dVector value) {
        return value == null ? RuntimeValues.nullValue() : vector(value);
    }

    private static RuntimeValue vector(Box2dVector value) {
        return RuntimeValues.vector2(value.x(), value.y());
    }

    private static RuntimeValue impulses(List<Box2dContactRecord.Impulse> values) {
        return list(values.stream().map(value -> RuntimeValues.object(
                field("normal", decimal(value.normal())),
                field("tangent", decimal(value.tangent())))).toList());
    }


    private static RuntimeValue truncation(Truncation value) {
        return RuntimeValues.object(
                field("dimension", RuntimeValues.string(value.dimension())),
                field("observed", integer(value.observed())),
                field("retained", integer(value.retained())),
                field("limit", integer(value.limit())));
    }

    private static String eventSuffix(Box2dContactRecord.Phase phase) {
        return switch (phase) {
            case BEGIN -> "begin";
            case END -> "end";
            case POST_SOLVE -> "postSolve";
        };
    }

    private static RuntimeValue.BooleanValue bool(boolean value) {
        return RuntimeValues.bool(value);
    }

    private static RuntimeValue.IntegerValue integer(long value) {
        return RuntimeValues.integer(value);
    }

    private static RuntimeValue.DecimalValue decimal(double value) {
        return RuntimeValues.decimal(value);
    }

    private static RuntimeValue.EnumValue enumeration(String value) {
        return RuntimeValues.enumValue(value);
    }

    private static RuntimeValue.ListValue list(List<? extends RuntimeValue> values) {
        return RuntimeValues.list(new ArrayList<>(values));
    }

    private static RuntimeValue.Field field(String name, RuntimeValue value) {
        return RuntimeValues.field(name, value);
    }
}
