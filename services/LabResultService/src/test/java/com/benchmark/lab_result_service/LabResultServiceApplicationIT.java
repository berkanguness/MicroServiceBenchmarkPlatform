package com.benchmark.lab_result_service;

import static org.assertj.core.api.Assertions.assertThat;

import com.benchmark.lab_result_service.persistence.repository.LabReadRepository;
import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.ClassUtils;

/** The application context starts against Testcontainers PostgreSQL 17 (replaces the generated contextLoads test). */
class LabResultServiceApplicationIT extends AbstractPostgresIT {

    @Autowired ApplicationContext context;
    @Autowired DataSource dataSource;
    @Autowired EntityManagerFactory emf;

    @Test
    void contextLoadsWithTheReadRepository() {
        assertThat(context.getBeanNamesForType(LabReadRepository.class)).hasSize(1);
    }

    @Test
    void applicationPropertiesIsGoneSoTheYamlIsAuthoritative() {
        // R10: a leftover application.properties would override application.yaml
        assertThat(new ClassPathResource("application.properties").exists()).isFalse();
        assertThat(new ClassPathResource("application.yaml").exists()).isTrue();
    }

    @Test
    void hikariPoolFollowsTheContract() {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getPoolName()).isEqualTo("lab-hikari");
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(20);
        assertThat(hikari.getMinimumIdle()).isEqualTo(20);
        assertThat(hikari.getConnectionTimeout()).isEqualTo(2_000);
        assertThat(hikari.getMaxLifetime()).isEqualTo(1_800_000);
        // F3: Hikari does not clamp validationTimeout to connectionTimeout; the default 5000 would make a hung
        // database cost about 5 s per acquisition instead of the contract's 2000 ms
        assertThat(hikari.getValidationTimeout()).isEqualTo(1_000).isLessThan(hikari.getConnectionTimeout());
    }

    /**
     * P4 fix round: springdoc was dropped (R4 is covered by the Postman collection), and with it Jackson 2
     * ({@code com.fasterxml.jackson.core:jackson-databind}, {@code jackson-dataformat-yaml}). JSON is written by
     * Boot 4's one Jackson 3 converter (the golden files pin its output); no Jackson 2 JSON or YAML converter is
     * registered, and no springdoc class is on the classpath.
     */
    @Test
    void jsonIsWrittenByTheJackson3ConverterOnly() {
        var adapter = context.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.class);
        var names = adapter.getMessageConverters().stream().map(c -> c.getClass().getName()).toList();
        assertThat(names).contains("org.springframework.http.converter.json.JacksonJsonHttpMessageConverter")
                .noneMatch(name -> name.contains("Jackson2"))
                .noneMatch(name -> name.startsWith("org.springframework.http.converter.yaml."));
        assertThat(ClassUtils.isPresent("com.fasterxml.jackson.databind.ObjectMapper", null)).as("Jackson 2 databind").isFalse();
        assertThat(ClassUtils.isPresent("org.springdoc.core.configuration.SpringDocConfiguration", null)).as("springdoc").isFalse();
    }

    @Test
    void hibernateSettingsFollowTheContract() {
        Map<String, Object> props = emf.getProperties();
        assertThat(props).containsEntry("hibernate.query.in_clause_parameter_padding", "true")
                .containsEntry("hibernate.jdbc.time_zone", "UTC")
                .containsEntry("hibernate.cache.use_second_level_cache", "false")
                .containsEntry("hibernate.cache.use_query_cache", "false")
                .containsEntry("hibernate.hbm2ddl.auto", "validate");
    }
}
