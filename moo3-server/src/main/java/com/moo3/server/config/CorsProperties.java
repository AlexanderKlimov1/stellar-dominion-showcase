package com.moo3.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Игра видна всем, кто видит IP сервера (п. 3.1), поэтому CORS настраивается снаружи. */
@ConfigurationProperties(prefix = "moo3.cors")
public record CorsProperties(
        String allowedOrigins
) {
}
