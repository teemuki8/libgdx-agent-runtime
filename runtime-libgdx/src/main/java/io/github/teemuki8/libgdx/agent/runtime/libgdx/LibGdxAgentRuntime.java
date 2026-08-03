package io.github.teemuki8.libgdx.agent.runtime.libgdx;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.ApplicationCommandDispatcher;
import io.github.teemuki8.libgdx.agent.runtime.core.CommandDispatchLimits;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeConfiguration;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import java.util.Objects;

/** Convenience builder that preserves application ownership of the libGDX lifecycle. */
public final class LibGdxAgentRuntime {
    private LibGdxAgentRuntime() {}

    /** Creates an adapter builder owned by the calling render thread. */
    public static Builder builder() {
        return new Builder();
    }

    /** Small adapter builder; the returned object is the transport-neutral core runtime. */
    public static final class Builder {
        private RuntimeConfiguration configuration = RuntimeConfiguration.developmentDefaults();
        private Thread captureThread = Thread.currentThread();
        private SessionId sessionId;
        private ApplicationCommandDispatcher commandDispatcher;
        private CommandDispatchLimits commandDispatchLimits =
                CommandDispatchLimits.developmentDefaults();

        private Builder() {}

        /** Sets capture limits and enablement. */
        public Builder configuration(RuntimeConfiguration value) {
            configuration = Objects.requireNonNull(value, "configuration");
            return this;
        }

        /** Sets the render/capture thread explicitly. */
        public Builder captureThread(Thread value) {
            captureThread = Objects.requireNonNull(value, "captureThread");
            return this;
        }

        /** Sets a stable session identity. */
        public Builder sessionId(SessionId value) {
            sessionId = Objects.requireNonNull(value, "sessionId");
            return this;
        }

        /** Registers the application's existing render-thread dispatch bridge. */
        public Builder commandDispatcher(ApplicationCommandDispatcher value) {
            commandDispatcher = Objects.requireNonNull(value, "commandDispatcher");
            return this;
        }

        /** Sets hard command queue and retention bounds. */
        public Builder commandDispatchLimits(CommandDispatchLimits value) {
            commandDispatchLimits = Objects.requireNonNull(value, "commandDispatchLimits");
            return this;
        }

        /** Builds an unstarted core runtime. */
        public AgentRuntime build() {
            AgentRuntime.Builder builder = AgentRuntime.builder()
                    .configuration(configuration)
                    .captureThread(captureThread)
                    .clock(LibGdxTime.monotonicClock());
            if (sessionId != null) {
                builder.sessionId(sessionId);
            }
            if (commandDispatcher != null) {
                builder.commandDispatcher(commandDispatcher)
                        .commandDispatchLimits(commandDispatchLimits);
            }
            return builder.build();
        }
    }
}
