package io.github.teemuki8.libgdx.agent.runtime.core;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Counts the exact bytes in the versioned, type-tagged core recording encoding. */
final class RecordingCanonicalSize {
    private static final int MAGIC_BYTES = 8;

    private RecordingCanonicalSize() {}

    static long manifest(RecordingMetadata metadata, List<RecordingEntry> entries) {
        long size = MAGIC_BYTES;
        size = add(size, Integer.BYTES);
        size = string(size, metadata.runtimeVersion());
        size = string(size, metadata.protocolVersion());
        size = add(size, Integer.BYTES);
        for (RecordingCapabilityVersion capability : metadata.capabilityVersions()) {
            size = string(size, capability.capabilityId());
            size = string(size, capability.version());
        }
        size = string(size, metadata.recordingId());
        size = string(size, metadata.sessionId().value());
        size = add(size, Long.BYTES);
        size = optionalString(size, metadata.scenarioId());
        size = optionalString(size, metadata.checkpointId());
        size = add(size, 1 + (metadata.randomSeed().isPresent() ? Long.BYTES : 0));
        size = value(size, metadata.configuration());
        size = add(size, Integer.BYTES * 2L);
        size = add(size, metadata.truncations().size() * (Integer.BYTES + Long.BYTES * 3L + 1L));
        size = add(size, 2 + Long.BYTES * 3L);
        size = add(size, Integer.BYTES);
        for (RecordingEntry entry : entries) {
            size = entry(size, entry);
        }
        return size;
    }

    static long truncationBytes() {
        return Integer.BYTES + Long.BYTES * 3L + 1L;
    }

    static long entry(RecordingEntry entry) {
        return entry(0, entry);
    }

    private static long entry(long size, RecordingEntry entry) {
        size = add(size, 1 + Long.BYTES);
        if (entry instanceof RecordingActionEntry action) {
            size = string(size, action.invocation().toString());
            return value(size, action.parameters());
        }
        if (entry instanceof RecordingInputEntry input) {
            return string(size, input.injection().toString());
        }
        if (entry instanceof RecordingFrameEntry frame) {
            size = add(size, Long.BYTES * 3L + 1 + Integer.BYTES * 2L);
            return add(size, frame.baselineKind().isPresent() ? Integer.BYTES : 0);
        }
        if (entry instanceof RecordingTickEntry) {
            return add(size, Long.BYTES * 4L);
        }
        throw new IllegalArgumentException("unsupported recording entry");
    }

    private static long value(long size, RuntimeValue value) {
        size = add(size, 1);
        return switch (value) {
            case RuntimeValue.NullValue ignored -> size;
            case RuntimeValue.BooleanValue ignored -> add(size, 1);
            case RuntimeValue.IntegerValue ignored -> add(size, Long.BYTES);
            case RuntimeValue.DecimalValue decimal -> string(size, decimal.value().toPlainString());
            case RuntimeValue.StringValue text -> string(size, text.value());
            case RuntimeValue.EnumValue symbol -> string(size, symbol.value());
            case RuntimeValue.Vector2Value vector ->
                    value(value(size, vector.x()), vector.y());
            case RuntimeValue.ListValue list -> values(size, list.values());
            case RuntimeValue.ObjectValue object -> fields(size, object.fields());
        };
    }

    private static long values(long size, List<RuntimeValue> values) {
        size = add(size, Integer.BYTES);
        for (RuntimeValue value : values) {
            size = value(size, value);
        }
        return size;
    }

    private static long fields(long size, List<RuntimeValue.Field> fields) {
        size = add(size, Integer.BYTES);
        for (RuntimeValue.Field field : fields) {
            size = string(size, field.name());
            size = value(size, field.value());
        }
        return size;
    }

    private static long optionalString(long size, java.util.Optional<String> value) {
        size = add(size, 1);
        return value.isPresent() ? string(size, value.orElseThrow()) : size;
    }

    private static long string(long size, String value) {
        return add(size, Integer.BYTES + value.getBytes(StandardCharsets.UTF_8).length);
    }

    private static long add(long left, long right) {
        return Math.addExact(left, right);
    }
}
