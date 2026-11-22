package com.benchmark.lab_result_service.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.benchmark.lab_result_service.core.api.enums.ErrorCode;
import com.benchmark.lab_result_service.core.api.enums.Protocol;
import com.benchmark.lab_result_service.core.api.exception.ServiceUnavailableException;
import com.benchmark.lab_result_service.core.api.service.LabErrorRecorder;
import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import com.benchmark.lab_result_service.testsupport.PgSideConnections;
import com.benchmark.lab_result_service.testsupport.PgSideConnections.BlockedBackend;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runtime database outages through the real REST stack (contract §10 P4 {@code RestErrorIT}, chaos report §3(b) "P4 (A)"
 * and §5 T-4/T-7). Every case starts after a healthy startup and first asserts {@code GET …/3/summary} → 200; each
 * case owns its PostgreSQL 17 container and its Spring context ({@code @DirtiesContext}), so no fault leaks into
 * another case. Every failure must be a 503 {@code application/problem+json} with {@code code=UNAVAILABLE} and the
 * fixed {@code detail}, recorded exactly once ({@code lab.errors{protocol=REST,code=UNAVAILABLE}} +1, the
 * {@code @MockitoSpyBean} recorder called once) and counted once by the service
 * ({@code lab.service.calls{outcome=UNAVAILABLE}} +1). No retries anywhere.
 *
 * <ul>
 *   <li>(a) pool exhaustion → 503 after ≈ {@code connection-timeout} (2000 ms)</li>
 *   <li>(b) DB down at transaction begin (deterministic order N1) → cause chain proves the transaction-begin path</li>
 *   <li>(c) connection lost mid-transaction (backend terminated while its statement executes; the rollback then fails, F2)</li>
 *   <li>(d) an in-flight statement that receives no bytes for {@code socketTimeout} is cut and mapped to 503 (F1).
 *       What starves the read is the side-connection lock; the referee showed (d) passes without the pause too, so
 *       the pause is not what is proven (see the case's Javadoc)</li>
 *   <li>(e) T-7 recovery: DB server stopped → 503 → started → first 200 within 10 s (deadline-bounded), same
 *       context</li>
 * </ul>
 */
class RestErrorIT {

    static final String SUMMARY = "/api/v1/patients/3/summary";
    static final String FIXTURE = "/fixtures/p1-fixture.sql";
    static final String UNAVAILABLE_DETAIL = "Service temporarily unavailable";
    static final JsonMapper JSON = JsonMapper.builder().build();

    /** Shared wiring and assertions; each nested case adds its own class-owned container and properties. */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    @Testcontainers
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    abstract static class Case {

        @LocalServerPort int port;
        @Autowired MeterRegistry registry;
        @Autowired DataSource dataSource;
        @MockitoSpyBean LabErrorRecorder recorder;

        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        double errorsBefore;
        double serviceUnavailableBefore;

        abstract PostgreSQLContainer postgres();

        @BeforeEach
        void healthyFirst() throws Exception {
            // Not @Sql: that would run through the app's datasource, i.e. under (d)'s 1 s socketTimeout (flaky setup).
            PgSideConnections.loadFixtureOnce(postgres(), FIXTURE);
            HttpResponse<String> ok = get(SUMMARY);
            assertThat(ok.statusCode()).as("healthy call before the fault").isEqualTo(200);
            assertThat(JSON.readTree(ok.body()).get("latestReport").get("id").asLong()).isEqualTo(101);
            errorsBefore = restUnavailableErrors();
            serviceUnavailableBefore = serviceUnavailableCalls();
        }

        HttpResponse<String> get(String path) throws IOException, InterruptedException {
            return get(path, Duration.ofSeconds(30));
        }

        HttpResponse<String> get(String path, Duration timeout) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(timeout).GET().build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }

        /** 503, problem+json, fixed detail, nothing internal; exactly one record and one service count. Returns the recorded cause. */
        ServiceUnavailableException assertUnavailable(HttpResponse<String> response) throws Exception {
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
            JsonNode body = JSON.readTree(response.body());
            assertThat(body.get("status").asInt()).isEqualTo(503);
            assertThat(body.get("code").asString()).isEqualTo("UNAVAILABLE");
            assertThat(body.get("detail").asString()).isEqualTo(UNAVAILABLE_DETAIL);
            assertThat(body.get("instance").asString()).isEqualTo(SUMMARY);
            assertThat(response.body()).doesNotContain("Exception", "SQL", "jdbc", "Hikari", "\tat ");

            assertThat(restUnavailableErrors() - errorsBefore).as("lab.errors{protocol=REST,code=UNAVAILABLE}").isEqualTo(1);
            assertThat(serviceUnavailableCalls() - serviceUnavailableBefore)
                    .as("lab.service.calls{method=getPatientSummary,outcome=UNAVAILABLE}").isEqualTo(1);
            ArgumentCaptor<Throwable> cause = ArgumentCaptor.forClass(Throwable.class);
            verify(recorder, times(1)).record(eq(Protocol.REST), any(ErrorCode.class), cause.capture());
            verify(recorder).record(eq(Protocol.REST), eq(ErrorCode.UNAVAILABLE), any());
            assertThat(cause.getValue()).isInstanceOf(ServiceUnavailableException.class);
            return (ServiceUnavailableException) cause.getValue();
        }

        double restUnavailableErrors() {
            return registry.get("lab.errors").tag("protocol", "REST").tag("code", "UNAVAILABLE").counter().count();
        }

        double serviceUnavailableCalls() {
            return registry.get("lab.service.calls").tag("method", "getPatientSummary").tag("outcome", "UNAVAILABLE")
                    .timer().count();
        }

        HikariPoolMXBean pool() throws java.sql.SQLException {
            return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        }

        static long millisSince(long startNanos) {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }
    }

    // ------------------------------------------------------------------------------------------- (a) exhaustion

    @Nested
    @TestPropertySource(properties = {
            "spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.minimum-idle=1"})
    class PoolExhaustion extends Case {

        @Container @ServiceConnection
        static final PostgreSQLContainer postgres = new PostgreSQLContainer(AbstractPostgresIT.POSTGRES_IMAGE);

        @Override PostgreSQLContainer postgres() { return postgres; }

        @Test
        void theOnlyConnectionIsHeldSoTheRequestFailsAfterTheConnectionTimeout() throws Exception {
            long elapsed;
            HttpResponse<String> response;
            try (Connection held = dataSource.getConnection()) {
                assertThat(pool().getActiveConnections()).isEqualTo(1);
                long start = System.nanoTime();
                response = get(SUMMARY);
                elapsed = millisSince(start);
            }
            System.out.printf("RestErrorIT(a) pool exhaustion: 503 after %d ms%n", elapsed);

            ServiceUnavailableException cause = assertUnavailable(response);
            assertThat(cause.getCause()).isInstanceOf(CannotCreateTransactionException.class);
            assertThat(PgSideConnections.causeChain(cause)).anyMatch(SQLTransientConnectionException.class::isInstance);
            assertThat(elapsed).as("≈ connection-timeout 2000 ms").isBetween(1_900L, 3_000L);
            assertThat(get(SUMMARY).statusCode()).as("the pool serves again once the connection is back").isEqualTo(200);
        }
    }

    // --------------------------------------------------------------------------------- (b) DB down at tx begin

    @Nested
    class DatabaseDownAtTransactionBegin extends Case {

        @Container @ServiceConnection
        static final PostgreSQLContainer postgres = new PostgreSQLContainer(AbstractPostgresIT.POSTGRES_IMAGE);

        @Override PostgreSQLContainer postgres() { return postgres; }

        @Test
        void requestFailsInsideTransactionBeginWithCannotCreateTransaction() throws Exception {
            postgres.stop();                                       // (1) first: no replacement connection can be created
            HikariPoolMXBean pool = pool();
            pool.softEvictConnections();                           // (2) closes idle entries, also recently used ones
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (pool.getTotalConnections() != 0) {              // (3) every 50 ms, up to 10 s
                if (System.nanoTime() > deadline) {
                    fail("pool did not drain: total=%d idle=%d", pool.getTotalConnections(), pool.getIdleConnections());
                }
                Thread.sleep(50);
            }
            assertThat(pool.getIdleConnections()).isZero();

            long start = System.nanoTime();
            HttpResponse<String> response = get(SUMMARY);         // (4) only now
            long elapsed = millisSince(start);
            System.out.printf("RestErrorIT(b) DB down at begin: 503 after %d ms%n", elapsed);

            ServiceUnavailableException cause = assertUnavailable(response);
            assertThat(cause.getCause()).as("failed at transaction begin, not during a query")
                    .isInstanceOf(CannotCreateTransactionException.class);
            assertThat(PgSideConnections.causeChain(cause)).anyMatch(SQLTransientConnectionException.class::isInstance);
            assertThat(elapsed).as("≈ connection-timeout 2000 ms").isBetween(1_900L, 3_000L);
        }
    }

    // ------------------------------------------------------------------------------ (c) lost mid-transaction

    @Nested
    class ConnectionLostMidTransaction extends Case {

        @Container @ServiceConnection
        static final PostgreSQLContainer postgres = new PostgreSQLContainer(AbstractPostgresIT.POSTGRES_IMAGE);

        @Override PostgreSQLContainer postgres() { return postgres; }

        @Test
        void backendTerminatedWhileItsStatementExecutesIs503() throws Exception {
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try (Connection locker = PgSideConnections.open(postgres, "ym491-p4c-locker");
                    Connection monitor = PgSideConnections.open(postgres, "ym491-p4c-monitor")) {
                int lockerPid = PgSideConnections.backendPid(locker);
                HttpResponse<String> response;
                try {
                    PgSideConnections.lockLabReport(locker);
                    Future<HttpResponse<String>> call = caller.submit(() -> get(SUMMARY));
                    BlockedBackend blocked = PgSideConnections.awaitBlockedBackend(monitor, lockerPid, call);
                    assertThat(PgSideConnections.terminate(monitor, blocked.pid())).as("pg_terminate_backend").isTrue();
                    System.out.printf("RestErrorIT(c) terminated app backend mid-transaction: %s%n", blocked);
                    response = call.get(10, TimeUnit.SECONDS);
                } finally {
                    locker.rollback();
                }

                ServiceUnavailableException cause = assertUnavailable(response);
                assertThat(cause.getCause()).as("the callback's query failure (F2), not the rollback failure")
                        .isInstanceOf(DataAccessException.class)
                        .hasMessageContaining("executing SQL");
                List<Throwable> chain = PgSideConnections.causeChain(cause);
                assertThat(chain).as("not a pool acquisition failure").noneMatch(SQLTransientConnectionException.class::isInstance);
                PSQLException psql = chain.stream().filter(PSQLException.class::isInstance).map(PSQLException.class::cast)
                        .findFirst().orElseThrow(() -> new AssertionError("no PSQLException in " + chain));
                assertThat(psql.getSQLState()).as("server ended the session: admin_shutdown").isEqualTo("57P01");
            } finally {
                caller.shutdownNow();
            }
            assertThat(get(SUMMARY).statusCode()).as("the dead connection is replaced, no restart").isEqualTo(200);
        }
    }

    // ----------------------------------------------------------------------- (d) in-flight read without bytes

    /**
     * What this proves: an in-flight statement whose read receives no bytes for {@code socketTimeout} (1 s here) is
     * cut by the driver and ends as 503 {@code UNAVAILABLE}, with {@code SocketTimeoutException} in the chain and no
     * pool-acquisition failure. The read is starved by the side-connection lock ({@code pg_blocking_pids}); the
     * referee REPRODUCED that the case also passes without {@code docker pause}, so it does <em>not</em> prove that a
     * frozen server, as opposed to a lock wait, is what bounded the read (the socket mechanism is the same).
     *
     * <p>The pause is kept for the realism of what follows the cut: the rollback and close of the broken connection
     * meet a frozen server, and the final 200 after unpause shows that the pool's other connections survive a
     * frozen-then-resumed server without a restart.
     */
    @Nested
    @TestPropertySource(properties = "spring.datasource.hikari.data-source-properties.socketTimeout=" + HungDuringStatement.SOCKET_TIMEOUT_S)
    class HungDuringStatement extends Case {

        static final int SOCKET_TIMEOUT_S = 1;
        /** socketTimeout (1000 ms) + failed rollback + HTTP and scheduling slack. */
        static final long BUDGET_MS = 2_000;

        @Container @ServiceConnection
        static final PostgreSQLContainer postgres = new PostgreSQLContainer(AbstractPostgresIT.POSTGRES_IMAGE);

        @Override PostgreSQLContainer postgres() { return postgres; }

        @Test
        @DisplayName("(d) in-flight statement receiving no bytes for socketTimeout is cut → 503 UNAVAILABLE")
        void inFlightStatementWithoutBytesForTheSocketTimeoutIs503() throws Exception {
            var docker = postgres.getDockerClient();
            String id = postgres.getContainerId();
            ExecutorService caller = Executors.newSingleThreadExecutor();
            HttpResponse<String> response = null;
            long elapsed = -1;
            try (Connection locker = PgSideConnections.open(postgres, "ym491-p4d-locker");
                    Connection monitor = PgSideConnections.open(postgres, "ym491-p4d-monitor")) {
                int lockerPid = PgSideConnections.backendPid(locker);
                try {
                    PgSideConnections.lockLabReport(locker);
                    long start = System.nanoTime();
                    Future<HttpResponse<String>> call = caller.submit(() -> get(SUMMARY));
                    BlockedBackend blocked = PgSideConnections.awaitBlockedBackend(monitor, lockerPid, call);
                    try {
                        docker.pauseContainerCmd(id).exec();
                        assertThat(call.isDone()).as("the request is still executing when the container is paused").isFalse();
                        System.out.printf("RestErrorIT(d) paused while backend executed: %s%n", blocked);
                        try {
                            response = call.get(6, TimeUnit.SECONDS);
                        } catch (TimeoutException e) {
                            fail("the in-flight statement was not bounded by socketTimeout");
                        } catch (ExecutionException e) {
                            throw new AssertionError("HTTP call failed", e.getCause());
                        }
                        elapsed = millisSince(start);
                    } finally {
                        PgSideConnections.unpauseIfPaused(docker, id);
                    }
                } finally {
                    locker.rollback();
                }
            } finally {
                caller.shutdownNow();
            }
            System.out.printf("RestErrorIT(d) in-flight read without bytes: 503 after %d ms%n", elapsed);

            ServiceUnavailableException cause = assertUnavailable(response);
            assertThat(cause.getCause()).isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(PgSideConnections.causeChain(cause)).anyMatch(SocketTimeoutException.class::isInstance)
                    .noneMatch(SQLTransientConnectionException.class::isInstance);
            assertThat(elapsed).as("bounded by socketTimeout %d s", SOCKET_TIMEOUT_S).isLessThanOrEqualTo(BUDGET_MS);
            assertThat(get(SUMMARY).statusCode()).as("recovers after unpause, no restart").isEqualTo(200);
        }
    }

    // ---------------------------------------------------------------------------------------- (e) T-7 recovery

    /**
     * T-7: the database server stops (fast shutdown, as {@code docker stop} does) → 503 → it starts again → 200 within
     * {@link #RECOVERY_BUDGET_MS}, same Spring context and pool.
     *
     * <p>Port handling (race-free): the server is stopped and started <em>inside</em> a container that keeps running
     * ({@code pg_ctl stop -m fast} / {@code pg_ctl start} over {@code docker exec}). The container's PID 1 is a
     * keep-alive shell, not PostgreSQL, so the host port that Docker allocated at container start stays bound to
     * this container for its whole life, and the app's datasource URL stays valid. Nothing picks a free port and
     * releases it for someone else to take (the former {@code ServerSocket(0)} race).
     */
    @Nested
    class RecoveryAfterRestart extends Case {

        static final long RECOVERY_BUDGET_MS = 10_000;

        /** PID 1 keeps the container alive while the server is down; the image's entrypoint starts the server as usual. */
        private static final String KEEP_ALIVE = "docker-entrypoint.sh postgres -c fsync=off & while true; do sleep 3600; done";

        @Container @ServiceConnection
        static final PostgreSQLContainer postgres = new PostgreSQLContainer(AbstractPostgresIT.POSTGRES_IMAGE)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "-c", KEEP_ALIVE));

        @Override PostgreSQLContainer postgres() { return postgres; }

        @Test
        void databaseStopGives503AndStartRecoversWithin10sWithoutContextRestart() throws Exception {
            boolean stopped = false;
            try {
                pgCtl("-m fast -w stop");
                stopped = true;
                long start = System.nanoTime();
                HttpResponse<String> down = get(SUMMARY);
                System.out.printf("RestErrorIT(e) DB stopped: %d after %d ms%n", down.statusCode(), millisSince(start));
                assertUnavailable(down);
            } finally {
                if (stopped) {
                    startServer();
                }
            }
        }

        /** Starts the server and asserts the first 200 arrives within the budget; every attempt is bounded by it. */
        private void startServer() throws Exception {
            long started = System.nanoTime();
            long deadline = started + TimeUnit.MILLISECONDS.toNanos(RECOVERY_BUDGET_MS);
            pgCtl("-l /tmp/ym491-restart.log start");               // no -w: the clock covers the server's startup
            int attempts = 0;
            while (true) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) {
                    fail("no 200 within %d ms after the database start (%d attempts)", RECOVERY_BUDGET_MS, attempts);
                }
                attempts++;
                int status;
                try {
                    status = get(SUMMARY, Duration.ofMillis(remainingMs)).statusCode();
                } catch (HttpTimeoutException e) {
                    fail("attempt %d still had no response when the %d ms budget ran out", attempts, RECOVERY_BUDGET_MS);
                    return;
                }
                if (status == 200) {
                    break;
                }
                assertThat(status).as("only UNAVAILABLE while the database starts").isEqualTo(503);
                Thread.sleep(Math.min(100, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
            }
            long recoveredMs = millisSince(started);
            System.out.printf("RestErrorIT(e) first 200 %d ms after DB start (%d attempts)%n", recoveredMs, attempts);
            assertThat(recoveredMs).as("first 200 within %d ms after the database start", RECOVERY_BUDGET_MS)
                    .isLessThanOrEqualTo(RECOVERY_BUDGET_MS);
        }

        private static void pgCtl(String args) throws Exception {
            var result = postgres.execInContainerWithUser("postgres", "sh", "-c", "pg_ctl -D \"$PGDATA\" " + args);
            assertThat(result.getExitCode()).as("pg_ctl %s: %s %s", args, result.getStdout(), result.getStderr()).isZero();
        }
    }
}
