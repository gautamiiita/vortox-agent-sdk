package com.vortox.sidecar.api;

import com.vortox.sidecar.service.AgentService;
import com.vortox.sidecar.skill.SkillDefinition;
import com.vortox.sidecar.skill.SkillRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/agent")
public class AgentController {

    private final AgentService agentService;
    private final SkillRegistry skillRegistry;

    public AgentController(AgentService agentService, SkillRegistry skillRegistry) {
        this.agentService  = agentService;
        this.skillRegistry = skillRegistry;
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
