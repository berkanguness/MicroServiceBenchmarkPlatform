package com.benchmark.lab_result_service.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.benchmark.lab_result_service.core.api.exception.ServiceUnavailableException;
import com.benchmark.lab_result_service.core.api.service.LabResultQueryService;
import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import com.benchmark.lab_result_service.testsupport.PgSideConnections;
import com.benchmark.lab_result_service.testsupport.PgSideConnections.BlockedBackend;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

/**
 * Chaos T-2 (findings F1 + F2): the database hangs while a statement is in flight. The JDBC {@code socketTimeout}
 * (lowered to 1 s here; 5 s by default) must end the blocked read, and the resulting mid-transaction connection loss,
 * whose rollback then fails, must still be classified as {@link ServiceUnavailableException} by the shared service.
 *
 * <p>Synchronization (no fixed sleeps): a test-only connection outside the Hikari pool takes an
 * {@code ACCESS EXCLUSIVE} lock on {@code lab_report}. The real {@code getPatientSummary} call (real service, real
 * transaction manager, real pool) then runs its patient SELECT normally and blocks on its second statement, the
 * {@code lab_report} SELECT, on its checked-out connection inside the open read transaction. A third, autocommit
 * monitor connection polls {@code pg_stat_activity} until exactly that backend is {@code active}, waiting on a lock
 * held by the locker ({@code pg_blocking_pids}). Only then is the container paused, and the call is asserted to still
 * be running at that moment. So the fault provably hits an executing statement, not pool acquisition or validation
 * (that is {@code HungDatabaseIT}, F3).
 *
 * <p>pgjdbc's {@code socketTimeout} is a read <em>inactivity</em> timeout whose clock starts when the driver begins
 * reading the statement's response, so the measured time includes the short lock wait before the pause. Every
 * cleanup step (unpause, lock release, executor shutdown) runs in its own {@code finally}, also when a Docker command
 * throws.
 */
@Sql(scripts = "/fixtures/p1-fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@TestPropertySource(properties = "spring.datasource.hikari.data-source-properties.socketTimeout=" + InFlightHungDatabaseIT.SOCKET_TIMEOUT_S)
class InFlightHungDatabaseIT extends AbstractPostgresIT {

    static final int SOCKET_TIMEOUT_S = 1;

    /** socketTimeout (1000 ms) plus the failed rollback and scheduling slack. */
    private static final long MAX_ELAPSED_MS = 1_500;

    /** Without a socket timeout the call would hang until the lock is released; give up waiting well before that. */
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
        try (Connection locker = PgSideConnections.open(postgres, "ym491-t2-locker");
                Connection monitor = PgSideConnections.open(postgres, "ym491-t2-monitor")) {
            int lockerPid = PgSideConnections.backendPid(locker);
            try {
                PgSideConnections.lockLabReport(locker);

                long start = System.nanoTime();
                Future<?> call = caller.submit(() -> service.getPatientSummary(3));
                BlockedBackend blocked = PgSideConnections.awaitBlockedBackend(monitor, lockerPid, call);

                try {
                    docker.pauseContainerCmd(id).exec();
                    assertThat(call.isDone()).as("the call is still executing when the database hangs").isFalse();
                    System.out.printf("T-2 paused while backend executed: %s%n", blocked);

                    Throwable failure = null;
                    try {
                        call.get(GIVE_UP_MS, TimeUnit.MILLISECONDS);
                    } catch (ExecutionException e) {
                        failure = e.getCause();
                    } catch (TimeoutException e) {
                        fail("the in-flight statement was not bounded: still running after %d ms", GIVE_UP_MS);
                    }
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    System.out.printf("T-2 elapsed to UNAVAILABLE: %d ms%n", elapsedMs);

                    assertThat(failure).as("outcome of the in-flight call").isInstanceOf(ServiceUnavailableException.class);
                    assertEndedByTheExecutingQuery(failure);
                    assertThat(elapsedMs).as("time to UNAVAILABLE against a hung database").isLessThanOrEqualTo(MAX_ELAPSED_MS);
                    assertThat(unavailableCount() - unavailableBefore).as("lab.service.calls{outcome=UNAVAILABLE}").isEqualTo(1);
                } finally {
                    PgSideConnections.unpauseIfPaused(docker, id);
                }
            } finally {
                locker.rollback();                                 // releases the lock (after unpause)
            }
        } finally {
            caller.shutdownNow();
        }

        assertThat(service.getPatientSummary(3).latestReport()).as("recovers after unpause, no restart").isNotNull();
    }

    /**
     * The service preserved the callback's query failure as the direct cause (F2): Spring's "JDBC exception executing
     * SQL" over pgjdbc's connection-failure state 08006 over the socket read timeout. Nothing from pool acquisition.
     */
    private static void assertEndedByTheExecutingQuery(Throwable failure) {
        Throwable direct = failure.getCause();
        assertThat(direct).as("direct cause: the callback's query failure, not the rollback failure")
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(direct.getMessage()).as("Spring's message for a failed statement").contains("executing SQL");
        List<Throwable> chain = PgSideConnections.causeChain(failure);
        PSQLException psql = chain.stream().filter(PSQLException.class::isInstance).map(PSQLException.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("no PSQLException in " + chain));
        assertThat(psql.getSQLState()).as("pgjdbc connection failure during I/O").isEqualTo("08006");
        assertThat(psql.getCause()).as("the statement's read hit the socket timeout").isInstanceOf(SocketTimeoutException.class);
        assertThat(chain).as("not a pool acquisition failure (HungDatabaseIT covers that)")
                .noneMatch(t -> t instanceof SQLTransientConnectionException);
    }

    private double unavailableCount() {
        return registry.get("lab.service.calls").tag("method", "getPatientSummary").tag("outcome", "UNAVAILABLE")
                .timer().count();
    }
}
