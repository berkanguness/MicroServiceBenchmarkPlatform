package com.benchmark.lab_result_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The P6 rows of the contract §8.1 table, file by file. The ITs run with a random management port (test profile), so
 * this is what pins {@code 8081} and the exact keys of {@code bench}/{@code bench-nomon}.
 */
class ManagementProfilesTest {

    @Test
    void defaultProfile() throws IOException {
        assertKeys(yaml("application.yaml"), Map.of(
                "management.server.port", "8081",
                "management.endpoints.web.exposure.include", "health,info,prometheus",
                "management.endpoint.health.probes.enabled", "true",
                "management.info.java.enabled", "true",
                "management.info.os.enabled", "true",
                "management.info.process.enabled", "true",
                "management.metrics.tags.application", "lab-result-service",
                "management.prometheus.metrics.export.enabled", "true"));
        PropertySource<?> defaults = yaml("application.yaml");
        assertThat(defaults.getProperty("server.port")).isEqualTo(8080);
        assertThat(defaults.getProperty("spring.grpc.server.port")).isEqualTo(9091);
        assertNoHistograms(defaults);
    }

    @Test
    void benchProfile() throws IOException {
        PropertySource<?> bench = yaml("application-bench.yaml");
        assertKeys(bench, Map.of(
                "server.tomcat.max-keep-alive-requests", "-1",
                "server.tomcat.keep-alive-timeout", "60s",
                "server.http2.enabled", "false",
                "server.compression.enabled", "false",
                "spring.graphql.schema.introspection.enabled", "false",
                "spring.graphql.graphiql.enabled", "false",
                "spring.grpc.server.reflection.enabled", "false",
                "management.prometheus.metrics.export.enabled", "true"));
        assertThat(bench.getProperty("management.server.port")).as("inherits 8081").isNull();
        assertThat(bench.getProperty("app.cors.allowed-origins")).as("CORS is off by @Profile, not by a value").isNull();
        assertNoHistograms(bench);
    }

    @Test
    void benchNomonOverlay() throws IOException {
        PropertySource<?> nomon = yaml("application-bench-nomon.yaml");
        assertKeys(nomon, Map.of(
                "management.observations.enable.all", "false",
                "management.metrics.enable.all", "false",
                "management.prometheus.metrics.export.enabled", "false",
                "spring.grpc.server.observation.enabled", "false"));
        assertThat(((org.springframework.core.env.EnumerablePropertySource<?>) nomon).getPropertyNames())
                .as("an overlay of exactly the four F14 keys").hasSize(4);
    }

    private static void assertKeys(PropertySource<?> source, Map<String, String> expected) {
        expected.forEach((key, value) ->
                assertThat(String.valueOf(source.getProperty(key))).as("%s in %s", key, source.getName()).isEqualTo(value));
    }

    private static void assertNoHistograms(PropertySource<?> source) {
        for (String name : ((org.springframework.core.env.EnumerablePropertySource<?>) source).getPropertyNames()) {
            assertThat(name).doesNotContain("percentiles-histogram").doesNotContain("percentiles");
        }
    }

    private static PropertySource<?> yaml(String resource) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
        assertThat(sources).hasSize(1);
        return sources.getFirst();
    }
}
