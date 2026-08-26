package com.vortox.agent.legacyproxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VortoxChatProxyControllerTest {

    @Test
    void postStartsChatAndForwardsRelayResponseVerbatim() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        when(relay.start(anyString())).thenReturn(new RelayResult(202, "{\"runId\":\"abc\",\"status\":\"RUNNING\"}"));

        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agentChat.htm");
        request.setContent("{\"message\":\"hi\"}".getBytes("UTF-8"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getContentAsString()).contains("abc");
        verify(relay).start(anyString());
    }

    @Test
    void enricherRunsBeforeStartAndCanAddFields() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        when(relay.start(anyString())).thenReturn(new RelayResult(202, "{}"));

        ChatContextEnricher enricher = new ChatContextEnricher() {
            @Override
            public void enrich(ObjectNode payload, RelayRequestContext context) {
                payload.put("institutionCode", "MOSA");
                payload.put("authenticatedUser", context.getRemoteUser());
            }
        };
        VortoxChatProxyController controller = new VortoxChatProxyController(relay, enricher);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agentChat.htm");
        request.setContent("{\"message\":\"hi\"}".getBytes("UTF-8"));
        request.setRemoteUser("alice");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(relay).start(captor.capture());
        assertThat(captor.getValue()).contains("\"institutionCode\":\"MOSA\"").contains("\"authenticatedUser\":\"alice\"");
    }

    @Test
    void getWithRunIdPollsRelay() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        when(relay.poll(eq("run-1"))).thenReturn(new RelayResult(200, "{\"status\":\"DONE\",\"reply\":\"hello\"}"));

        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/agentChat.htm");
        request.setParameter("runId", "run-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("hello");
        verify(relay).poll("run-1");
        verify(relay, never()).stream(anyString(), any(OutputStream.class));
    }

    @Test
    void getWithStreamParamRelaysStream() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("event: done\ndata: {}\n\n".getBytes("UTF-8"));
            return null;
        }).when(relay).stream(eq("run-2"), any(OutputStream.class));

        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/agentChat.htm");
        request.setParameter("runId", "run-2");
        request.setParameter("stream", "true");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        assertThat(response.getContentType()).contains("text/event-stream");
        assertThat(response.getContentAsString()).contains("event: done");
        verify(relay, times(1)).stream(eq("run-2"), any(OutputStream.class));
        verify(relay, never()).poll(anyString());
    }

    @Test
    void getWithoutRunIdReturnsBadRequest() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/agentChat.htm");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(400);
        verify(relay, never()).poll(anyString());
    }

    @Test
    void relayIOExceptionOnStartBecomesBadGateway() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        when(relay.start(anyString())).thenThrow(new IOException("connection refused"));

        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agentChat.htm");
        request.setContent("{}".getBytes("UTF-8"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(502);
    }

    @Test
    void postWithEmptyBodyIsReportedAsEmptyNotMalformed() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agentChat.htm");
        request.setContent(new byte[0]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("Empty request body");
        verify(relay, never()).start(anyString());
    }

    @Test
    void postWithTruncatedJsonIsRejectedAsMalformed() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agentChat.htm");
        request.setContent("{\"message\":\"hi\",\"history\":[{\"role\":\"us".getBytes("UTF-8"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("Malformed request body");
        verify(relay, never()).start(anyString());
    }

    @Test
    void postWithNonObjectJsonIsRejectedRatherThanClassCast() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agentChat.htm");
        request.setContent("[\"not\",\"an\",\"object\"]".getBytes("UTF-8"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(400);
        verify(relay, never()).start(anyString());
    }

    @Test
    void htmlErrorPageFromSidecarBecomesAJsonErrorRatherThanBeingForwarded() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        // Spring Boot's whitelabel page — what the sidecar answers with when a request fails to bind
        // or an exception escapes, since its caller here states no media type preference.
        when(relay.start(anyString())).thenReturn(new RelayResult(500,
                "<html><body><h1>Whitelabel Error Page</h1></body></html>"));

        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agentChat.htm");
        request.setContent("{\"message\":\"hi\"}".getBytes("UTF-8"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getContentAsString()).doesNotContain("<html>");
        // The sidecar's own status survives in the text, so the failure is still diagnosable.
        assertThat(response.getContentAsString()).contains("500");
    }

    @Test
    void emptyBodyFromSidecarBecomesAJsonErrorRatherThanAnUnparseableResponse() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        when(relay.poll(anyString())).thenReturn(new RelayResult(204, ""));

        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/agentChat.htm");
        request.setParameter("runId", "abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getContentAsString()).contains("unreadable");
    }

    @Test
    void jsonErrorFromSidecarIsStillForwardedWithItsOwnStatus() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        // A JSON body is the sidecar speaking deliberately — a 401 from its API-key filter, say — and
        // rewriting that to a 502 would hide a fact the caller can act on.
        when(relay.start(anyString())).thenReturn(new RelayResult(401, "{\"error\":\"Missing X-Sidecar-Key\"}"));

        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agentChat.htm");
        request.setContent("{\"message\":\"hi\"}".getBytes("UTF-8"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Missing X-Sidecar-Key");
    }

    /**
     * The failure that produced this test in the field: Tomcat's decoding reader threw
     * {@code IllegalArgumentException: newPosition > limit (8174 > 9)} out of B2CConverter on any
     * body crossing its 8KB buffer, which is every message carrying a page snapshot.
     * MockHttpServletRequest cannot reproduce Tomcat's internals, so what is pinned here is the
     * property that makes the container's decoder irrelevant: a large multi-byte body arrives intact.
     */
    @Test
    void largeMultiByteBodyIsReadIntact() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        when(relay.start(anyString())).thenReturn(new RelayResult(202, "{\"runId\":\"abc\"}"));

        StringBuilder message = new StringBuilder();
        while (message.length() < 12000) {
            message.append("Le r\u00e8glement d'acc\u00e8s \u00e0 l'\u00e9tablissement \u2014 si\u00e8ges num\u00e9rot\u00e9s, 1 pi\u00e8ce d'identit\u00e9. ");
        }
        String sent = message.toString();

        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agentChat.htm");
        request.setContentType("application/json");
        request.setContent(new ObjectMapper().writeValueAsString(
                java.util.Collections.singletonMap("message", sent)).getBytes("UTF-8"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        ArgumentCaptor<String> relayed = ArgumentCaptor.forClass(String.class);
        verify(relay).start(relayed.capture());
        assertThat(new ObjectMapper().readTree(relayed.getValue()).get("message").asText())
                .isEqualTo(sent);
        assertThat(response.getStatus()).isEqualTo(202);
    }

    /** A Content-Type without a charset means UTF-8 for JSON \u2014 never the servlet default of
     *  ISO-8859-1, which a host-wide encoding filter can otherwise impose on this request. */
    @Test
    void bodyWithoutADeclaredCharsetIsReadAsUtf8NotTheServletDefault() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        when(relay.start(anyString())).thenReturn(new RelayResult(202, "{}"));

        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agentChat.htm");
        request.setContentType("application/json");
        request.setCharacterEncoding("ISO-8859-1");   // as a host-wide filter would set it
        request.setContent("{\"message\":\"r\u00e9serv\u00e9 \u2014 \u65e5\u672c\"}".getBytes("UTF-8"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        ArgumentCaptor<String> relayed = ArgumentCaptor.forClass(String.class);
        verify(relay).start(relayed.capture());
        assertThat(new ObjectMapper().readTree(relayed.getValue()).get("message").asText())
                .isEqualTo("r\u00e9serv\u00e9 \u2014 \u65e5\u672c");
    }

    /** A caller that does state a charset is taken at its word. */
    @Test
    void explicitlyDeclaredCharsetIsHonoured() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        when(relay.start(anyString())).thenReturn(new RelayResult(202, "{}"));

        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agentChat.htm");
        request.setContentType("application/json; charset=ISO-8859-1");
        request.setContent("{\"message\":\"r\u00e9serv\u00e9\"}".getBytes("ISO-8859-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        ArgumentCaptor<String> relayed = ArgumentCaptor.forClass(String.class);
        verify(relay).start(relayed.capture());
        assertThat(new ObjectMapper().readTree(relayed.getValue()).get("message").asText())
                .isEqualTo("r\u00e9serv\u00e9");
    }

    /** A body spread over several lines must survive as it was sent \u2014 the reader this replaced
     *  dropped every line terminator without putting one back. */
    @Test
    void multiLineBodyIsNotSilentlyAltered() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        when(relay.start(anyString())).thenReturn(new RelayResult(202, "{}"));

        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agentChat.htm");
        request.setContentType("application/json");
        request.setContent("{\n  \"message\" : \"line one\\nline two\"\n}".getBytes("UTF-8"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        ArgumentCaptor<String> relayed = ArgumentCaptor.forClass(String.class);
        verify(relay).start(relayed.capture());
        assertThat(new ObjectMapper().readTree(relayed.getValue()).get("message").asText())
                .isEqualTo("line one\nline two");
    }

    /**
     * Whatever goes wrong, this endpoint answers JSON. An exception let out of here is rendered by
     * the host application as HTML, and HTML is precisely what the widget cannot report on \u2014 it
     * degrades to "Unexpected response from agent." with the cause left in a server log.
     */
    @Test
    void unexpectedRuntimeFailureIsReportedAsJsonNotPropagated() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        ChatContextEnricher exploding = new ChatContextEnricher() {
            @Override
            public void enrich(ObjectNode payload, RelayRequestContext context) {
                throw new IllegalStateException("no session bound to this thread");
            }
        };
        VortoxChatProxyController controller = new VortoxChatProxyController(relay, exploding);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agentChat.htm");
        request.setContentType("application/json");
        request.setContent("{\"message\":\"hi\"}".getBytes("UTF-8"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString()).contains("unexpected server error");
        // The internal detail stays in the log, not in a body bound for a browser.
        assertThat(response.getContentAsString()).doesNotContain("no session bound");
        verify(relay, never()).start(anyString());
    }

    @Test
    void unsupportedMethodReturns405() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/agentChat.htm");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(405);
    }
}
