package io.github.teemuki8.libgdx.agent.runtime.examples;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import io.github.teemuki8.libgdx.agent.runtime.mcp.RuntimeMcpServer;
import io.github.teemuki8.libgdx.agent.runtime.protocol.PublishedRuntime;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;

/**
 * Minimal same-JVM libGDX launcher whose standard input and output are reserved for local MCP.
 *
 * <p>The application remains authoritative for the render thread and dispatches every mutation
 * through {@link com.badlogic.gdx.Application#postRunnable(Runnable)}. Human diagnostics belong on
 * standard error so JSON-RPC framing on standard output cannot be corrupted.
 */
public final class SameJvmMcpApplication extends ApplicationAdapter {
    private final RuntimeRegistry registry = new RuntimeRegistry();
    private ControlledWorkflowExample example;
    private PublishedRuntime publication;
    private RuntimeMcpServer server;

    /** Starts a hidden LWJGL3 application and a local stdio MCP server in the same JVM. */
    public static void main(String[] args) {
        if (args.length != 0) {
            throw new IllegalArgumentException("same-JVM MCP example accepts no arguments");
        }
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("libGDX Agent Runtime MCP Example");
        configuration.setWindowedMode(320, 180);
        configuration.setInitialVisible(false);
        configuration.setResizable(false);
        configuration.useVsync(false);
        configuration.setForegroundFPS(60);
        configuration.disableAudio(true);
        new Lwjgl3Application(new SameJvmMcpApplication(), configuration);
    }

    @Override public void create() {
        example = ControlledWorkflowExample.create(Gdx.app::postRunnable);
        publication = registry.publish(example.runtime());
        server = RuntimeMcpServer.open(
                new RuntimeProtocolService(registry), System.in, System.out);
        Thread.startVirtualThread(() -> {
            server.awaitTermination();
            if (Gdx.app != null) {
                Gdx.app.postRunnable(Gdx.app::exit);
            }
        });
    }

    @Override public void render() {
        Gdx.gl.glClearColor(0.04f, 0.06f, 0.1f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    }

    @Override public void dispose() {
        if (server != null) {
            server.close();
        }
        if (publication != null) {
            publication.close();
        }
        if (example != null) {
            example.close();
        }
    }
}
