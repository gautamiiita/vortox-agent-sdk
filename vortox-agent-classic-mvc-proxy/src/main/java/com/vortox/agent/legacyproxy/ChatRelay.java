package com.vortox.agent.legacyproxy;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Server-to-server relay to a vortox-agent-sidecar's chat endpoints
 * ({@code POST /agent/chat}, {@code GET /agent/chat/{runId}}, {@code GET /agent/chat/{runId}/stream}).
 */
public interface ChatRelay {

    /** POSTs {@code payloadJson} to the sidecar's {@code /agent/chat} and returns its response verbatim. */
    RelayResult start(String payloadJson) throws IOException;

    /** GETs the sidecar's {@code /agent/chat/{runId}} poll endpoint and returns its response verbatim. */
    RelayResult poll(String runId) throws IOException;

    /**
     * Opens the sidecar's {@code /agent/chat/{runId}/stream} SSE endpoint and writes its bytes to
     * {@code sink} as they arrive, flushing after each chunk — true streaming, not buffered —
     * blocking the caller's thread for the stream's duration.
     */
    void stream(String runId, OutputStream sink) throws IOException;
}
