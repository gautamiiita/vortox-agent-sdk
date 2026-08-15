package com.vortox.sidecar.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Disables browser caching for the widget JS so updates are picked up immediately
 * without requiring a hard refresh.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/vortox-agent-widget.js", "/demo.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore());
    }

    /**
     * Spring MVC's default async request timeout is 30s, which would silently kill the
     * {@code GET /agent/chat/{runId}/stream} SSE connection mid-run — ReAct loops routinely
     * take several minutes. Disabling it entirely mirrors the Vortox backend's AsyncConfig,
     * which does the same for its own SSE endpoints.
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(-1);
    }
}
