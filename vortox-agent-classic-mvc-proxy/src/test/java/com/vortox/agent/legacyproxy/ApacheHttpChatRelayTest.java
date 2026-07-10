package com.vortox.agent.legacyproxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises {@link ApacheHttpChatRelay} against a real (JDK-builtin) HTTP server standing in
 *  for the sidecar — no new test dependency needed, and it proves the actual wire behavior. */
class ApacheHttpChatRelayTest {

    private HttpServer server;
    private ApacheHttpChatRelay relay;

    @BeforeEach
    void startStubSidecar() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);

        server.createContext("/agent/chat", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] body = "{\"runId\":\"run-1\",\"status\":\"RUNNING\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, body.length);
                OutputStream os = exchange.getResponseBody();
                os.write(body);
                os.close();
            }
        });

        server.createContext("/agent/chat/run-1", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if ("/agent/chat/run-1/stream".equals(exchange.getRequestURI().getPath())) {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream;charset=UTF-8");
                    exchange.sendResponseHeaders(200, 0);
                    OutputStream os = exchange.getResponseBody();
                    os.write("event: iteration\ndata: {\"iteration\":1,\"maxIterations\":75}\n\n"
                            .getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.write("event: done\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.close();
                    return;
                }
                byte[] body = "{\"status\":\"DONE\",\"reply\":\"hello there\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                OutputStream os = exchange.getResponseBody();
                os.write(body);
                os.close();
            }
        });

        server.start();
        relay = new ApacheHttpChatRelay("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopStubSidecar() {
        server.stop(0);
    }

    @Test
    void startReturnsSidecarResponseVerbatim() throws IOException {
        RelayResult result = relay.start("{\"message\":\"hi\"}");

        assertThat(result.getStatusCode()).isEqualTo(202);
        assertThat(result.getBody()).contains("run-1").contains("RUNNING");
    }

    @Test
    void pollReturnsSidecarResponseVerbatim() throws IOException {
        RelayResult result = relay.poll("run-1");

        assertThat(result.getStatusCode()).isEqualTo(200);
        assertThat(result.getBody()).contains("hello there");
    }

    @Test
    void streamRelaysBytesInOrder() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        relay.stream("run-1", sink);

        String received = sink.toString("UTF-8");
        assertThat(received).contains("event: iteration");
        assertThat(received).contains("event: done");
        assertThat(received.indexOf("iteration")).isLessThan(received.indexOf("done"));
    }
}
