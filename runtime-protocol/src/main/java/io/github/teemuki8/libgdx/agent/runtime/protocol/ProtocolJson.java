package io.github.teemuki8.libgdx.agent.runtime.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeAssertion;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingActionEntry;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingEntry;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingFrameEntry;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingInputEntry;
import io.github.teemuki8.libgdx.agent.runtime.core.RecordingTickEntry;
import java.io.IOException;
import java.util.Objects;

/** Hardened deterministic JSON codec for protocol V1. */
public final class ProtocolJson {
    /** Maximum raw request bytes checked before parsing. */
    public static final int MAX_REQUEST_BYTES = 1_048_576;
    /** Maximum encoded response bytes. */
    public static final int MAX_RESPONSE_BYTES = 8_388_608;
    /** Maximum JSON nesting depth. */
    public static final int MAX_NESTING_DEPTH = 32;
    /** Maximum string token length. */
    public static final int MAX_STRING_LENGTH = 16_384;
    /** Maximum remotely requested result items. */
    public static final int MAX_RESULT_ITEMS = 1_000;
    private static final int MAX_IDENTIFIER_LENGTH = 256;
    private static final ObjectMapper MAPPER = createMapper();

    private ProtocolJson() {}

    /** Returns an isolated copy of the canonical mapper. */
    public static ObjectMapper mapper() {
        return MAPPER.copy();
    }

    /** Decodes a byte-bounded strict request. */
    public static RuntimeRequest decodeRequest(byte[] json) {
        Objects.requireNonNull(json, "json");
        if (json.length > MAX_REQUEST_BYTES) {
            throw new ProtocolJsonException(
                    ProtocolErrorCode.LIMIT_EXCEEDED, "request exceeds byte limit", null);
        }
        try {
            return MAPPER.readValue(json, RuntimeRequest.class);
        } catch (IOException failure) {
            throw new ProtocolJsonException(
                    ProtocolErrorCode.INVALID_QUERY, "malformed protocol request", failure);
        }
    }

    /** Encodes a locally constructed request with the same byte bound. */
    public static byte[] encode(RuntimeRequest request) {
        return encodeBounded(request, MAX_REQUEST_BYTES, "request");
    }

    /** Encodes a response and enforces the response byte bound. */
    public static byte[] encode(RuntimeResponse response) {
        return encodeBounded(response, MAX_RESPONSE_BYTES, "response");
    }

    /** Decodes a response for clients and contract tests. */
    public static RuntimeResponse decodeResponse(byte[] json) {
        Objects.requireNonNull(json, "json");
        if (json.length > MAX_RESPONSE_BYTES) {
            throw new ProtocolJsonException(
                    ProtocolErrorCode.LIMIT_EXCEEDED, "response exceeds byte limit", null);
        }
        try {
            return MAPPER.readValue(json, RuntimeResponse.class);
        } catch (IOException failure) {
            throw new ProtocolJsonException(
                    ProtocolErrorCode.INTERNAL_ERROR, "malformed protocol response", failure);
        }
    }

    static String requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds identifier limit");
        }
        return value;
    }

    static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds string limit");
        }
        return value;
    }

    private static byte[] encodeBounded(Object value, int maximum, String kind) {
        Objects.requireNonNull(value, "value");
        try {
            byte[] encoded = MAPPER.writeValueAsBytes(value);
            if (encoded.length > maximum) {
                throw new ProtocolJsonException(
                        ProtocolErrorCode.LIMIT_EXCEEDED, kind + " exceeds byte limit", null);
            }
            return encoded;
        } catch (JsonProcessingException failure) {
            throw new ProtocolJsonException(
                    ProtocolErrorCode.INTERNAL_ERROR, "cannot encode protocol " + kind, failure);
        }
    }

    private static ObjectMapper createMapper() {
        JsonFactory factory = JsonFactory.builder().streamReadConstraints(
                StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_NESTING_DEPTH)
                        .maxStringLength(MAX_STRING_LENGTH)
                        .maxNumberLength(128)
                        .build()).build();
        ObjectMapper mapper = JsonMapper.builder(factory)
                .enable(MapperFeature.BLOCK_UNSAFE_POLYMORPHIC_BASE_TYPES)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .addModule(new Jdk8Module())
                .addModule(new JavaTimeModule())
                .build();
        mapper.addMixIn(RuntimeValue.class, RuntimeValueMixin.class);
        mapper.addMixIn(RuntimeAssertion.class, RuntimeAssertionMixin.class);
        mapper.addMixIn(RecordingEntry.class, RecordingEntryMixin.class);
        return mapper;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "valueType")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = RuntimeValue.NullValue.class, name = "null"),
        @JsonSubTypes.Type(value = RuntimeValue.BooleanValue.class, name = "boolean"),
        @JsonSubTypes.Type(value = RuntimeValue.IntegerValue.class, name = "integer"),
        @JsonSubTypes.Type(value = RuntimeValue.DecimalValue.class, name = "decimal"),
        @JsonSubTypes.Type(value = RuntimeValue.StringValue.class, name = "string"),
        @JsonSubTypes.Type(value = RuntimeValue.EnumValue.class, name = "enum"),
        @JsonSubTypes.Type(value = RuntimeValue.Vector2Value.class, name = "vector2"),
        @JsonSubTypes.Type(value = RuntimeValue.ListValue.class, name = "list"),
        @JsonSubTypes.Type(value = RuntimeValue.ObjectValue.class, name = "object")
    })
    private interface RuntimeValueMixin {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "assertionType")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = RuntimeAssertion.EntityExists.class, name = "entityExists"),
        @JsonSubTypes.Type(value = RuntimeAssertion.EntityDoesNotExist.class,
                name = "entityDoesNotExist"),
        @JsonSubTypes.Type(value = RuntimeAssertion.PropertyEquals.class, name = "propertyEquals"),
        @JsonSubTypes.Type(value = RuntimeAssertion.PropertyChangesFrom.class,
                name = "propertyChangesFrom"),
        @JsonSubTypes.Type(value = RuntimeAssertion.PropertyRemainsWithinRange.class,
                name = "propertyRemainsWithinRange"),
        @JsonSubTypes.Type(value = RuntimeAssertion.EventOccurs.class, name = "eventOccurs"),
        @JsonSubTypes.Type(value = RuntimeAssertion.EventDoesNotOccur.class,
                name = "eventDoesNotOccur"),
        @JsonSubTypes.Type(value = RuntimeAssertion.EventOccursExactly.class,
                name = "eventOccursExactly"),
        @JsonSubTypes.Type(value = RuntimeAssertion.DecisionSelected.class,
                name = "decisionSelected"),
        @JsonSubTypes.Type(value = RuntimeAssertion.DecisionRejected.class,
                name = "decisionRejected"),
        @JsonSubTypes.Type(value = RuntimeAssertion.EntityCountStaysBelow.class,
                name = "entityCountStaysBelow"),
        @JsonSubTypes.Type(value = RuntimeAssertion.SnapshotsEquivalent.class,
                name = "snapshotsEquivalent")
    })
    private interface RuntimeAssertionMixin {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "entryType")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = RecordingActionEntry.class, name = "action"),
        @JsonSubTypes.Type(value = RecordingInputEntry.class, name = "input"),
        @JsonSubTypes.Type(value = RecordingFrameEntry.class, name = "frame"),
        @JsonSubTypes.Type(value = RecordingTickEntry.class, name = "tick")
    })
    private interface RecordingEntryMixin {}

    /** Local codec failure with a stable safe category. */
    @SuppressWarnings("serial")
    public static final class ProtocolJsonException extends RuntimeException {
        private final ProtocolErrorCode code;

        ProtocolJsonException(ProtocolErrorCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        /** Returns the safe error category. */
        public ProtocolErrorCode code() {
            return code;
        }
    }
}
