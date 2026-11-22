package com.benchmark.lab_result_service.testsupport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Test-only JDBC-boundary counter (P7a fix round): sees <b>every</b> statement application code creates on the
 * application {@link DataSource}, whether it comes from Hibernate, {@code JdbcTemplate} or a raw connection.
 *
 * <p>Register it with {@code @Import(JdbcStatementCounter.Config.class)}. Its {@link BeanPostProcessor} wraps the
 * {@code DataSource} bean in a {@link DelegatingDataSource} (so {@code unwrap(HikariDataSource.class)} and Boot's
 * {@code DataSourceUnwrapper} keep working) whose connections are JDK proxies. Recorded, process-wide:
 * <ul>
 *   <li>{@code statements}: calls to {@code prepareStatement}, {@code prepareCall}, {@code createStatement};</li>
 *   <li>{@code executions}: calls to {@code execute*} ({@code execute}, {@code executeQuery}, {@code executeUpdate},
 *       {@code executeLargeUpdate}, {@code executeBatch}, {@code executeLargeBatch}) on those statements;</li>
 *   <li>{@code sql}: the SQL text in order: the text passed to {@code prepare*}, or, for a plain {@code Statement},
 *       the text passed to {@code execute*}/{@code addBatch}.</li>
 * </ul>
 *
 * <p><b>Pool housekeeping is outside by construction.</b> The wrapper sits <em>in front of</em> Hikari: it only sees
 * connections handed out by {@code getConnection()}. Hikari's own liveness checks ({@code Connection.isValid} with
 * {@code validation-timeout}; no {@code connectionTestQuery} or {@code connectionInitSql} is configured) and its
 * housekeeper/filler threads work on the physical connections inside the pool and never pass through this proxy.
 * Transaction control ({@code setAutoCommit}, {@code setReadOnly}, {@code commit}, {@code rollback}) is not a
 * statement at the JDBC API and is not counted; it is one shared service transaction for every protocol anyway.
 *
 * <p>The counters are global, so a measurement is only attributable while nothing else uses the pool (the ITs are
 * sequential; seeding/verification finish at context start). Concurrent work would add statements and make a
 * comparison fail, never hide a difference.
 */
public final class JdbcStatementCounter {

    private static final Object LOCK = new Object();
    private static long statements;
    private static long executions;
    private static final List<String> SQL = new ArrayList<>();

    private static final Set<String> CREATE = Set.of("prepareStatement", "prepareCall", "createStatement");
    private static final Set<String> EXECUTE = Set.of(
            "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch");

    private JdbcStatementCounter() {}

    /** Statements, executions and SQL text since the last {@link #clear()}. */
    public record Snapshot(long statements, long executions, List<String> sql) {}

    public static void clear() {
        synchronized (LOCK) {
            statements = 0;
            executions = 0;
            SQL.clear();
        }
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(statements, executions, List.copyOf(SQL));
        }
    }

    private static void created(@Nullable String sql) {
        synchronized (LOCK) {
            statements++;
            if (sql != null) {
                SQL.add(sql);
            }
        }
    }

    private static void executed(@Nullable String sql) {
        synchronized (LOCK) {
            executions++;
            if (sql != null) {
                SQL.add(sql);
            }
        }
    }

    private static void sqlOnly(String sql) {
        synchronized (LOCK) {
            SQL.add(sql);
        }
    }

    /** Imported by the IT that measures; never component-scanned (lives in {@code src/test}, not auto-detected). */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        static BeanPostProcessor jdbcStatementCounterPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource ds && !(bean instanceof CountingDataSource)
                            ? new CountingDataSource(ds) : bean;
                }
            };
        }
    }

    /** Delegates everything; wraps each connection. {@link AutoCloseable} so the pool is still closed on shutdown. */
    static final class CountingDataSource extends DelegatingDataSource implements AutoCloseable {

        CountingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return connection(obtainTargetDataSource().getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return connection(obtainTargetDataSource().getConnection(username, password));
        }

        @Override
        public void close() throws Exception {
            if (obtainTargetDataSource() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private static Connection connection(Connection target) {
        return (Connection) Proxy.newProxyInstance(JdbcStatementCounter.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    Object result = invoke(target, method, args);
                    if (CREATE.contains(method.getName()) && result instanceof Statement statement) {
                        boolean plain = method.getName().equals("createStatement");
                        created(plain ? null : (String) args[0]);
                        return statement(statement, plain);
                    }
                    return result;
                });
    }

    private static Statement statement(Statement target, boolean plain) {
        Class<?> type = target instanceof CallableStatement ? CallableStatement.class
                : target instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            boolean withSql = args != null && args.length > 0 && args[0] instanceof String;
            if (EXECUTE.contains(name)) {
                executed(plain && withSql ? (String) args[0] : null);
            } else if (plain && withSql && name.equals("addBatch")) {
                sqlOnly((String) args[0]);
            }
            return invoke(target, method, args);
        };
        return (Statement) Proxy.newProxyInstance(JdbcStatementCounter.class.getClassLoader(),
                new Class<?>[] {type}, handler);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
