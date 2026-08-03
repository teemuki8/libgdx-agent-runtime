package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.mcp.RuntimeMcpServer;
import io.github.teemuki8.libgdx.agent.runtime.protocol.PublishedRuntime;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeProtocolService;
import io.github.teemuki8.libgdx.agent.runtime.protocol.RuntimeRegistry;

/** Same-JVM deterministic fixture and stdio MCP host used by the documented agent setup. */
public final class McpFixtureApplication extends ApplicationAdapter {
    private final DeterministicSimulation simulation = new DeterministicSimulation();
    private final RuntimeRegistry registry = new RuntimeRegistry();
    private AgentRuntime runtime;
    private PublishedRuntime publication;
    private RuntimeMcpServer server;
    private int frame;

    /** Starts a hidden desktop fixture; stdin/stdout are reserved for MCP. */
    public static void main(String[] args) {
        if (args.length != 0) {
            throw new IllegalArgumentException("MCP fixture accepts no arguments");
        }
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("libGDX Agent Runtime MCP Fixture");
        configuration.setWindowedMode(320, 180);
        configuration.setInitialVisible(false);
        configuration.setResizable(false);
        configuration.useVsync(false);
        configuration.setForegroundFPS(60);
        configuration.disableAudio(true);
        new Lwjgl3Application(new McpFixtureApplication(), configuration);
    }

    @Override public void create() {
        runtime = simulation.startRuntime(Gdx.app::postRunnable);
        publication = registry.publish(runtime);
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
        if (frame < 45) {
            simulation.advance(runtime, ++frame);
        }
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
        if (runtime != null) {
            runtime.close();
        }
    }
}
