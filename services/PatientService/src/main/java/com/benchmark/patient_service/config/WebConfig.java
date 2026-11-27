package com.benchmark.patient_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

public class WebConfig implements WebMvcConfigurer
{
    private final String[] origins;

    public WebConfig(@Value("${app.cors.allowed-origins}") String origins) {
        this.origins = origins.split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origins).allowedMethods("GET, OPTIONS");
    }
}
