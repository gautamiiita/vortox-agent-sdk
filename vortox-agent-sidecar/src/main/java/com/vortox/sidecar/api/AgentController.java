package com.vortox.sidecar.api;

import com.vortox.sidecar.service.AgentService;
import com.vortox.sidecar.service.AgentStreamRegistry;
import com.vortox.sidecar.service.AnthropicKeyRefreshService;
import com.vortox.sidecar.service.ChatRunService;
import com.vortox.sidecar.skill.SkillDefinition;
import com.vortox.sidecar.skill.SkillRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agentService;
    private final SkillRegistry skillRegistry;
    private final ChatRunService chatRunService;
    private final AgentStreamRegistry streamRegistry;

    /** Optional: present only when vortox.backend.url is configured (i.e. this container is
     *  linked to a Vortox agent). Used by /agent/link-status to tell the skills UI whether
     *  uploading here would actually have any effect, or whether skill access is instead
     *  gated by the linked agent's own availableSkills configured in Vortox. */
    @Nullable
    @Autowired(required = false)
    private AnthropicKeyRefreshService anthropicKeyRefreshService;

    public AgentController(AgentService agentService, SkillRegistry skillRegistry,
                            ChatRunService chatRunService, AgentStreamRegistry streamRegistry) {
        this.agentService   = agentService;
        this.skillRegistry  = skillRegistry;
        this.chatRunService = chatRunService;
        this.streamRegistry = streamRegistry;
    }

    // ── Skill upload / delete ─────────────────────────────────────────────────

    /** Upload or replace a skill. Body: {"name": "my_skill", "content": "<SKILL.md content>"} */
    @PostMapping("/skills/upload")
    public ResponseEntity<Map<String, Object>> uploadSkill(@RequestBody Map<String, String> body) {
        String name    = body.get("name");
        String content = body.get("content");
        // Refused server-side, not merely hidden in the UI: the browser is not the only caller, and
        // a write that the next sync silently discards is worse than a clear refusal.
        if (name != null && skillRegistry.isVortoxManaged(null, name)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "'" + name + "' is managed by Vortox and would be overwritten on the "
                            + "next sync. Edit it in Vortox instead.",
                    "code", "SKILL_MANAGED_BY_VORTOX"));
        }
        try {
            SkillDefinition saved = skillRegistry.save(name, content);
            return ResponseEntity.ok(Map.of(
                "saved", true,
                "name", saved.name(),
                "language", saved.language(),
                "total", skillRegistry.count()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to save skill {}: {}", name, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** Delete a skill by name. */
    @DeleteMapping("/skills/{name}")
    public ResponseEntity<Map<String, Object>> deleteSkill(@PathVariable String name) {
        // Deleting a managed skill only removes it until the next sync restores it — a confusing
        // no-op rather than a deletion. Refuse and point at where it can actually be removed.
        if (skillRegistry.isVortoxManaged(null, name)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "'" + name + "' is managed by Vortox and the next sync would restore it. "
                            + "Delete it in Vortox instead.",
                    "code", "SKILL_MANAGED_BY_VORTOX"));
        }
        try {
            boolean deleted = skillRegistry.delete(name);
            if (!deleted) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(Map.of("deleted", true, "name", name, "total", skillRegistry.count()));
        } catch (Exception e) {
            log.error("Failed to delete skill {}: {}", name, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/run")
    public ResponseEntity<AgentRunResponse> run(@RequestBody AgentRunRequest request) {
        if (request.task() == null || request.task().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(agentService.run(request));
    }

    /**
     * Widget-facing chat endpoint. Accepts the format sent by vortox-agent-widget.js:
     * {message, history, context, allowPageScripts, pageApiDescription, systemPrompt, model}
     * <p>
     * Asynchronous: starts the agent run in the background and returns immediately with
     * {runId, status: "RUNNING"}. The caller polls {@link #chatStatus} for the result — a
     * single blocking HTTP request here would need to outlive the whole ReAct loop, which
     * can run many minutes for multi-step tasks or long-running skills.
     * <p>
     * When allowPageScripts=true the system prompt instructs the LLM that it may include
     * a ```javascript block which the widget will execute in the host page context.
     */
    /**
     * Teaches the model the closed set of page operations it may request, and the envelope to
     * request them in.
     *
     * <p>Deliberately unlike the {@code allowPageScripts} instructions above, which invite arbitrary
     * JavaScript that the widget then executes. Here the model emits a description of what it wants
     * done; the browser decides whether to do it, having validated every field. The model's output
     * is a request, never the action itself — which is what keeps an instruction smuggled in through
     * a database value from becoming code running in the operator's session.
     *
     * <p>The fenced block carries its own tag rather than {@code json}, so a JSON example the model
     * writes to explain something to the user cannot be mistaken for something to run.
     */
    /* package-private for testability */
    static void appendPageActionInstructions(StringBuilder systemPrompt,
                                             AgentChatRequest request) {
        java.util.List<String> actions = request.pageActions();
        if (actions == null || actions.isEmpty()) return;

        systemPrompt.append("\n\n## Page Actions\n")
                .append("You may ask the user's browser to change the page it is showing. ")
                .append("You cannot run code — you describe what you want, and the widget does it ")
                .append("after checking it is permitted.\n\n")
                .append("Available actions: ").append(String.join(", ", actions)).append("\n\n")
                .append("- highlight {target}          — draw attention to elements\n")
                .append("- scrollTo  {target}          — bring the first match into view\n")
                .append("- setClass  {target, add?, remove?} — add or remove a CSS class\n")
                .append("- setText   {target, text}    — replace text (never on a form field)\n")
                .append("- fill      {target, value}   — set an input, select or textarea\n\n")
                .append("To request them, end your reply with one block, exactly:\n")
                .append("```vortox-actions\n")
                .append("{\"actions\":[{\"name\":\"fill\",\"target\":\"#status\",\"value\":\"OPEN\"}]}\n")
                .append("```\n\n")
                .append("Rules:\n")
                .append("- `target` is a CSS selector taken from the page structure above. Never invent one.\n")
                .append("- At most one block per reply, and at most 20 actions in it.\n")
                .append("- Always say in plain text what you are changing, before the block.\n")
                .append("- `fill` needs the user's approval, so say what you are about to set and why.\n")
                .append("- If you are unsure which element is meant, ask instead of guessing — a wrong\n")
                .append("  selector silently changes the wrong part of the page.\n")
                .append("- Requesting anything not in the list above is refused and nothing runs.");

        if (request.lastActionResults() != null && !request.lastActionResults().isBlank()) {
            systemPrompt.append("\n\n### What happened to your last page actions\n")
                    .append(request.lastActionResults())
                    .append("\nTake this into account. If something failed, do not simply repeat it — ")
                    .append("either choose a different selector or tell the user what you could not do.");
        }
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody AgentChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        // ── Runtime instructions ─────────────────────────────────────────────
        // What the browser can do on this turn, kept apart from the agent's persona: AgentService
        // appends this after the prompt it resolves from Vortox. Building both in one buffer, as
        // this used to, made any turn carrying page actions look like a caller-supplied persona and
        // silently displaced the configured one.
        StringBuilder systemPrompt = new StringBuilder();

        // Knowing what is on the page is separate from being allowed to change it, so the structure
        // is included whenever the widget sent one — the read-only case being both the most useful
        // and the least dangerous ("what is missing on this form?" needs no write capability at all).
        //
        // It used to be appended only inside the allowPageScripts branch below, which broke page
        // actions in their intended configuration: the action instructions tell the model to take
        // every selector from "the page structure above" and never invent one, while the structure
        // itself was withheld unless script execution was also enabled. The model had no option left
        // but to guess, and a guessed selector silently changes the wrong part of the page.
        if (request.pageApiDescription() != null && !request.pageApiDescription().isBlank()) {
            systemPrompt.append("\n\n## Host Page Structure\n").append(request.pageApiDescription());
        }

        if (Boolean.TRUE.equals(request.allowPageScripts())) {
            systemPrompt.append("\n\n## Page Script Capability\n")
                        .append("You can update the user's page directly by including a ```javascript code block in your response.\n")
                        .append("The widget automatically executes it in the browser. Follow these rules:\n")
                        .append("- Include at most ONE ```javascript block per response.\n")
                        .append("- Prefer safe, reversible operations — add/remove CSS classes rather than direct style edits.\n")
                        .append("- Never use alert(), confirm(), or prompt() — they block the browser.\n")
                        .append("- Always explain what you are doing in plain text before the code block.\n")
                        .append("- If the right DOM selectors are unclear, ask the user instead of guessing.");
        }

        appendPageActionInstructions(systemPrompt, request);

        // ── Task: context + history + message ────────────────────────────────
        StringBuilder task = new StringBuilder();

        if (request.context() != null && !request.context().isEmpty()) {
            task.append("## Current Page Context\n");
            request.context().forEach((k, v) ->
                    task.append("- ").append(k).append(": ").append(v).append("\n"));
            task.append("\n");
        }

        if (request.history() != null && !request.history().isEmpty()) {
            task.append("## Conversation History\n");
            for (Map<String, Object> turn : request.history()) {
                String role    = Objects.toString(turn.getOrDefault("role", "user"));
                String content = Objects.toString(turn.getOrDefault("content", ""));
                task.append("user".equals(role) ? "User: " : "Assistant: ")
                    .append(content).append("\n");
            }
            task.append("\n");
        }

        task.append("## User Message\n").append(request.message());

        String apiKey = request.llmApiKey();  // explicit per-request key takes precedence
        String runtimeInstructions = systemPrompt.length() > 0 ? systemPrompt.toString() : null;
        AgentRunRequest runRequest = new AgentRunRequest(
                task.toString(),
                null,
                // The caller's own persona, offered but not guaranteed: AgentService refuses it for
                // runs linked to a Vortox agent, where the prompt is that agent's configuration and
                // not something a chat payload gets to replace.
                request.systemPrompt(),
                request.model(),
                75,
                apiKey,
                request.llmProvider(),
                request.llmBaseUrl(),
                // Resolved once, here at the boundary, then passed explicitly the whole way down.
                request.tenantCode(),
                request.surface(),
                runtimeInstructions
        );

        String runId = chatRunService.start(runRequest);
        log.info("Chat run {} started", runId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("status", "RUNNING");
        body.put("contextSummary", describeEffectiveContext(request, runtimeInstructions));
        return ResponseEntity.accepted().body(body);
    }

    /**
     * What this run was actually told, for the widget to show the user.
     *
     * <p>Reported from the server rather than assembled in the browser because the two do not
     * necessarily agree: a host's {@code ChatContextEnricher} adds the authenticated user and
     * institution that the page never sees, and strips or overrides fields the page did send. A
     * badge built from what the widget *hoped* to send would therefore be able to claim something
     * untrue, and an indicator that can lie about what left the page is worse than none at all.
     *
     * <p>Values are echoed as-is: everything here was already in the outgoing request, so this
     * discloses nothing new to the browser it came from.
     */
    /* package-private for testability */
    static List<Map<String, String>> describeEffectiveContext(AgentChatRequest request,
                                                              String runtimeInstructions) {
        List<Map<String, String>> summary = new java.util.ArrayList<>();

        if (request.context() != null) {
            request.context().forEach((k, v) -> {
                if (v == null || String.valueOf(v).isBlank()) return;
                summary.add(Map.of("key", k, "value", String.valueOf(v), "source", "context"));
            });
        }
        if (request.tenantCode() != null && !request.tenantCode().isBlank()) {
            // The field that decides which tenant's skills and secrets the run receives — worth
            // showing separately from the advisory copy that may also sit in `context`.
            summary.add(Map.of("key", "tenant", "value", request.tenantCode(), "source", "server"));
        }
        if (request.surface() != null && !request.surface().isBlank()) {
            summary.add(Map.of("key", "surface", "value", request.surface(), "source", "page"));
        }

        boolean sentPageStructure = runtimeInstructions != null
                && runtimeInstructions.contains("## Host Page Structure");
        summary.add(Map.of("key", "pageContext",
                "value", sentPageStructure ? "included" : "not sent",
                "source", "page"));

        if (request.pageActions() != null && !request.pageActions().isEmpty()) {
            summary.add(Map.of("key", "pageActions",
                    "value", String.join(", ", request.pageActions()), "source", "page"));
        }
        if (Boolean.TRUE.equals(request.allowPageScripts())) {
            summary.add(Map.of("key", "pageScripts", "value", "enabled", "source", "server"));
        }
        return summary;
    }

    /** Poll target for {@link #chat}. Returns {runId, status, reply?, artifacts?, error?}. */
    @GetMapping("/chat/{runId}")
    public ResponseEntity<Map<String, Object>> chatStatus(@PathVariable String runId) {
        return chatRunService.status(runId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("status", "NOT_FOUND", "error", "Unknown or expired run")));
    }

    /**
     * Live progress stream for {@link #chat} — emits {@code iteration}/{@code tool_call}/
     * {@code tool_result}/{@code error} events as the ReAct loop runs, then a signal-only
     * {@code done} event. Coexists with {@link #chatStatus}, which remains the source of truth
     * for the final {reply, artifacts} — the stream consumer is expected to make one follow-up
     * GET to that endpoint after {@code done} rather than have the final payload duplicated here.
     * <p>
     * Mirrors the Vortox backend's {@code PlannerChatController.connectStream} pattern: a 15s
     * poll loop with a heartbeat comment on idle ticks (keeps proxies/browsers from timing out
     * on idle), and a brief pause after the terminal event before closing so nginx can flush its
     * buffer before Tomcat sends the TCP FIN (avoids {@code ERR_INCOMPLETE_CHUNKED_ENCODING}).
     */
    @GetMapping(value = "/chat/{runId}/stream", produces = "text/event-stream;charset=UTF-8")
    public StreamingResponseBody chatStream(@PathVariable String runId, HttpServletResponse response) {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        if (!streamRegistry.exists(runId)) {
            return out -> {
                out.write(("event: error\ndata: {\"message\":\"Unknown or expired run\"}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            };
        }

        return out -> {
            final byte[] heartbeat = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
            try {
                while (true) {
                    AgentStreamRegistry.Evt evt;
                    try {
                        evt = streamRegistry.poll(runId, 15_000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    if (evt == null) {
                        if (!streamRegistry.exists(runId)) break;
                        out.write(heartbeat);
                        out.flush();
                        continue;
                    }
                    if (streamRegistry.isDone(evt)) {
                        out.write("event: done\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        try { Thread.sleep(150); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        break;
                    }
                    out.write(("event: " + evt.name() + "\ndata: " + evt.json() + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } finally {
                streamRegistry.remove(runId);
            }
        };
    }

    @GetMapping("/skills")
    public ResponseEntity<List<Map<String, Object>>> skills(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String tenantCode) {
        String tenant = tenantCode == null || tenantCode.isBlank() ? null : tenantCode;
        // Withheld skills are still listed — an operator needs to see that a file in the skills
        // directory is being ignored, rather than watch it silently disappear from the agent.
        java.util.Set<String> withheld = new java.util.LinkedHashSet<>(skillRegistry.unusableNames(tenant));
        List<Map<String, Object>> list = skillRegistry.allFor(tenant).stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", s.name());
                    m.put("description", s.description());
                    m.put("language", s.language());
                    m.put("timeoutSeconds", s.timeoutSeconds());
                    // Whether Vortox owns this definition. A managed skill is replaced on the next
                    // sync, so editing it here is discarded silently — the UI needs to say so rather
                    // than offer an edit that appears to work and then vanishes.
                    m.put("managedByVortox", skillRegistry.isVortoxManaged(tenant, s.name()));
                    m.put("ignored", false);
                    return m;
                })
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        for (String name : withheld) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("description", "Not delivered by Vortox — ignored while this sidecar is governed. "
                    + "Register it in Vortox, or remove the file.");
            m.put("language", "-");
            m.put("timeoutSeconds", 0);
            m.put("managedByVortox", false);
            m.put("ignored", true);
            list.add(m);
        }
        list.sort((a, b) -> a.get("name").toString().compareTo(b.get("name").toString()));
        return ResponseEntity.ok(list);
    }

    /**
     * Tells the bundled skills UI (see {@code static/index.html}) whether this container is
     * linked to a Vortox agent, and if so, that agent's {@code availableSkills} — the list that
     * actually gates which loaded skills the LLM gets to see (per {@code AgentService.run()}).
     * Skills uploaded here are always loaded into the registry regardless of this link, but a
     * linked agent's own configuration in Vortox — not this container — decides which of them
     * are actually reachable, so the UI uses this to show "available" vs "loaded, not granted"
     * and to switch itself to read-only when a link is present.
     */
    @GetMapping("/link-status")
    public ResponseEntity<Map<String, Object>> linkStatus(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String tenantCode) {
        // Per tenant: on a pooled sidecar two institutions can link different agents, so "is this
        // container linked" is only answerable once you say who is asking.
        Map<String, Object> agentConfig = anthropicKeyRefreshService != null
                ? anthropicKeyRefreshService.getAgentConfig(
                        tenantCode == null || tenantCode.isBlank() ? null : tenantCode)
                : null;

        // "Linked" means an actual agent, not merely that config arrived: Vortox also sends a
        // skills-only config to apply a tenant's allow-list when no agent is linked. Treating that as
        // linked would show the UI an agent named "unknown" and switch it to read-only for nothing.
        boolean linked = agentConfig != null && agentConfig.get("agentId") != null;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("linked", linked);
        if (tenantCode != null && !tenantCode.isBlank()) body.put("tenantCode", tenantCode);
        if (agentConfig != null) {
            body.put("agentId", agentConfig.get("agentId"));
            body.put("agentName", agentConfig.get("name"));
            Object availableSkills = agentConfig.get("availableSkills");
            body.put("availableSkills", availableSkills instanceof List ? availableSkills : List.of());
        }
        return ResponseEntity.ok(body);
    }

    /** Fetch a single skill's raw SKILL.md content, for editing in a form that re-uses /skills/upload to save. */
    @GetMapping("/skills/{name}")
    public ResponseEntity<Map<String, Object>> getSkill(@PathVariable String name) {
        return skillRegistry.find(name)
                .map(s -> ResponseEntity.ok(Map.<String, Object>of(
                        "name", s.name(),
                        "content", s.rawContent()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    @RequestMapping(value = "/skills/reload", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> reloadSkills() {
        skillRegistry.load();
        return ResponseEntity.ok(Map.of("loaded", skillRegistry.count()));
    }

    /**
     * Proxy to a local LLM's model-listing endpoint.
     * GET /agent/llm/models?baseUrl=https://ai.svc.elca.ch&apiKey=sk-...
     */
    @GetMapping("/llm/models")
    public ResponseEntity<String> listLlmModels(
            @RequestParam String baseUrl,
            @RequestParam String apiKey) {
        try {
            String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(root + "/api/models"))
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .headers("Authorization", "Bearer " + apiKey)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) {
                return ResponseEntity.status(res.statusCode()).body(res.body());
            }
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .body(res.body());
        } catch (Exception e) {
            log.error("Failed to list LLM models from {}: {}", baseUrl, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
}
