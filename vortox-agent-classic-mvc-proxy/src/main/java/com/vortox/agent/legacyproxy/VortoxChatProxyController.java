package com.vortox.agent.legacyproxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.Controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
        this(sidecarUrl, null, enricher);
    }

    /**
     * @param apiKey the sidecar's shared key, sent as {@code X-Sidecar-Key}. The sidecar refuses
     *               every {@code /agent/**} call without it. It is read here, server-side, and
     *               never reaches the browser.
     */
    public VortoxChatProxyController(String sidecarUrl, String apiKey, ChatContextEnricher enricher) {
        this(new ApacheHttpChatRelay(sidecarUrl, apiKey), enricher);
    }

    /** Package-visible for tests — inject a stub {@link ChatRelay} directly. */
    VortoxChatProxyController(ChatRelay relay, ChatContextEnricher enricher) {
        this.relay = relay;
        this.enricher = enricher != null ? enricher : ChatContextEnricher.NOOP;
    }

    @Override
    public ModelAndView handleRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String method = request.getMethod();
        try {
            if ("POST".equalsIgnoreCase(method)) {
                handleStart(request, response);
            } else if ("GET".equalsIgnoreCase(method)) {
                handleGet(request, response);
            } else {
                response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
        } catch (RuntimeException e) {
            // The last line of defence for the widget's contract: this endpoint answers JSON, always.
            // An exception let through here is rendered by the host application as an HTML error page,
            // and HTML is the one thing the caller cannot report on — it becomes "Unexpected response
            // from agent." with the cause visible only in a server log nobody is looking at. That is
            // exactly how the Tomcat decoding fault in readBody stayed hidden. Rethrowing would be
            // more idiomatic and strictly worse here.
            log.error("Unhandled failure serving {} on the chat proxy", method, e);
            if (!response.isCommitted()) {
                writeJson(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "{\"error\":\"The assistant hit an unexpected server error.\"}");
            }
        }
        return null;
    }

    private void handleStart(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String rawBody = readBody(request);

        // An empty body is a different failure from bad JSON and needs saying so: it means the
        // request arrived without one — the stream was already consumed by an upstream filter, or
        // something between the browser and here dropped it — not that the widget sent junk.
        // Jackson makes the two look alike, since readTree("") yields a MissingNode that only
        // fails later, on the cast.
        if (rawBody.isEmpty()) {
            log.warn("Chat start rejected: empty request body (Content-Length header={}, Content-Type={})",
                    request.getHeader("Content-Length"), request.getContentType());
            writeJson(response, HttpServletResponse.SC_BAD_REQUEST,
                    "{\"error\":\"Empty request body\"}");
            return;
        }

        ObjectNode payload;
        try {
            com.fasterxml.jackson.databind.JsonNode parsed = OBJECT_MAPPER.readTree(rawBody);
            if (!(parsed instanceof ObjectNode)) {
                throw new IOException("expected a JSON object, got " + parsed.getNodeType());
            }
            payload = (ObjectNode) parsed;
        } catch (Exception e) {
            // Logged with the size and both ends of the body: a body that parses in the browser but
            // not here is almost always truncated in transit, which shows up as a plausible opening
            // and a tail that stops mid-token. Without this the failure left no trace at all.
            log.warn("Chat start rejected: unparseable request body ({} chars, Content-Length header={}): {} — starts '{}', ends '{}'",
                    rawBody.length(), request.getHeader("Content-Length"), e.getMessage(),
                    preview(rawBody, true), preview(rawBody, false));
            writeJson(response, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"Malformed request body\"}");
            return;
        }

        enricher.enrich(payload, contextFrom(request));

        try {
            RelayResult result = relay.start(OBJECT_MAPPER.writeValueAsString(payload));
            writeRelayResult(response, result, "chat start");
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
            writeRelayResult(response, result, "poll of run " + runId);
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

    /**
     * Forwards the sidecar's response — but only if it is actually JSON.
     *
     * <p>The sidecar is a Spring Boot application with the default error handling, so a binding
     * failure, an unmapped path or an unhandled exception there produces the whitelabel <em>HTML</em>
     * error page; so does any gateway that sits between the two. Forwarded verbatim under a
     * {@code application/json} content type, as this used to do, that HTML reached the widget as
     * "Unexpected response from agent." with the status code thrown away — unactionable at the only
     * place a human was reading it. Nothing downstream can recover what the sidecar meant, so it is
     * said here, once, where the status and the body are both still in hand.
     */
    private void writeRelayResult(HttpServletResponse response, RelayResult result, String what)
            throws IOException {
        String body = result.getBody();
        if (looksLikeJson(body)) {
            writeJson(response, result.getStatusCode(), body);
            return;
        }
        log.warn("Sidecar returned a non-JSON body for {} (HTTP {}, {} chars) — starts '{}'",
                what, result.getStatusCode(), body == null ? 0 : body.length(), preview(body, true));
        // 502 rather than the sidecar's own status: the failure being reported is that this hop got
        // something it could not use, which is not the same fact as whatever the sidecar was trying
        // to say. The original status stays in the message so it is still diagnosable.
        writeJson(response, HttpServletResponse.SC_BAD_GATEWAY,
                "{\"error\":\"The assistant returned an unreadable response (HTTP "
                        + result.getStatusCode() + ").\"}");
    }

    /**
     * Cheap shape check, not validation: the widget will parse the body itself and report its own
     * failure, so all this has to catch is the case that is worth a distinct server-side log line —
     * a body that was never JSON to begin with, such as an HTML error page or an empty response.
     */
    private static boolean looksLikeJson(String body) {
        if (body == null) return false;
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return false;
        char first = trimmed.charAt(0);
        return first == '{' || first == '[';
    }

    /** First or last 120 characters of the body, for a diagnostic log line — never echoed to the caller. */
    private static String preview(String body, boolean head) {
        if (body == null) return "";
        int limit = 120;
        if (body.length() <= limit) return head ? body : "";
        return head ? body.substring(0, limit) : body.substring(body.length() - limit);
    }

    /**
     * Reads the request body as bytes and decodes it once, here.
     *
     * <p>Deliberately not {@code request.getReader()}. Tomcat's decoding reader throws out of
     * {@code B2CConverter} on a body that crosses its internal 8KB buffer — which is every chat
     * message carrying a page snapshot, so on the screens where the assistant is most useful it was
     * close to every message:
     *
     * <pre>
     * java.lang.IllegalArgumentException: newPosition &gt; limit: (8174 &gt; 9)
     *   at org.apache.tomcat.util.buf.B2CConverter.convert(B2CConverter.java:300)
     *   at org.apache.catalina.connector.InputBuffer.realReadChars(InputBuffer.java:485)
     *   at org.apache.catalina.connector.CoyoteReader.readLine(CoyoteReader.java:156)
     *   at ...VortoxChatProxyController.readBody
     * </pre>
     *
     * <p>That is an {@code IllegalArgumentException}, so the {@code catch (IOException)} downstream
     * never saw it: it escaped the controller, the host application rendered its HTML error page,
     * and the widget — which can only parse JSON — reported "Unexpected response from agent." with
     * the real cause nowhere in sight. Reading bytes bypasses the character decoder entirely.
     *
     * <p>The reader was wrong on a second count too: {@code readLine()} strips line terminators and
     * they were never appended back, so any body that was not on a single line arrived silently
     * altered. Today's widget sends compact JSON and never noticed; the next client would.
     */
    private static String readBody(HttpServletRequest request) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        InputStream in = request.getInputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), charsetOf(request));
    }

    /**
     * UTF-8 unless this particular request explicitly said otherwise.
     *
     * <p>Read from the {@code Content-Type} header rather than {@code getCharacterEncoding()},
     * because that method also reports an application-wide default set by a servlet filter. A host
     * that defaults its requests to ISO-8859-1 — ordinary in an application of this vintage — would
     * otherwise mangle every accented character in a chat message, and the corruption would be
     * attributed to the model rather than to this hop. XMLHttpRequest encodes a string body as UTF-8
     * whatever content type it is given, and RFC 8259 requires JSON exchanged between systems to be
     * UTF-8, so absence of a charset parameter means UTF-8 here — not the servlet default.
     */
    private static Charset charsetOf(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) return StandardCharsets.UTF_8;

        for (String part : contentType.split(";")) {
            String token = part.trim();
            if (token.regionMatches(true, 0, "charset=", 0, 8)) {
                String name = token.substring(8).trim().replace("\"", "");
                try {
                    return Charset.forName(name);
                } catch (RuntimeException e) {
                    log.warn("Ignoring unusable charset '{}' on the request — reading the body as UTF-8", name);
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
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
