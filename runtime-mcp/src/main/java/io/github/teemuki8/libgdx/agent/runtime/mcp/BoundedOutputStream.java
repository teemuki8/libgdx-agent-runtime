package io.github.teemuki8.libgdx.agent.runtime.mcp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Bounded byte-array sink for streamed JSON-RPC serialization.
 *
 * <p>Counts every retained byte with a {@code long} and checks the whole incoming range
 * against the cap <em>before</em> retaining it: an overflowing range is rejected with
 * {@link OverflowException} without retaining any of its bytes, so the retained buffer
 * never exceeds {@code limit} (never {@code limit + 8000} or a partial overflowing range).
 * Zero-length ranges are no-ops. {@link #close()} is intentionally a no-op: Jackson closes
 * the generator it creates over this stream, which must not release the retained buffer.
 */
final class BoundedOutputStream extends OutputStream {
    private static final int INITIAL_CAPACITY = 256;

    private final long limit;
    private byte[] buffer = new byte[INITIAL_CAPACITY];
    private long count;
    private boolean overflowed;

    BoundedOutputStream(long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        this.limit = limit;
    }

    /** Returns the number of bytes retained so far; never exceeds {@code limit}. */
    long count() {
        return count;
    }

    /**
     * Returns whether the cap was rejected at least once. Serializers may wrap the
     * {@link OverflowException} (Jackson 3 reports it as a {@code DatabindException}
     * with reference-chain context), so the transport discriminates overflow by this
     * flag rather than by exception type.
     */
    boolean overflowed() {
        return overflowed;
    }

    /** Returns a copy of the retained bytes. */
    byte[] toByteArray() {
        return Arrays.copyOf(buffer, (int) count);
    }

    /** Writes the retained bytes to another stream in one call. */
    void writeTo(OutputStream out) throws IOException {
        out.write(buffer, 0, (int) count);
    }

    @Override
    public void write(int value) throws IOException {
        if (count >= limit) {
            overflowed = true;
            throw new OverflowException(limit);
        }
        ensureCapacity(count + 1);
        buffer[(int) count] = (byte) value;
        count++;
    }

    @Override
    public void write(byte[] bytes, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, bytes.length);
        if (len == 0) {
            return;
        }
        if ((long) len > limit - count) {
            overflowed = true;
            throw new OverflowException(limit);
        }
        ensureCapacity(count + len);
        System.arraycopy(bytes, off, buffer, (int) count, len);
        count += len;
    }

    private void ensureCapacity(long needed) {
        if (needed > buffer.length) {
            buffer = Arrays.copyOf(buffer,
                    (int) Math.min(Math.max(buffer.length * 2L, needed), limit));
        }
    }

    /**
     * The serialized message exceeded {@code limit}: byte {@code limit + 1} or later was
     * about to be retained and was not. The failure message is fixed and bounded, so
     * constructing it can never recurse into serialization.
     */
    @SuppressWarnings("serial")
    static final class OverflowException extends IOException {
        OverflowException(long limit) {
            super("output exceeds byte limit " + limit);
        }
    }
}
