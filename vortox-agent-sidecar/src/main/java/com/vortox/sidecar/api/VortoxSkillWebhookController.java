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

    /**
     * One worker, one queued reload.
     *
     * <p>This was a bare {@code new Thread(...)} per request. Every sync serialises on a single lock
     * inside {@link VortoxSkillSyncService}, so N calls parked N threads waiting their turn to do
     * work that had already been done — and while this route was unauthenticated, that was the whole
     * denial-of-service. A single worker with a one-slot queue is also the honest model of the job:
     * reloads are idempotent, so a reload already waiting to run covers any that arrive behind it,
     * and dropping those is correct rather than lossy.
     */
    private final java.util.concurrent.ThreadPoolExecutor reloadExecutor =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(1),
                    r -> {
                        Thread t = new Thread(r, "skill-sync-webhook");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());

    public VortoxSkillWebhookController(VortoxSkillSyncService syncService) {
        this.syncService = syncService;
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        reloadExecutor.shutdownNow();
    }

    /**
     * Reload signal. An optional {@code tenantCode} in the body scopes the reload to one tenant's
     * private skills — the usual case, since a skill change belongs to whoever owns it. Omitting it
     * reloads the shared tier and then every tenant tier already in play, which is what a change to
     * a shared skill needs.
     */
    @PostMapping("/skills/reload")
    public ResponseEntity<Map<String, String>> reload(
            @org.springframework.web.bind.annotation.RequestBody(required = false)
            Map<String, Object> body) {
        String tenantCode = body != null && body.get("tenantCode") instanceof String s && !s.isBlank()
                ? s : null;
        log.info("Received skill reload signal from Vortox (tenant={})", tenantCode);
        // Fire-and-forget: respond immediately, sync runs on the single reload worker. A reload
        // dropped because one is already queued is not a lost update — the queued one will pull the
        // same current state, and the five-minute fallback poll covers anything stranger than that.
        reloadExecutor.execute(() -> syncService.syncOnWebhook(tenantCode));
        return ResponseEntity.ok(Map.of("status", "reload triggered",
                "scope", tenantCode == null ? "shared+known-tenants" : tenantCode));
    }
}
