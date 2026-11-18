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

    /** P4 (R4): springdoc is on the classpath but inert unless the "docs" profile is active (contract §8.1). */
    @Test
    void springdocIsOffOutsideTheDocsProfile() {
        assertThat(context.getEnvironment().getActiveProfiles()).doesNotContain("docs");
        assertThat(context.getBeanNamesForType(org.springdoc.webmvc.api.OpenApiWebMvcResource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(org.springdoc.webmvc.ui.SwaggerWelcomeWebMvc.class)).isEmpty();
    }

    /**
     * P4: springdoc brings Jackson 2 (jackson-dataformat-yaml) onto the classpath, so Spring MVC also registers
     * {@code MappingJackson2YamlHttpMessageConverter} (application/yaml). JSON must still be written by Boot 4's one
     * Jackson 3 converter (the golden files pin its output), and it must come before the YAML converter. The REST
     * controller declares {@code produces=application/json}, so YAML is never negotiated for it
     * ({@code PatientRestControllerTest#yamlIsNotNegotiable}).
     */
    @Test
    void jsonIsWrittenByTheJackson3ConverterOnly() {
        var adapter = context.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.class);
        var names = adapter.getMessageConverters().stream().map(c -> c.getClass().getName()).toList();
        String jackson3 = "org.springframework.http.converter.json.JacksonJsonHttpMessageConverter";
        assertThat(names).contains(jackson3)
                .noneMatch(name -> name.startsWith("org.springframework.http.converter.json.") && name.contains("Jackson2"));
        int yaml = names.indexOf("org.springframework.http.converter.yaml.MappingJackson2YamlHttpMessageConverter");
        if (yaml >= 0) {
            assertThat(names.indexOf(jackson3)).as("JSON converter before the YAML one").isLessThan(yaml);
        }
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
