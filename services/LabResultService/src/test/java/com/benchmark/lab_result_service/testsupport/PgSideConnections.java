package com.benchmark.lab_result_service.testsupport;

import static org.assertj.core.api.Assertions.fail;

import com.github.dockerjava.api.DockerClient;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Deterministic in-flight fault injection for chaos ITs (first used by P3's {@code InFlightHungDatabaseIT}, chaos T-2).
 *
 * <p>A test-only connection outside the Hikari pool takes {@code ACCESS EXCLUSIVE} on {@code lab_report}; the app's
 * read then blocks on its {@code lab_report} SELECT inside its open read transaction. A monitor connection polls
 * {@code pg_stat_activity} until exactly that backend is {@code active} and waiting on the locker
 * ({@code pg_blocking_pids}). Only then does the test inject its fault (pause the container, terminate the backend),
 * so the fault provably hits an executing statement, not pool acquisition. No fixed sleeps.
 */
public final class PgSideConnections {

    /** Upper bound for the app's backend to show up as blocked in {@code pg_stat_activity}. */
    public static final long BLOCKED_DEADLINE_MS = 5_000;

    /** Socket timeout of the test's own connections, so cleanup can never hang forever. */
    private static final String HELPER_SOCKET_TIMEOUT_S = "10";

    /** Finds the app backend that is executing a {@code lab_report} statement and is blocked by the locker. */
    private static final String BLOCKED_BACKEND = """
            SELECT pid, query FROM pg_stat_activity
            WHERE state = 'active' AND wait_event_type = 'Lock'
              AND query ILIKE '%lab_report%'
              AND ? = ANY (pg_blocking_pids(pid))
            """;

    /** A blocked app backend: its pid and the statement it is executing. */
    public record BlockedBackend(int pid, String query) {
        @Override
        public String toString() {
            return "pid=" + pid + " " + query.replaceAll("\\s+", " ");
        }
    }

    private PgSideConnections() {}

    /** A plain driver connection outside the Hikari pool, so it neither borrows from nor perturbs the app's pool. */
    public static Connection open(PostgreSQLContainer postgres, String applicationName) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", postgres.getUsername());
        props.setProperty("password", postgres.getPassword());
        props.setProperty("ApplicationName", applicationName);
        props.setProperty("socketTimeout", HELPER_SOCKET_TIMEOUT_S);
        return DriverManager.getConnection(postgres.getJdbcUrl(), props);
    }

    public static int backendPid(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Starts a transaction on {@code locker} and takes {@code ACCESS EXCLUSIVE} on {@code lab_report}; roll back to release. */
    public static void lockLabReport(Connection locker) throws SQLException {
        locker.setAutoCommit(false);
        try (Statement lock = locker.createStatement()) {
            lock.execute("LOCK TABLE lab_report IN ACCESS EXCLUSIVE MODE");
        }
    }

    /** Polls until the app's backend is executing the {@code lab_report} SELECT and waits on the locker. */
    public static BlockedBackend awaitBlockedBackend(Connection monitor, int lockerPid, Future<?> call)
            throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BLOCKED_DEADLINE_MS);
        try (PreparedStatement ps = monitor.prepareStatement(BLOCKED_BACKEND)) {
            ps.setInt(1, lockerPid);
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new BlockedBackend(rs.getInt("pid"), rs.getString("query"));
                    }
                }
                if (call.isDone()) {
                    fail("the call ended before it blocked on the locked table");
                }
                Thread.sleep(5);                                   // poll interval, not the synchronization itself
            }
        }
        return fail("the app's lab_report SELECT never showed up as blocked within %d ms", BLOCKED_DEADLINE_MS);
    }

    /** {@code pg_terminate_backend}: the server ends that session (FATAL 57P01) and closes its socket. */
    public static boolean terminate(Connection monitor, int pid) throws SQLException {
        try (PreparedStatement ps = monitor.prepareStatement("SELECT pg_terminate_backend(?)")) {
            ps.setInt(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    public static void unpauseIfPaused(DockerClient docker, String id) {
        Boolean paused = docker.inspectContainerCmd(id).exec().getState().getPaused();
        if (Boolean.TRUE.equals(paused)) {
            docker.unpauseContainerCmd(id).exec();
        }
    }

    public static List<Throwable> causeChain(Throwable e) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = e; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }
}
