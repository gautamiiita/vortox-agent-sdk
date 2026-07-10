package com.vortox.agent.legacyproxy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
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
    void unsupportedMethodReturns405() throws Exception {
        ChatRelay relay = mock(ChatRelay.class);
        VortoxChatProxyController controller = new VortoxChatProxyController(relay, ChatContextEnricher.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/agentChat.htm");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handleRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(405);
    }
}
