package com.vortox.sidecar.api;

import com.vortox.sidecar.skill.VortoxSkillSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Receives skill-reload pushes from the Vortox control plane.
 *
 * Vortox calls POST /vortox/skills/reload after any skill is approved, updated, or deleted.
 * This endpoint triggers an immediate re-pull of the approved skill set.
 * Only active when a VortoxGateway bean is present (i.e. vortox.backend.url is configured).
 */
@RestController
@RequestMapping("/vortox")
@ConditionalOnProperty(name = "vortox.backend.url")
public class VortoxSkillWebhookController {

    private static final Logger log = LoggerFactory.getLogger(VortoxSkillWebhookController.class);

    private final VortoxSkillSyncService syncService;

    public VortoxSkillWebhookController(VortoxSkillSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/skills/reload")
    public ResponseEntity<Map<String, String>> reload() {
        log.info("Received skill reload signal from Vortox");
        // Fire-and-forget: respond immediately, sync runs in background
        new Thread(syncService::syncOnWebhook, "skill-sync-webhook").start();
        return ResponseEntity.ok(Map.of("status", "reload triggered"));
    }
}
