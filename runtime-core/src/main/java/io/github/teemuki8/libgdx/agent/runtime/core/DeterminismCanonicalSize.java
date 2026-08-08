package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Optional;

/**
 * Exact deterministic type-tagged byte count of retained determinism evidence.
 *
 * <p>Counts the canonical encoding applied to comparable frame evidence (frame id,
 * entities, events, decisions, and UI correlations) and the canonical {@link RuntimeValue}
 * encoding shared with {@link RecordingCanonicalSize}, without materializing
 * {@code toString()} output or temporary byte arrays. Multi-byte lengths are fixed-width
 * ({@link Integer#BYTES} counts and prefixes, {@link Long#BYTES} longs), strings are
 * length-prefixed exact UTF-8, and every variant carries a one-byte type tag. All arithmetic
 * saturates at {@link Long#MAX_VALUE} instead of overflowing.
 */
final class DeterminismCanonicalSize {
    private DeterminismCanonicalSize() {}

    /** Saturated addition: clamps at {@link Long#MAX_VALUE} instead of overflowing. */
    static long add(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    /** Exact canonical bytes of one complete frame evidence. */
    static long frame(FrameId frameId, List<EntitySnapshot> entities,
            List<RuntimeEvent> events, List<DecisionTrace> decisions,
            List<UiFrameCorrelation> ui) {
        long entitiesBytes = 0;
        for (EntitySnapshot entity : entities) {
            entitiesBytes = add(entitiesBytes, entity(entity));
        }
        long eventsBytes = 0;
        for (RuntimeEvent event : events) {
            eventsBytes = add(eventsBytes, event(event));
        }
        long decisionsBytes = 0;
        for (DecisionTrace decision : decisions) {
            decisionsBytes = add(decisionsBytes, decision(decision));
        }
        long uiBytes = 0;
        for (UiFrameCorrelation correlation : ui) {
            uiBytes = add(uiBytes, ui(correlation));
        }
        return frame(frameId, entitiesBytes, eventsBytes, decisionsBytes, uiBytes);
    }

    /** Frame id plus the four list count prefixes and their payloads. */
    static long frame(FrameId frameId, long entitiesBytes, long eventsBytes,
            long decisionsBytes, long uiBytes) {
        long size = Long.BYTES;
        size = add(size, listPrefix());
        size = add(size, entitiesBytes);
        size = add(size, listPrefix());
        size = add(size, eventsBytes);
        size = add(size, listPrefix());
        size = add(size, decisionsBytes);
        size = add(size, listPrefix());
        return add(size, uiBytes);
    }

    /** Comparable entity: id, type, optional display name, ordered properties, truncations. */
    static long entity(EntitySnapshot entity) {
        long size = entity(entity.id(), entity.type(), entity.displayName());
        size = add(size, properties(entity.properties()));
        return add(size, truncations(entity.truncations()));
    }

    /** Entity identity and display name; property and truncation lists are counted separately. */
    static long entity(EntityId id, EntityType type, Optional<String> displayName) {
        long size = string(id.value());
        size = add(size, string(type.value()));
        return add(size, optionalString(displayName));
    }

    /** Counted property list: list prefix plus one field per property. */
    static long properties(List<RuntimeValue.Field> properties) {
        long size = listPrefix();
        for (RuntimeValue.Field property : properties) {
            size = add(size, field(property));
        }
        return size;
    }

    /** Counted truncation list: list prefix plus dimension string and fixed counts. */
    static long truncations(List<Truncation> truncations) {
        long size = listPrefix();
        for (Truncation truncation : truncations) {
            size = add(size, string(truncation.dimension()));
            size = add(size, Long.BYTES * 3L + 1L);
        }
        return size;
    }

    /** Comparable event: type, optional subject/source, metadata, ordered attributes. */
    static long event(RuntimeEvent event) {
        long size = string(event.type().value());
        size = add(size, optionalEntityId(event.subject()));
        size = add(size, optionalEntityId(event.source()));
        size = add(size, metadata(event.metadata()));
        return add(size, properties(event.attributes()));
    }

    /** Comparable decision: type, actor, candidates, chosen candidate, reason, metadata, completion. */
    static long decision(DecisionTrace decision) {
        long size = string(decision.type().value());
        size = add(size, string(decision.actor().value()));
        size = add(size, candidates(decision.candidates()));
        size = add(size, optionalEntityId(decision.chosenCandidate()));
        size = add(size, optionalReason(decision.choiceReason()));
        size = add(size, metadata(decision.metadata()));
        return add(size, 1);
    }

    /** Counted candidate list: identity, status tag, reason, and attributes per candidate. */
    static long candidates(List<DecisionCandidate> candidates) {
        long size = listPrefix();
        for (DecisionCandidate candidate : candidates) {
            size = add(size, string(candidate.entityId().value()));
            size = add(size, 1);
            size = add(size, reason(candidate.reason()));
            size = add(size, properties(candidate.attributes()));
        }
        return size;
    }

    /** Comparable UI correlation: session id, optional frame id, optional token. */
    static long ui(UiFrameCorrelation ui) {
        long size = string(ui.uiSessionId());
        size = add(size, optionalString(ui.uiFrameId()));
        return add(size, optionalString(ui.correlationToken()));
    }

    /** One named property: name string plus canonical value. */
    static long field(RuntimeValue.Field field) {
        return add(string(field.name()), value(field.value()));
    }

    /** Canonical {@link RuntimeValue} payload mirroring {@link RecordingCanonicalSize}. */
    static long value(RuntimeValue value) {
        long size = 1;
        return switch (value) {
            case RuntimeValue.NullValue ignored -> size;
            case RuntimeValue.BooleanValue ignored -> add(size, 1);
            case RuntimeValue.IntegerValue ignored -> add(size, Long.BYTES);
            case RuntimeValue.DecimalValue decimal ->
                    add(size, string(decimal.value().toPlainString()));
            case RuntimeValue.StringValue text -> add(size, string(text.value()));
            case RuntimeValue.EnumValue symbol -> add(size, string(symbol.value()));
            case RuntimeValue.Vector2Value vector ->
                    add(add(size, value(vector.x())), value(vector.y()));
            case RuntimeValue.ListValue list -> add(size, values(list.values()));
            case RuntimeValue.ObjectValue object -> add(size, fields(object.fields()));
        };
    }

    private static long values(List<RuntimeValue> values) {
        long size = listPrefix();
        for (RuntimeValue value : values) {
            size = add(size, value(value));
        }
        return size;
    }

    private static long fields(List<RuntimeValue.Field> fields) {
        long size = listPrefix();
        for (RuntimeValue.Field field : fields) {
            size = add(size, field(field));
        }
        return size;
    }

    private static long metadata(FactMetadata metadata) {
        long size = optionalString(metadata.sourceSubsystem());
        size = add(size, optionalString(metadata.sourceLocation()));
        return add(size, optionalString(metadata.correlationId()));
    }

    private static long reason(Reason reason) {
        long size = string(reason.code());
        return add(size, optionalString(reason.description()));
    }

    private static long optionalReason(Optional<Reason> reason) {
        long size = 1;
        return reason.isPresent() ? add(size, reason(reason.orElseThrow())) : size;
    }

    private static long optionalEntityId(Optional<EntityId> entityId) {
        long size = 1;
        return entityId.isPresent()
                ? add(size, string(entityId.orElseThrow().value())) : size;
    }

    /** Length-prefixed UTF-8 string: {@link Integer#BYTES} count plus exact byte length. */
    static long string(String value) {
        return add(Integer.BYTES, utf8Length(value));
    }

    /** Optional string: one tag byte plus the length-prefixed string when present. */
    static long optionalString(Optional<String> value) {
        long size = 1;
        return value.isPresent() ? add(size, string(value.orElseThrow())) : size;
    }

    /** Fixed-width list count prefix: exactly {@link Integer#BYTES}. */
    static long listPrefix() {
        return Integer.BYTES;
    }

    /** Exact UTF-8 byte length without allocating an encoded array. */
    private static long utf8Length(String value) {
        long length = 0;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c < 0x80) {
                length++;
            } else if (c < 0x800) {
                length += 2;
            } else if (Character.isHighSurrogate(c)) {
                if (index + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(index + 1))) {
                    length += 4;
                    index++;
                } else {
                    length++;
                }
            } else if (Character.isLowSurrogate(c)) {
                length++;
            } else {
                length += 3;
            }
        }
        return length;
    }
}
