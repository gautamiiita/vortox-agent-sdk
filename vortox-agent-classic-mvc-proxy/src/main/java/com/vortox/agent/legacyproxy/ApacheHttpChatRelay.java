package com.vortox.agent.legacyproxy;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * {@link ChatRelay} backed by Apache HttpClient 4.x — matches what TNAM's existing hand-rolled
 * proxy controller already uses, since this module's runtime may predate {@code java.net.http}
 * (Java 11+).
 */
public final class ApacheHttpChatRelay implements ChatRelay {

    /** Generous ceiling for the stream socket — mirrors the widget's own MAX_WAIT_MS default. */
    private static final int STREAM_SOCKET_TIMEOUT_MS = 20 * 60 * 1000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = 30_000;

    private final String sidecarUrl;
    private final CloseableHttpClient client;

    public ApacheHttpChatRelay(String sidecarUrl) {
        this(sidecarUrl, HttpClients.createDefault());
    }

    /** Package-visible for tests — inject a client pointed at a stub server. */
    ApacheHttpChatRelay(String sidecarUrl, CloseableHttpClient client) {
        this.sidecarUrl = trimTrailingSlash(sidecarUrl);
        this.client = client;
    }

    @Override
    public RelayResult start(String payloadJson) throws IOException {
        HttpPost post = new HttpPost(sidecarUrl + "/agent/chat");
        post.setEntity(new StringEntity(payloadJson, ContentType.APPLICATION_JSON));
        post.setConfig(RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setSocketTimeout(DEFAULT_SOCKET_TIMEOUT_MS)
                .build());
        return execute(post);
    }

    @Override
    public RelayResult poll(String runId) throws IOException {
        HttpGet get = new HttpGet(sidecarUrl + "/agent/chat/" + runId);
        get.setConfig(RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setSocketTimeout(DEFAULT_SOCKET_TIMEOUT_MS)
                .build());
        return execute(get);
    }

    @Override
    public void stream(String runId, OutputStream sink) throws IOException {
        HttpGet get = new HttpGet(sidecarUrl + "/agent/chat/" + runId + "/stream");
        get.setConfig(RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setSocketTimeout(STREAM_SOCKET_TIMEOUT_MS)
                .build());

        CloseableHttpResponse response = client.execute(get);
        try {
            HttpEntity entity = response.getEntity();
            if (entity == null) return;
            InputStream in = entity.getContent();
            try {
                byte[] buffer = new byte[512];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    sink.write(buffer, 0, read);
                    sink.flush();
                }
            } finally {
                in.close();
            }
        } finally {
            response.close();
        }
    }

    private RelayResult execute(HttpUriRequest request) throws IOException {
        CloseableHttpResponse response = client.execute(request);
        try {
            HttpEntity entity = response.getEntity();
            String body = entity != null ? EntityUtils.toString(entity, "UTF-8") : "{}";
            int status = response.getStatusLine().getStatusCode();
            return new RelayResult(status, body);
        } finally {
            response.close();
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
