package com.dettavo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String[] ALLOWED_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"};

    // Request headers the client sends beyond the CORS-safe ones: revision checks on save, the upload's file name, video seeking.
    private static final String[] ALLOWED_HEADERS = {"Content-Type", "If-Match", "X-File-Name", "Range"};

    // Response headers the client must be able to read: the revision, partial content for seeking, and download names.
    private static final String[] EXPOSED_HEADERS = {"ETag", "Content-Range", "Accept-Ranges", "Content-Length", "Content-Disposition", "Location"};

    private static final long PREFLIGHT_CACHE_SECONDS = 3600;

    private final String[] allowedOrigins;

    public WebConfig(ApplicationProperties properties) {
        this.allowedOrigins = properties.security().allowedOrigins().toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods(ALLOWED_METHODS)
                .allowedHeaders(ALLOWED_HEADERS)
                .exposedHeaders(EXPOSED_HEADERS)
                .allowCredentials(true)
                .maxAge(PREFLIGHT_CACHE_SECONDS);
    }
}
