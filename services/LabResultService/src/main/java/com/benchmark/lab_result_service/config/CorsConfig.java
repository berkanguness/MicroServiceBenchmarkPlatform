package com.benchmark.lab_result_service.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Browser access for the frontend (contract §8.1 R3): one CORS mapping for {@code /api/**} and {@code /graphql},
 * methods GET, POST, OPTIONS, origins from {@code app.cors.allowed-origins} (comma-separated).
 *
 * <p>Two independent conditions keep CORS out of benchmark runs: (1) this bean does not exist under {@code bench} or
 * {@code bench-nomon}, whatever the property says (an environment variable outranks profile YAML, P0-F3); (2) outside
 * bench a mapping is registered only when the origin list is non-empty. {@code spring.graphql.cors.*} is not used:
 * the global MVC mapping also reaches GraphQL's {@code RouterFunctionMapping}, so there is one mechanism.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!bench & !bench-nomon")
public class CorsConfig implements WebMvcConfigurer {

    static final String[] PATHS = {"/api/**", "/graphql"};
    static final String[] METHODS = {"GET", "POST", "OPTIONS"};

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(StringUtils.commaDelimitedListToStringArray(allowedOrigins))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    List<String> allowedOrigins() {
        return allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.isEmpty()) {
            return;                                        // empty = CORS off: no mapping, no CORS headers
        }
        String[] origins = allowedOrigins.toArray(String[]::new);
        for (String path : PATHS) {
            registry.addMapping(path).allowedOrigins(origins).allowedMethods(METHODS);
        }
    }
}
