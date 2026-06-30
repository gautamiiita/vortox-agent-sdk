package com.vortox.agent.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client that bridges embedded SDK instances to the Vortox control plane.
 *
 * Calls /api/sdk/v1/ on the Vortox backend for skill retrieval, run tracking,
 * notifications, memory persistence, and approval gates.
 */
public class VortoxGateway {

    private static final Logger log = LoggerFactory.getLogger(VortoxGateway.class);
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    private final String baseUrl;
    private final String apiKey;
    private final String instanceId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** Anthropic API key delivered by the Vortox backend at registration time. */
    private volatile String registrationAnthropicKey = null;

    public VortoxGateway(String baseUrl, String apiKey, String instanceId) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey != null ? apiKey : "";
        this.instanceId = instanceId;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public String getBaseUrl()    { return baseUrl; }
    public String getInstanceId() { return instanceId; }

    /** Returns the Anthropic API key provided by Vortox at registration, or null if not set. */
    public String getRegistrationAnthropicKey() { return registrationAnthropicKey; }

    // ── Skills ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchSkills() {
        try {
            String json = get("/api/sdk/v1/skills");
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("Failed to fetch skills from Vortox: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Instance lifecycle ────────────────────────────────────────────────────

    public void registerInstance(String applicationName, String version) {
        registerInstance(applicationName, version, null);
    }

    @SuppressWarnings("unchecked")
    public void registerInstance(String applicationName, String version, String callbackUrl) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("instanceId", instanceId);
            body.put("applicationName", applicationName != null ? applicationName : "unknown");
            body.put("version", version != null ? version : "0.0.0");
            if (callbackUrl != null && !callbackUrl.isBlank()) {
                body.put("callbackUrl", callbackUrl);
            }
            String responseJson = post("/api/sdk/v1/instances/register", body);
            // Cache the Anthropic key if the backend delivered one at registration time.
            // This avoids a separate /config round-trip on sidecar startup.
            try {
                Map<String, Object> response = objectMapper.readValue(responseJson, Map.class);
                String anthropicKey = (String) response.get("anthropicApiKey");
                if (anthropicKey != null && !anthropicKey.isBlank()) {
                    registrationAnthropicKey = anthropicKey;
                    log.info("Anthropic API key received from Vortox at registration (prefix: {}...)",
                            anthropicKey.substring(0, Math.min(12, anthropicKey.length())));
                }
            } catch (Exception parseEx) {
                log.debug("Could not parse registration response: {}", parseEx.getMessage());
            }
            log.info("Registered SDK instance '{}' with Vortox at {} (callbackUrl={})",
                    instanceId, baseUrl, callbackUrl != null ? callbackUrl : "none");
        } catch (Exception e) {
            log.warn("Failed to register SDK instance with Vortox: {}", e.getMessage());
        }
    }

    public void deregisterInstance() {
        try {
            delete("/api/sdk/v1/instances/" + instanceId);
            log.info("Deregistered SDK instance '{}' from Vortox", instanceId);
        } catch (Exception e) {
            log.warn("Failed to deregister SDK instance: {}", e.getMessage());
        }
    }

    // ── Run tracking ──────────────────────────────────────────────────────────

    public void createRun(String runId, String agentId, String task, String model) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("runId", runId);
            body.put("agentId", agentId != null ? agentId : "sdk-agent");
            body.put("instanceId", instanceId);
            body.put("task", task != null ? task : "");
            body.put("model", model != null ? model : "");
            post("/api/sdk/v1/runs", body);
        } catch (Exception e) {
            log.debug("Run tracking unavailable: {}", e.getMessage());
        }
    }

    public void updateRun(String runId, String status, String result, int inputTokens, int outputTokens) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("status", status);
            if (result != null) body.put("result", result);
            body.put("inputTokens", inputTokens);
            body.put("outputTokens", outputTokens);
            put("/api/sdk/v1/runs/" + runId, body);
        } catch (Exception e) {
            log.debug("Run update unavailable: {}", e.getMessage());
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    public String sendNotification(String channel, String target, String subject, String message) {
        try {
            Map<String, Object> body = Map.of(
                    "channel", channel != null ? channel : "default",
                    "target", target != null ? target : "",
                    "subject", subject != null ? subject : "Notification",
                    "message", message != null ? message : ""
            );
            String response = post("/api/sdk/v1/notifications", body);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(response, Map.class);
            return (String) parsed.getOrDefault("result", "sent");
        } catch (Exception e) {
            log.warn("Failed to send notification via Vortox: {}", e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    // ── Skill proposal ────────────────────────────────────────────────────────

    public String proposeSkill(String agentId, Map<String, Object> skillDef) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("agentId", agentId != null ? agentId : "sdk-agent");
            body.put("skill", skillDef);
            String response = post("/api/sdk/v1/skills/propose", body);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(response, Map.class);
            return (String) parsed.getOrDefault("result", "proposed");
        } catch (Exception e) {
            log.warn("Failed to propose skill via Vortox: {}", e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    // ── Memory ────────────────────────────────────────────────────────────────

    public void storeMemory(String agentId, String key, String content, String type, int importance) {
        try {
            Map<String, Object> body = Map.of(
                    "agentId", agentId,
                    "key", key,
                    "content", content != null ? content : "",
                    "type", type != null ? type : "CONTEXT",
                    "importance", importance
            );
            post("/api/sdk/v1/memory/store", body);
        } catch (Exception e) {
            log.debug("Memory store unavailable: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> recallMemory(String agentId, String query) {
        try {
            String url = "/api/sdk/v1/memory/recall?agentId=" + URLEncoder.encode(agentId, StandardCharsets.UTF_8);
            if (query != null && !query.isBlank()) {
                url += "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            }
            String json = get(url);
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.debug("Memory recall unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Approvals ─────────────────────────────────────────────────────────────

    public String createApproval(String agentId, String runId, String question, String context) {
        try {
            Map<String, Object> body = Map.of(
                    "instanceId", instanceId,
                    "agentId", agentId != null ? agentId : "",
                    "runId", runId != null ? runId : "",
                    "question", question != null ? question : "",
                    "context", context != null ? context : ""
            );
            String response = post("/api/sdk/v1/approvals", body);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(response, Map.class);
            return (String) parsed.get("approvalId");
        } catch (Exception e) {
            log.warn("Failed to create approval request: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> pollApproval(String approvalId) {
        try {
            String json = get("/api/sdk/v1/approvals/" + approvalId);
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.debug("Approval poll unavailable: {}", e.getMessage());
            return Map.of("status", "PENDING");
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private String get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("X-Vortox-Api-Key", apiKey)
                .header("X-Sdk-Instance-Id", instanceId)
                .GET()
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " from " + path + ": " + resp.body());
        }
        return resp.body();
    }

    private String post(String path, Object body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("X-Vortox-Api-Key", apiKey)
                .header("X-Sdk-Instance-Id", instanceId)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " from " + path + ": " + resp.body());
        }
        return resp.body();
    }

    private void put(String path, Object body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("X-Vortox-Api-Key", apiKey)
                .header("X-Sdk-Instance-Id", instanceId)
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " from " + path + ": " + resp.body());
        }
    }

    private void delete(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("X-Vortox-Api-Key", apiKey)
                .header("X-Sdk-Instance-Id", instanceId)
                .DELETE()
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " from " + path + ": " + resp.body());
        }
    }
}
