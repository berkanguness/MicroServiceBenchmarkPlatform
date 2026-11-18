package com.benchmark.lab_result_service.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.benchmark.lab_result_service.core.api.exception.ServiceUnavailableException;
import com.benchmark.lab_result_service.core.api.service.LabResultQueryService;
import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

/**
 * Chaos T-2 (findings F1 + F2): the database hangs while a statement is in flight. The JDBC {@code socketTimeout}
 * (lowered to 1 s here; 5 s by default) must end the statement, and the resulting mid-transaction connection loss,
 * whose rollback then fails, must still be classified as {@link ServiceUnavailableException} by the shared service.
 *
 * <p>The healthy call right before the pause returns its connection to the pool; the next call reuses it within
 * Hikari's 500 ms validation bypass, so the statement itself (not acquisition, which is {@code HungDatabaseIT}) is
 * what hangs. The container is class-owned and always unpaused in {@code finally}.
 */
@Sql(scripts = "/fixtures/p1-fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@TestPropertySource(properties = "spring.datasource.hikari.data-source-properties.socketTimeout=" + InFlightHungDatabaseIT.SOCKET_TIMEOUT_S)
class InFlightHungDatabaseIT extends AbstractPostgresIT {

    static final int SOCKET_TIMEOUT_S = 1;

    /** socketTimeout (1000 ms) plus the failed rollback and scheduling slack. */
    private static final long MAX_ELAPSED_MS = 1_500;

    /** Without a socket timeout the call would hang until unpause; give up waiting well before that. */
    private static final long GIVE_UP_MS = 6_000;

    @Autowired LabResultQueryService service;
    @Autowired MeterRegistry registry;

    @Test
    void statementAgainstAHungDatabaseEndsAsUnavailableWithinTheSocketTimeout() throws Exception {
        assertThat(service.getPatientSummary(3).latestReport()).as("healthy call before the fault").isNotNull();
        double unavailableBefore = unavailableCount();

        var docker = postgres.getDockerClient();
        String id = postgres.getContainerId();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        docker.pauseContainerCmd(id).exec();
        try {
            long start = System.nanoTime();
            Future<?> call = caller.submit(() -> service.getPatientSummary(3));
            Throwable failure = null;
            try {
                call.get(GIVE_UP_MS, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                failure = e.getCause();
            } catch (TimeoutException e) {
                fail("the in-flight statement was not bounded: still running after %d ms", GIVE_UP_MS);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(failure).as("outcome of the in-flight call").isInstanceOf(ServiceUnavailableException.class);
            assertThat(causeChain(failure)).as("ended by the JDBC socket timeout, not by pool acquisition")
                    .hasAtLeastOneElementOfType(SocketTimeoutException.class);
            assertThat(elapsedMs).as("time to UNAVAILABLE against a hung database").isLessThanOrEqualTo(MAX_ELAPSED_MS);
            assertThat(unavailableCount() - unavailableBefore).as("lab.service.calls{outcome=UNAVAILABLE}").isEqualTo(1);
        } finally {
            docker.unpauseContainerCmd(id).exec();
            caller.shutdownNow();
        }

        assertThat(service.getPatientSummary(3).latestReport()).as("recovers after unpause, no restart").isNotNull();
    }

    private double unavailableCount() {
        return registry.get("lab.service.calls").tag("method", "getPatientSummary").tag("outcome", "UNAVAILABLE")
                .timer().count();
    }

    private static List<Throwable> causeChain(Throwable e) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = e; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }
}
