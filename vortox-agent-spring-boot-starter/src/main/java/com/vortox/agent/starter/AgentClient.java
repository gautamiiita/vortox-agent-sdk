package com.vortox.agent.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for the vortox-agent-sidecar.
 *
 * <p>Auto-configured as a Spring bean when {@code agent.sidecar.url} is set.
 * Inject it directly:
 *
 * <pre>{@code
 * @Autowired AgentClient agentClient;
 *
 * // Simple call — returns the result string or throws AgentException
 * String analysis = agentClient.ask(
 *     "Find failed badge reads in the last hour",
 *     "query_pkp_database"
 * );
 *
 * // Full call — inspect status, toolCalls, token usage
 * AgentResponse resp = agentClient.run(
 *     AgentRequest.builder()
 *         .task("...")
 *         .skill("query_pkp_database", "call_pkp_api")
 *         .maxIterations(15)
 *         .build()
 * );
 * }</pre>
 */
public final class AgentClient {

    private static final Logger log = LoggerFactory.getLogger(AgentClient.class);

    private final AgentSidecarProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper;

    AgentClient(AgentSidecarProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Run a task synchronously and return just the result text.
     *
     * @throws AgentException if the sidecar is unreachable or the run does not succeed
     */
    public String ask(String task, String... skills) {
        AgentResponse resp = run(AgentRequest.builder().task(task).skill(skills).build());
        if (!resp.isSuccess()) {
            throw new AgentException("Agent run did not succeed — status: " + resp.getStatus() +
                    ", result: " + resp.getResult());
        }
        return resp.getResult();
    }

    /**
     * Run a task synchronously and return the full response.
     *
     * @throws AgentException on network error or HTTP error
     */
    public AgentResponse run(AgentRequest request) {
        String url = props.getUrl() + "/agent/run";
        try {
            String body = mapper.writeValueAsString(buildBody(request));
            log.debug("POST {} — task: {}", url, request.getTask());

            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(props.getReadTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> httpResp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());

            if (httpResp.statusCode() != 200) {
                throw new AgentException("Sidecar returned HTTP " + httpResp.statusCode() +
                        " for POST " + url + " — " + httpResp.body());
            }

            AgentResponse resp = parseResponse(httpResp.body());
            log.debug("Agent run {} — status: {}, tokens in/out: {}/{}",
                    resp.getRunId(), resp.getStatus(), resp.getInputTokens(), resp.getOutputTokens());
            return resp;

        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException("Failed to reach agent sidecar at " + url, e);
        }
    }

    /** Async variant — delegates to a virtual thread (Java 21+) or ForkJoinPool otherwise. */
    public CompletableFuture<AgentResponse> runAsync(AgentRequest request) {
        return CompletableFuture.supplyAsync(() -> run(request));
    }

    public CompletableFuture<String> askAsync(String task, String... skills) {
        return CompletableFuture.supplyAsync(() -> ask(task, skills));
    }

    // ── private ──────────────────────────────────────────────────────────────

    private Map<String, Object> buildBody(AgentRequest req) {
        Map<String, Object> body = new HashMap<>();
        body.put("task",          req.getTask());
        body.put("model",         req.getModel() != null ? req.getModel() : props.getDefaultModel());
        body.put("maxIterations", req.getMaxIterations() != null ? req.getMaxIterations() : props.getMaxIterations());
        if (!req.getSkills().isEmpty())  body.put("skills",       req.getSkills());
        if (req.getSystemPrompt() != null) body.put("systemPrompt", req.getSystemPrompt());
        return body;
    }

    @SuppressWarnings("unchecked")
    private AgentResponse parseResponse(String json) throws Exception {
        Map<String, Object> m = mapper.readValue(json, Map.class);

        String runId  = (String) m.get("runId");
        String status = (String) m.get("status");
        String result = (String) m.get("result");
        int inputTokens  = toInt(m.get("inputTokens"));
        int outputTokens = toInt(m.get("outputTokens"));

        List<AgentResponse.ToolCall> toolCalls = List.of();
        if (m.get("toolCalls") instanceof List<?> raw) {
            toolCalls = raw.stream()
                    .filter(e -> e instanceof Map)
                    .map(e -> (Map<String, Object>) e)
                    .map(tc -> new AgentResponse.ToolCall(
                            (String) tc.get("toolName"),
                            Boolean.TRUE.equals(tc.get("success")),
                            toLong(tc.get("durationMs"))
                    ))
                    .toList();
        }

        return new AgentResponse(runId, status, result, toolCalls, inputTokens, outputTokens);
    }

    private static int  toInt(Object v)  { return v instanceof Number n ? n.intValue()  : 0; }
    private static long toLong(Object v) { return v instanceof Number n ? n.longValue() : 0L; }
}
