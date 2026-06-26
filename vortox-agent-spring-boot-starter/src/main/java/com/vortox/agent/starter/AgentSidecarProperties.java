package com.vortox.agent.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.sidecar")
public class AgentSidecarProperties {

    /** Base URL of the vortox-agent-sidecar. Example: http://pkp-agent:7860 */
    private String url;

    /** Default Claude model used when the request doesn't specify one. */
    private String defaultModel = "claude-sonnet-4-6";

    /** Default maximum ReAct iterations. The sidecar stops after this many loops. */
    private int maxIterations = 10;

    /** TCP connect timeout in milliseconds. */
    private long connectTimeoutMs = 5_000;

    /** HTTP read timeout in milliseconds. Keep generous — LLM calls can take 30–60 s. */
    private long readTimeoutMs = 120_000;

    public String getUrl()                { return url; }
    public void   setUrl(String url)      { this.url = url; }

    public String getDefaultModel()                    { return defaultModel; }
    public void   setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public int  getMaxIterations()               { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }

    public long getConnectTimeoutMs()                      { return connectTimeoutMs; }
    public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public long getReadTimeoutMs()                   { return readTimeoutMs; }
    public void setReadTimeoutMs(long readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
}
