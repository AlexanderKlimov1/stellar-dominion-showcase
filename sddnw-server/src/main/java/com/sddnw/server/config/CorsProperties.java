package com.sddnw.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Игра видна всем, кто видит IP сервера (п. 3.1), поэтому CORS настраивается снаружи. */
@ConfigurationProperties(prefix = "sddnw.cors")
public record CorsProperties(
        String allowedOrigins
) {
}
