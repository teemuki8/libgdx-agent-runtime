package io.github.teemuki8.libgdx.agent.runtime.protocol;

import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;

/** Idempotent non-owning publication handle. */
public interface PublishedRuntime extends AutoCloseable {
    /** Published session identity. */
    SessionId sessionId();

    /** Removes the publication without closing the runtime. */
    @Override
    void close();
}
