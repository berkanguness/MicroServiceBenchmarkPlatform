package com.benchmark.lab_result_service.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed binding of the two P2 keys (contract §8.1):
 * <ul>
 *   <li>{@code app.seed.enabled} — default {@code false}; {@code true} in the {@code seed} profile. It also gates the
 *       {@link DatasetSeeder} bean ({@code @ConditionalOnBooleanProperty}).</li>
 *   <li>{@code app.dataset.verify-mode} — {@code cheap} (default) / {@code full} / {@code off}, env
 *       {@code APP_DATASET_VERIFY_MODE}. An unknown value fails binding, so startup fails.</li>
 * </ul>
 * In YAML, {@code off} must be quoted ({@code "off"}): YAML 1.1 reads a bare {@code off} as boolean {@code false}.
 */
@ConfigurationProperties("app")
public record DatasetProperties(@DefaultValue Seed seed, @DefaultValue Dataset dataset) {

    public record Seed(@DefaultValue("false") boolean enabled) {}

    public record Dataset(@DefaultValue("cheap") VerifyMode verifyMode) {}

    /** §6.7 verification modes. */
    public enum VerifyMode { CHEAP, FULL, OFF }
}
