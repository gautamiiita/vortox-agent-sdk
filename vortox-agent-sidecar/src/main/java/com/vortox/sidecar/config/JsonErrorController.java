package com.vortox.sidecar.config;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Answers every error with JSON, replacing Spring Boot's whitelabel HTML page.
 *
 * <p>Nothing reaches this sidecar from a browser directly: its callers are the host application's
 * chat relay and the widget behind it, and both parse the body as JSON before they can say anything
 * useful about it. Boot's default picks HTML whenever the caller expresses no preference — which
 * Apache HttpClient, the relay's client, does not by default — so a malformed request body, an
 * unmapped path or an unhandled exception came back as a whitelabel page. That page then travelled
 * the whole way to the operator's screen as "Unexpected response from agent.", with the status code
 * and the reason both lost in transit. Producing JSON here means the failure survives every hop with
 * its status attached, whatever the caller asked for.
 *
 * <p>Declaring this bean is what switches Boot's {@code BasicErrorController} off: its
 * auto-configuration is conditional on no {@link ErrorController} existing.
 */
@RestController
public class JsonErrorController implements ErrorController {

    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
        int status = statusOf(request);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", reasonFor(status));

        Object path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (path != null) {
            body.put("path", String.valueOf(path));
        }

        String message = messageFor(request, status);
        if (message != null) {
            body.put("message", message);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static int statusOf(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (status instanceof Integer) {
            int code = (Integer) status;
            // Anything the container cannot explain is a server error; reporting 200 for an error
            // dispatch would be worse than guessing, since the caller keys its retry on the status.
            return code >= 100 && code < 600 ? code : 500;
        }
        return 500;
    }

    private static String reasonFor(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved != null ? resolved.getReasonPhrase() : "Error";
    }

    /**
     * The container's own explanation, forwarded for client errors only.
     *
     * <p>A 4xx describes something about the request that the caller can act on — which field failed
     * to bind, which path was not found. A 5xx message describes this application's internals, and
     * the caller can do nothing with them except relay them onward to a browser, so those get a
     * fixed sentence and the detail stays in the log.
     */
    private static String messageFor(HttpServletRequest request, int status) {
        if (status >= 500) {
            return "The agent sidecar failed to handle this request.";
        }
        Object message = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        if (message == null) {
            return null;
        }
        String text = String.valueOf(message).trim();
        return text.isEmpty() ? null : text;
    }
}
