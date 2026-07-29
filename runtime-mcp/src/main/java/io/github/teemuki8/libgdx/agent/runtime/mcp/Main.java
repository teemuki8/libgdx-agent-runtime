package io.github.teemuki8.libgdx.agent.runtime.mcp;

import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;

/** Stdio-only production entry point. */
public final class Main {
    private Main() {}

    /** Starts one MCP connection and exits when stdin closes. */
    public static void main(String[] args) {
        if (args.length != 0) {
            throw new IllegalArgumentException("runtime MCP accepts no command-line arguments");
        }
        try (RuntimeMcpServer server = RuntimeMcpServer.open(
                new RuntimeProtocolService(RuntimeRegistry.global()), System.in, System.out)) {
            server.awaitTermination();
        }
    }
}
