package com.vortox.sidecar.api;

import com.vortox.sidecar.service.AgentService;
import com.vortox.sidecar.service.AgentStreamRegistry;
import com.vortox.sidecar.service.ChatRunService;
import com.vortox.sidecar.skill.SkillRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@code POST /agent/chat/{runId}/approval} — the widget's answer to a run waiting for a person. */
class AgentControllerApprovalTest {

    private final ChatRunService chat = mock(ChatRunService.class);
    private final AgentController controller = new AgentController(
            mock(AgentService.class), mock(SkillRegistry.class), chat, new AgentStreamRegistry());

    private static Map<String, Object> body(Object approved, String note, String key) {
        Map<String, Object> b = new HashMap<>();
        if (approved != null) b.put("approved", approved);
        if (note != null) b.put("note", note);
        if (key != null) b.put("llmApiKey", key);
        return b;
    }

    @Test
    @DisplayName("accepted: 202 and the run is RUNNING again")
    void accepted() {
        when(chat.decide("r1", true, "go", "k")).thenReturn(ChatRunService.Decision.ACCEPTED);
        ResponseEntity<Map<String, Object>> r = controller.chatApproval("r1", body(true, "go", "k"));
        assertThat(r.getStatusCode().value()).isEqualTo(202);
        assertThat(r.getBody()).containsEntry("status", "RUNNING");
        verify(chat).decide("r1", true, "go", "k");
    }

    @Test
    @DisplayName("unknown run: 404; not waiting: 409")
    void refusals() {
        when(chat.decide("gone", false, null, null)).thenReturn(ChatRunService.Decision.NOT_FOUND);
        when(chat.decide("busy", false, null, null)).thenReturn(ChatRunService.Decision.NOT_WAITING);
        assertThat(controller.chatApproval("gone", body(false, null, null)).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.chatApproval("busy", body(false, null, null)).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("`approved` must be a real boolean: a missing or stringly value decides nothing")
    void approvedMustBeBoolean() {
        assertThat(controller.chatApproval("r1", body(null, null, null)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.chatApproval("r1", body("true", null, null)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.chatApproval("r1", null).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(chat);
    }
}
