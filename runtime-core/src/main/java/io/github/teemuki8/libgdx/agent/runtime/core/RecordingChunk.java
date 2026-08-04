package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.List;
import java.util.Objects;

/** One bounded page of an immutable stopped recording manifest. */
public record RecordingChunk(RecordingMetadata metadata, List<RecordingEntry> entries,
        int offset, int nextOffset, boolean hasMore) {
    /** Validates and copies chunk evidence. */
    public RecordingChunk {
        Objects.requireNonNull(metadata, "metadata");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (offset < 0 || nextOffset < offset || nextOffset != offset + entries.size()
                || hasMore != (nextOffset < metadata.retainedItems())) {
            throw new IllegalArgumentException("recording chunk evidence is inconsistent");
        }
    }
}
