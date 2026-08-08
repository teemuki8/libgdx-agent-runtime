package io.github.teemuki8.libgdx.agent.runtime.mcp;

import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Byte-counted newline framing for JSON-RPC over stdio.
 *
 * <p>Reads raw bytes instead of {@code BufferedReader.readLine()}, so the protocol codec's
 * {@link ProtocolJson#MAX_REQUEST_BYTES} bound protects actual stdio framing before any
 * string is materialized. Frames are decoded with a strict {@link CharsetDecoder}
 * ({@link CodingErrorAction#REPORT}), so malformed UTF-8 never reaches the JSON parser.
 *
 * <p>Once a frame exceeds the byte limit, the retained-byte count is capped at
 * {@code MAX_FRAME_BYTES + 1} and every further byte is drained without retention, so a
 * newline-free frame of any length (even past an {@code int} counter's range) cannot wrap,
 * re-enter retention, or change the rejection. An oversized frame surfaces as a recoverable
 * {@link RejectedLineException} once its newline arrives; the caller may keep reading. EOF
 * inside a frame surfaces as a terminal {@link UnterminatedFrameException}. A trailing
 * {@code \r} of a {@code \r\n} terminator is stripped for {@code readLine}-compatible
 * behavior; the raw line length (including that {@code \r}) counts toward the byte bound.
 */
public final class BoundedJsonRpcFramer {
    /** Maximum frame content bytes, excluding the line terminator. */
    public static final int MAX_FRAME_BYTES = ProtocolJson.MAX_REQUEST_BYTES;

    private final InputStream input;
    private final byte[] frame = new byte[MAX_FRAME_BYTES + 1];
    private final byte[] chunk = new byte[8192];
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    private int chunkPos;
    private int chunkLen;
    /** Content bytes retained for the frame being read; capped at {@link #MAX_FRAME_BYTES} + 1. */
    private int count;
    /** True once the current frame exceeded the byte limit; further bytes are drained. */
    private boolean oversized;

    /** Creates a framer over the given input; the stream is not owned or closed. */
    public BoundedJsonRpcFramer(InputStream input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    /**
     * Test-only seam that seeds the frame state as if {@code initialCount} content bytes had
     * already been consumed, so arbitrarily long frames can be exercised without allocating
     * them. {@code initialCount} is capped at {@code MAX_FRAME_BYTES + 1}, mirroring the
     * retained-state invariant after the limit is crossed.
     */
    BoundedJsonRpcFramer(InputStream input, int initialCount, boolean oversized) {
        this(input);
        if (initialCount < 0 || initialCount > MAX_FRAME_BYTES + 1) {
            throw new IllegalArgumentException("initialCount must be within [0, limit+1]");
        }
        this.count = initialCount;
        this.oversized = oversized;
    }

    /**
     * Reads the next newline-terminated frame and returns its strict-UTF-8 decoded
     * content with the line terminator removed, or {@code null} on a clean EOF at a
     * frame boundary.
     *
     * @throws RejectedLineException when a newline-terminated frame exceeds the byte
     * bound or is not valid UTF-8; the frame is fully consumed through its newline and
     * reading may continue
     * @throws UnterminatedFrameException when EOF is reached inside a frame; no further
     * reading is possible
     */
    public String read() throws IOException {
        while (true) {
            int b = readByte();
            if (b < 0) {
                if (count == 0 && !oversized) {
                    return null;
                }
                throw new UnterminatedFrameException("unterminated frame at end of input");
            }
            if (b == '\n') {
                if (oversized || count > MAX_FRAME_BYTES) {
                    clearFrameState();
                    throw new RejectedLineException(
                            "frame exceeds " + MAX_FRAME_BYTES + " byte limit");
                }
                int length = count;
                clearFrameState();
                return decode(length);
            }
            if (!oversized) {
                if (count == MAX_FRAME_BYTES + 1) {
                    // Limit+1 content bytes are retained; drain everything further.
                    oversized = true;
                } else {
                    frame[count] = (byte) b;
                    count++;
                }
            }
        }
    }

    private void clearFrameState() {
        count = 0;
        oversized = false;
    }

    private String decode(int length) throws RejectedLineException {
        decoder.reset();
        int end = length;
        if (end > 0 && frame[end - 1] == '\r') {
            end--;
        }
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(frame, 0, end));
            return decoded.toString();
        } catch (CharacterCodingException failure) {
            throw new RejectedLineException("frame is not valid UTF-8");
        }
    }

    private int readByte() throws IOException {
        if (chunkPos >= chunkLen) {
            chunkLen = input.read(chunk);
            chunkPos = 0;
            if (chunkLen < 0) {
                return -1;
            }
        }
        return chunk[chunkPos++] & 0xFF;
    }

    /** Base class for frame-level failures. */
    @SuppressWarnings("serial")
    public static class FrameException extends IOException {
        FrameException(String message) {
            super(message);
        }
    }

    /**
     * A newline-terminated frame was rejected at the framing layer (byte bound or UTF-8
     * validity). The frame was consumed through its newline, so reading may continue.
     */
    public static final class RejectedLineException extends FrameException {
        RejectedLineException(String message) {
            super(message);
        }
    }

    /** EOF was reached inside an unterminated frame; reading cannot continue. */
    public static final class UnterminatedFrameException extends FrameException {
        UnterminatedFrameException(String message) {
            super(message);
        }
    }
}
