package com.vortox.sidecar.config;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure this guards against is not a wrong field in the body — it is a body that is not JSON
 * at all. The relay in front of this sidecar forwards what it receives, and the widget behind that
 * can only report "unexpected response" when it cannot parse it, so an HTML error page here erases
 * the reason for the failure everywhere downstream.
 */
class JsonErrorControllerTest {

    private final JsonErrorController controller = new JsonErrorController();

    @Test
    void reportsClientErrorsAsJsonWithTheirStatus() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/agent/chat");
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "Required request body is missing");

        ResponseEntity<Map<String, Object>> response = controller.error(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).containsEntry("status", 400);
        assertThat(response.getBody()).containsEntry("error", "Bad Request");
        assertThat(response.getBody()).containsEntry("path", "/agent/chat");
        assertThat(response.getBody()).containsEntry("message", "Required request body is missing");
    }

    @Test
    void withholdsInternalDetailFromServerErrors() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE,
                "java.lang.NullPointerException: Cannot invoke \"String.length()\"");

        ResponseEntity<Map<String, Object>> response = controller.error(request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsEntry("error", "Internal Server Error");
        assertThat(String.valueOf(response.getBody().get("message")))
                .doesNotContain("NullPointerException");
    }

    @Test
    void treatsAnUnusableStatusAsAServerError() {
        // An error dispatch with no status attribute at all, which the servlet spec permits.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");

        ResponseEntity<Map<String, Object>> response = controller.error(request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsEntry("status", 500);
    }

    @Test
    void omitsTheMessageWhenTheContainerSuppliedNone() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "   ");

        ResponseEntity<Map<String, Object>> response = controller.error(request);

        assertThat(response.getBody()).doesNotContainKey("message");
        assertThat(response.getBody()).containsEntry("error", "Not Found");
    }
}
