package io.github.teemuki8.libgdx.agent.runtime.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Byte-cap output filter for streaming serialization.
 *
 * <p>Counts every delegated byte with a {@code long} and checks the whole incoming range
 * against the cap <em>before</em> delegating: an overflowing range is rejected with
 * {@link OverflowException} without writing any of its bytes, so the delegate never
 * receives byte {@code limit + 1} or later and never sees a partial overflowing range.
 * Zero-length ranges are no-ops. Lifecycle calls ({@link #close()} and {@link #flush()})
 * are intentionally not forwarded: serializers such as Jackson close the generator they
 * create over this stream, which must not close or flush a stream the caller owns.
 */
final class BoundedOutputStream extends OutputStream {
    private final OutputStream delegate;
    private final long limit;
    private long count;

    BoundedOutputStream(OutputStream delegate, long limit) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        this.limit = limit;
    }

    /** Returns the number of bytes accepted so far. */
    long count() {
        return count;
    }

    @Override
    public void write(int value) throws IOException {
        if (count >= limit) {
            throw new OverflowException(limit);
        }
        delegate.write(value);
        count++;
    }

    @Override
    public void write(byte[] bytes, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, bytes.length);
        if (len == 0) {
            return;
        }
        if ((long) len > limit - count) {
            throw new OverflowException(limit);
        }
        delegate.write(bytes, off, len);
        count += len;
    }

    /**
     * The serialized output exceeded {@code limit}: byte {@code limit + 1} or later was
     * about to be written and was not delegated. The failure message is fixed and bounded,
     * so constructing it can never recurse into serialization.
     */
    @SuppressWarnings("serial")
    static final class OverflowException extends IOException {
        OverflowException(long limit) {
            super("output exceeds byte limit " + limit);
        }
    }
}
