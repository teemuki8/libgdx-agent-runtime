package io.github.teemuki8.libgdx.agent.runtime.mcp;

import io.github.teemuki8.libgdx.agent.runtime.protocol.ProtocolJson;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeVersion;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Mono;

/** MCP SDK 2.0 server exposing only the fixed runtime tools over one stdio connection. */
public final class RuntimeMcpServer implements AutoCloseable {
    /**
     * JSON-RPC implementation-defined error code returned when an outbound response message
     * would exceed {@link ProtocolJson#MAX_RESPONSE_BYTES}. The transport cannot recover the
     * oversized message, so the client receives one bounded typed error instead of the
     * partial oversized frame; later requests still work.
     */
    static final int RESPONSE_LIMIT_EXCEEDED = -32001;

    private final StdioProvider transport;
    private final RuntimeToolHandler handler;
    private final McpAsyncServer server;
    private final AtomicBoolean closed = new AtomicBoolean();

    private RuntimeMcpServer(
            RuntimeProtocolService protocol, InputStream input, OutputStream output) {
        this(protocol, input, output, new ConstrainedMcpJsonMapper());
    }

    RuntimeMcpServer(
            RuntimeProtocolService protocol,
            InputStream input,
            OutputStream output,
            ConstrainedMcpJsonMapper mapper) {
        this(protocol, input, output, mapper, ProtocolJson.MAX_RESPONSE_BYTES);
    }

    /**
     * Test seam mirroring {@link io.github.teemuki8.libgdx.agent.runtime.mcp.BoundedJsonRpcFramer}:
     * shrinks the authoritative outbound response cap without changing any production path.
     */
    RuntimeMcpServer(
            RuntimeProtocolService protocol,
            InputStream input,
            OutputStream output,
            ConstrainedMcpJsonMapper mapper,
            int maxResponseBytes) {
        transport = new StdioProvider(input, output, mapper, maxResponseBytes);
        RuntimeToolCatalog catalog = new RuntimeToolCatalog(
                protocol.toolNames(), protocol.actionCatalog(), protocol.inputCatalog());
        handler = new RuntimeToolHandler(protocol, catalog);
        McpServer.AsyncSpecification<?> specification = McpServer.async(transport)
                .serverInfo("libgdx-agent-runtime", RuntimeVersion.current())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .validateToolInputs(false)
                .requestTimeout(Duration.ofSeconds(30));
        for (McpSchema.Tool tool : catalog.tools()) {
            specification.toolCall(tool, (exchange, request) -> handler.handle(request));
        }
        server = specification.build();
    }

    /** Opens a stdio-only server; no network listener or filesystem access is created. */
    public static RuntimeMcpServer open(
            RuntimeProtocolService protocol, InputStream input, OutputStream output) {
        return new RuntimeMcpServer(
                Objects.requireNonNull(protocol, "protocol"),
                Objects.requireNonNull(input, "input"),
                Objects.requireNonNull(output, "output"));
    }

    /** Waits for EOF, transport failure, or explicit closure. */
    public void awaitTermination() {
        transport.termination().join();
    }

    /** Closes SDK, transport, and owned adapter threads. */
    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            server.close();
            handler.close();
            transport.close();
        }
    }

    private static final class StdioProvider implements McpServerTransportProvider {
        private final ConstrainedMcpJsonMapper mapper;
        private final int maxResponseBytes;
        private final InputStream input;
        private final OutputStream output;
        private final ExecutorService readerExecutor =
                Executors.newSingleThreadExecutor(Thread.ofVirtual().name("runtime-mcp-input").factory());
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CompletableFuture<Void> termination = new CompletableFuture<>();
        private volatile McpServerSession session;

        StdioProvider(InputStream input, OutputStream output,
                ConstrainedMcpJsonMapper mapper, int maxResponseBytes) {
            this.input = input;
            this.output = output;
            this.mapper = mapper;
            this.maxResponseBytes = maxResponseBytes;
        }

        @Override public void setSessionFactory(McpServerSession.Factory factory) {
            session = factory.create(new StdioTransport());
            readerExecutor.submit(this::readLoop);
        }

        @Override public Mono<Void> notifyClients(String method, Object params) {
            McpServerSession current = session;
            return current == null ? Mono.error(new IllegalStateException("stdio is not initialized"))
                    : current.sendNotification(method, params);
        }

        @Override public Mono<Void> notifyClient(String sessionId, String method, Object params) {
            McpServerSession current = session;
            if (current == null || !current.getId().equals(sessionId)) {
                return Mono.error(new IllegalStateException("unknown stdio session"));
            }
            return current.sendNotification(method, params);
        }

        @Override public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(this::close);
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                McpServerSession current = session;
                if (current != null) {
                    current.close();
                }
                readerExecutor.shutdownNow();
                termination.complete(null);
            }
        }

        CompletableFuture<Void> termination() {
            return termination;
        }

        private void readLoop() {
            BoundedJsonRpcFramer framer = new BoundedJsonRpcFramer(input);
            try {
                while (!closed.get()) {
                    final String frame;
                    try {
                        frame = framer.read();
                    } catch (BoundedJsonRpcFramer.RejectedLineException rejection) {
                        writeError(McpSchema.ErrorCodes.PARSE_ERROR, rejection.getMessage());
                        continue;
                    }
                    if (frame == null) {
                        break;
                    }
                    McpServerSession current = session;
                    if (current == null) {
                        throw new IllegalStateException("stdio session was not initialized");
                    }
                    final McpSchema.JSONRPCMessage message;
                    try {
                        message = McpSchema.deserializeJsonRpcMessage(mapper, frame);
                    } catch (IOException malformed) {
                        writeError(McpSchema.ErrorCodes.PARSE_ERROR,
                                "malformed JSON-RPC message");
                        continue;
                    } catch (IllegalArgumentException shape) {
                        writeError(McpSchema.ErrorCodes.INVALID_REQUEST,
                                "message is not a JSON-RPC request or notification");
                        continue;
                    }
                    current.handle(message).block();
                }
                termination.complete(null);
            } catch (IOException | RuntimeException failure) {
                if (!closed.get()) {
                    termination.completeExceptionally(failure);
                }
            } finally {
                close();
            }
        }

        /** Writes a bounded JSON-RPC error response; rejected input is never echoed. */
        private void writeError(int code, String message) {
            writeError(code, message, null);
        }

        /**
         * Writes one bounded JSON-RPC error response with the given id. The envelope is a
         * tiny fixed map serialized directly (never through the bounded response sink), so
         * the fallback itself is bounded and cannot recurse into response serialization.
         */
        private void writeError(int code, String message, Object id) {
            if (closed.get()) {
                return;
            }
            try {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("code", code);
                detail.put("message", message);
                detail.put("data", null);
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("jsonrpc", "2.0");
                envelope.put("id", id);
                envelope.put("error", detail);
                String json = mapper.writeValueAsString(envelope);
                synchronized (output) {
                    output.write(json.getBytes(StandardCharsets.UTF_8));
                    output.write('\n');
                    output.flush();
                }
            } catch (IOException failure) {
                throw new IllegalStateException("failed to write stdio error response", failure);
            }
        }

        private final class StdioTransport implements McpServerTransport {
            @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
                return Mono.fromRunnable(() -> {
                    if (closed.get()) {
                        return;
                    }
                    // The actual message is serialized exactly once into a bounded
                    // buffer; those exact checked bytes plus the newline are written.
                    BoundedOutputStream sink = new BoundedOutputStream(maxResponseBytes);
                    try {
                        mapper.writeValue(sink, message);
                        synchronized (output) {
                            sink.writeTo(output);
                            output.write('\n');
                            output.flush();
                        }
                    } catch (RuntimeException | IOException failure) {
                        // Jackson 3 reports the sink's checked rejection as an unchecked
                        // DatabindException with reference-chain context, so overflow is
                        // detected by the sink's own flag, never by exception type. On
                        // overflow, never emit a partial oversized frame and never return
                        // an erroring Mono (a sendMessage error terminates the whole
                        // stdio session): one bounded typed error directly, then the
                        // session keeps working. Any other failure stays a real
                        // transport error.
                        if (sink.overflowed()) {
                            writeOverflowError(message);
                            return;
                        }
                        throw new IllegalStateException(
                                "failed to write stdio response", failure);
                    }
                });
            }

            /**
             * Answers an oversized outbound message with one bounded typed JSON-RPC error,
             * echoing the response id (or a null id for outbound notifications). The error
             * is written directly to stdio, never through {@code sendMessage}, so it cannot
             * recurse or be bounded again.
             */
            private void writeOverflowError(McpSchema.JSONRPCMessage message) {
                Object id = message instanceof McpSchema.JSONRPCResponse response
                        ? response.id() : null;
                writeError(RESPONSE_LIMIT_EXCEEDED, "response exceeds byte limit", id);
            }

            @Override public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
                return mapper.convertValue(data, typeRef);
            }

            @Override public Mono<Void> closeGracefully() {
                return Mono.fromRunnable(StdioProvider.this::close);
            }

            @Override public void close() {
                StdioProvider.this.close();
            }
        }
    }
}
