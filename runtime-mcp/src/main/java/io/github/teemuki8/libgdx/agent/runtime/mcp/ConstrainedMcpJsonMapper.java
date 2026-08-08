package io.github.teemuki8.libgdx.agent.runtime.mcp;

import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolJson;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import java.io.IOException;
import java.io.OutputStream;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link McpJsonMapper} with the same stream constraints as {@link ProtocolJson}: a 1 MiB
 * request, 32-level nesting, 16,384-character strings, and 128-digit numbers. Every other
 * behavior of the MCP SDK Jackson 3 mapper is retained by delegating to
 * {@link JacksonMcpJsonMapper}.
 */
public class ConstrainedMcpJsonMapper implements McpJsonMapper {
    /** Matches the number-token bound used by {@link ProtocolJson}. */
    private static final int MAX_NUMBER_LENGTH = 128;

    private final JacksonMcpJsonMapper delegate;

    /** Creates a mapper constrained to the protocol codec limits. */
    public ConstrainedMcpJsonMapper() {
        this(new JacksonMcpJsonMapper(createConstrainedJsonMapper()));
    }

    ConstrainedMcpJsonMapper(JacksonMcpJsonMapper delegate) {
        this.delegate = delegate;
    }

    private static JsonMapper createConstrainedJsonMapper() {
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(ProtocolJson.MAX_NESTING_DEPTH)
                        .maxStringLength(ProtocolJson.MAX_STRING_LENGTH)
                        .maxNumberLength(MAX_NUMBER_LENGTH)
                        .build())
                .build();
        return JsonMapper.builder(factory).build();
    }

    @Override
    public <T> T readValue(String content, Class<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T readValue(byte[] content, Class<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T readValue(String content, TypeRef<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T convertValue(Object fromValue, Class<T> type) {
        return delegate.convertValue(fromValue, type);
    }

    @Override
    public <T> T convertValue(Object fromValue, TypeRef<T> type) {
        return delegate.convertValue(fromValue, type);
    }

    @Override
    public String writeValueAsString(Object value) throws IOException {
        return delegate.writeValueAsString(value);
    }

    @Override
    public byte[] writeValueAsBytes(Object value) throws IOException {
        return delegate.writeValueAsBytes(value);
    }

    /**
     * Serializes a value through the underlying Jackson 3 streaming API directly to the
     * given stream.
     *
     * <p>{@link McpJsonMapper#writeValueAsBytes} hides that path behind a full-size byte
     * array; streaming into a {@link BoundedOutputStream} lets the transport enforce
     * {@link ProtocolJson#MAX_RESPONSE_BYTES} during serialization instead of after. The
     * stream is not closed by this method (Jackson closes only the generator it creates).
     */
    public void writeValue(OutputStream out, Object value) throws IOException {
        delegate.getJsonMapper().writeValue(out, value);
    }
}
