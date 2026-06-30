package com.vortox.sidecar.api;

import com.vortox.sidecar.service.AgentService;
import com.vortox.sidecar.skill.SkillDefinition;
import com.vortox.sidecar.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agentService;
    private final SkillRegistry skillRegistry;

    public AgentController(AgentService agentService, SkillRegistry skillRegistry) {
        this.agentService  = agentService;
        this.skillRegistry = skillRegistry;
    }

    // ── Skill upload / delete ─────────────────────────────────────────────────

    /** Upload or replace a skill. Body: {"name": "my_skill", "content": "<SKILL.md content>"} */
    @PostMapping("/skills/upload")
    public ResponseEntity<Map<String, Object>> uploadSkill(@RequestBody Map<String, String> body) {
        String name    = body.get("name");
        String content = body.get("content");
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
     * Returns: {reply: "..."}
     *
     * When allowPageScripts=true the system prompt instructs the LLM that it may include
     * a ```javascript block which the widget will execute in the host page context.
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody AgentChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        // ── System prompt ────────────────────────────────────────────────────
        StringBuilder systemPrompt = new StringBuilder();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            systemPrompt.append(request.systemPrompt());
        } else {
            systemPrompt.append("You are a helpful AI assistant embedded in a web application. ")
                        .append("Answer questions concisely and accurately. ")
                        .append("Use available skills when you need live data from the application.");
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

            if (request.pageApiDescription() != null && !request.pageApiDescription().isBlank()) {
                systemPrompt.append("\n\n## Host Page Structure\n").append(request.pageApiDescription());
            }
        }

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

        AgentRunRequest runRequest = new AgentRunRequest(
                task.toString(),
                null,
                systemPrompt.toString(),
                request.model(),
                30,
                null
        );

        AgentRunResponse response = agentService.run(runRequest);
        String reply = (response.result() != null && !response.result().isBlank())
                       ? response.result()
                       : "I couldn't generate a response. Please try again.";

        log.info("Chat completed — status={} inputTokens={} outputTokens={}",
                response.status(), response.inputTokens(), response.outputTokens());

        return ResponseEntity.ok(Map.of("reply", reply));
    }

    @GetMapping("/skills")
    public ResponseEntity<List<Map<String, Object>>> skills() {
        List<Map<String, Object>> list = skillRegistry.all().stream()
                .map(s -> Map.of(
                        "name", s.name(),
                        "description", (Object) s.description(),
                        "language", s.language(),
                        "timeoutSeconds", s.timeoutSeconds()
                ))
                .sorted((a, b) -> a.get("name").toString().compareTo(b.get("name").toString()))
                .toList();
        return ResponseEntity.ok(list);
    }

    @RequestMapping(value = "/skills/reload", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> reloadSkills() {
        skillRegistry.load();
        return ResponseEntity.ok(Map.of("loaded", skillRegistry.count()));
    }
}
