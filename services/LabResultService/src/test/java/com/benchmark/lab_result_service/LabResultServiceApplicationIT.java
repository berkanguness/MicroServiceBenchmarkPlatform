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
