package com.vortox.agent.legacyproxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.Controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Drop-in, single-bean chat proxy for classic (pre-Boot) Spring MVC applications — register one
 * instance under whatever {@code *.htm}-style bean name your app's {@code BeanNameUrlHandlerMapping}
 * uses (matching how e.g. TNAM's existing {@code /agentChat.htm} convention works), pointed at a
 * running vortox-agent-sidecar.
 * <p>
 * Dispatches on method + query parameters rather than separate bean-per-action routes, since
 * {@code BeanNameUrlHandlerMapping} matches on path only (confirmed against
 * {@code tnam-web-servlet.xml} — query strings never distinguish bean routes, but reading
 * {@code request.getParameter(...)} inside a single controller is this codebase's own established
 * pattern):
 * <ul>
 *   <li>{@code POST}                              — start a new chat run, returns {@code {runId,status}}</li>
 *   <li>{@code GET  ?runId=X}                      — poll for the final {@code {reply,artifacts}}</li>
 *   <li>{@code GET  ?runId=X&stream=true}          — relay the sidecar's live SSE progress stream</li>
 * </ul>
 * Configure the page's {@code vortox-agent-widget.js} with matching
 * {@code buildPollUrl}/{@code buildStreamUrl} hooks pointed at this same URL with those query
 * parameters, since the widget's default path-append convention doesn't fit this routing scheme.
 */
public class VortoxChatProxyController implements Controller {

    private static final Logger log = LoggerFactory.getLogger(VortoxChatProxyController.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatRelay relay;
    private final ChatContextEnricher enricher;

    public VortoxChatProxyController(String sidecarUrl) {
        this(sidecarUrl, ChatContextEnricher.NOOP);
    }

    public VortoxChatProxyController(String sidecarUrl, ChatContextEnricher enricher) {
        this(new ApacheHttpChatRelay(sidecarUrl), enricher);
    }

    /** Package-visible for tests — inject a stub {@link ChatRelay} directly. */
    VortoxChatProxyController(ChatRelay relay, ChatContextEnricher enricher) {
        this.relay = relay;
        this.enricher = enricher != null ? enricher : ChatContextEnricher.NOOP;
    }

    @Override
    public ModelAndView handleRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String method = request.getMethod();
        if ("POST".equalsIgnoreCase(method)) {
            handleStart(request, response);
        } else if ("GET".equalsIgnoreCase(method)) {
            handleGet(request, response);
        } else {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        }
        return null;
    }

    private void handleStart(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String rawBody = readBody(request);
        ObjectNode payload;
        try {
            payload = (ObjectNode) OBJECT_MAPPER.readTree(rawBody);
        } catch (Exception e) {
            writeJson(response, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"Malformed request body\"}");
            return;
        }

        enricher.enrich(payload, contextFrom(request));

        try {
            RelayResult result = relay.start(OBJECT_MAPPER.writeValueAsString(payload));
            writeJson(response, result.getStatusCode(), result.getBody());
        } catch (IOException e) {
            log.error("Failed to reach agent sidecar to start chat", e);
            writeJson(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "{\"error\":\"The assistant is temporarily unavailable.\"}");
        }
    }

    private void handleGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String runId = request.getParameter("runId");
        if (runId == null || runId.trim().length() == 0) {
            writeJson(response, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"runId is required\"}");
            return;
        }

        boolean wantsStream = isTruthy(request.getParameter("stream"));
        if (wantsStream) {
            handleStream(runId, response);
        } else {
            handlePoll(runId, response);
        }
    }

    private void handlePoll(String runId, HttpServletResponse response) throws IOException {
        try {
            RelayResult result = relay.poll(runId);
            writeJson(response, result.getStatusCode(), result.getBody());
        } catch (IOException e) {
            log.error("Failed to reach agent sidecar to poll run {}", runId, e);
            writeJson(response, HttpServletResponse.SC_BAD_GATEWAY,
                    "{\"error\":\"The assistant is temporarily unavailable.\"}");
        }
    }

    private void handleStream(String runId, HttpServletResponse response) throws IOException {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        try {
            relay.stream(runId, response.getOutputStream());
        } catch (IOException e) {
            log.warn("Stream relay for run {} ended abnormally: {}", runId, e.getMessage());
            // Best-effort only — headers/partial body may already be committed to the client at
            // this point, so there's nothing safe left to write; the widget falls back to polling.
        }
    }

    private RelayRequestContext contextFrom(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<String, String>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name, request.getHeader(name));
            }
        }
        return new RelayRequestContext(headers, request.getRemoteUser(), request.getRemoteAddr());
    }

    private static String readBody(HttpServletRequest request) throws IOException {
        StringBuilder body = new StringBuilder();
        BufferedReader reader = request.getReader();
        String line;
        while ((line = reader.readLine()) != null) {
            body.append(line);
        }
        return body.toString();
    }

    private static void writeJson(HttpServletResponse response, int statusCode, String body) throws IOException {
        response.setStatus(statusCode);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }

    private static boolean isTruthy(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }
}
