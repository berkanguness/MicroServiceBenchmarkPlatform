package com.benchmark.lab_result_service.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Hung (not refused) database, chaos finding F3: with the container paused, the pool's idle connections must fail
 * borrow-time validation fast enough that acquisition gives up at about {@code connection-timeout} (2000 ms). Hikari's
 * default {@code validationTimeout} of 5000 ms is not clamped to {@code connectionTimeout}, so without
 * {@code spring.datasource.hikari.validation-timeout} the same call takes about 5 s.
 *
 * <p>The container is class-owned ({@link AbstractPostgresIT}) and is always unpaused in {@code finally}.
 */
@Import(ReadSequence.class)
class HungDatabaseIT extends AbstractPostgresIT {

    /** Hikari skips borrow-time validation for connections used within the last 500 ms. */
    private static final long PAST_VALIDATION_BYPASS_MS = 600;

    /** {@code connection-timeout} (2000 ms) plus scheduling slack; the unfixed pool needs about 5000 ms. */
    private static final long MAX_ACQUISITION_MS = 2_500;

    @Autowired ReadSequence sequence;

    @Test
    void acquisitionAgainstAPausedDatabaseFailsWithinTheConnectionTimeout() throws InterruptedException {
        assertThat(sequence.summary(3)).as("healthy call before the fault").isEmpty();

        var docker = postgres.getDockerClient();
        String id = postgres.getContainerId();
        docker.pauseContainerCmd(id).exec();
        try {
            Thread.sleep(PAST_VALIDATION_BYPASS_MS);

            long start = System.nanoTime();
            assertThatThrownBy(() -> sequence.summary(3))
                    .isInstanceOf(CannotCreateTransactionException.class)
                    .satisfies(e -> assertThat(causeChain(e))
                            .as("Hikari acquisition timeout in the cause chain (its own cause is the failed validation)")
                            .hasAtLeastOneElementOfType(SQLTransientConnectionException.class));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs).as("acquisition time against a hung database").isLessThanOrEqualTo(MAX_ACQUISITION_MS);
        } finally {
            docker.unpauseContainerCmd(id).exec();
        }
    }

    private static List<Throwable> causeChain(Throwable e) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = e; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }
}
