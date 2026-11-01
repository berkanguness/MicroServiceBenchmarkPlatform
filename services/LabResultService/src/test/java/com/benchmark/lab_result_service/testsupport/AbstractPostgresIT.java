package com.benchmark.lab_result_service.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base of every {@code *IT} that needs PostgreSQL (contract §10): a disposable PostgreSQL 17 container wired with
 * {@code @ServiceConnection}, profile {@code test}.
 *
 * <p>The container is class-owned: the Testcontainers extension starts it before and stops it after each IT class,
 * so every IT class sees a freshly migrated, otherwise empty database. {@code @DirtiesContext} closes the Spring
 * context together with the container, so a cached context never points at a stopped container.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractPostgresIT {

    /** Tag until DevOps pins a digest (contract §12 Q9). */
    public static final String POSTGRES_IMAGE = "postgres:17-alpine";

    @Container
    @ServiceConnection
    protected static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);
}
