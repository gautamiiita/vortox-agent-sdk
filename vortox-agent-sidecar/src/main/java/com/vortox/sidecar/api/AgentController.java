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

    @PostMapping("/skills/reload")
    public ResponseEntity<Map<String, Object>> reloadSkills() {
        skillRegistry.load();
        return ResponseEntity.ok(Map.of("loaded", skillRegistry.count()));
    }
}
