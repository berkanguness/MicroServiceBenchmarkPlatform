package com.benchmark.lab_result_service.testsupport;

import java.util.ArrayList;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Test-only Hibernate {@link StatementInspector} that records the final SQL Hibernate sends to JDBC (after IN-list
 * padding). Activated per IT class with
 * {@code spring.jpa.properties.hibernate.session_factory.statement_inspector=<this class>}.
 */
public class SqlCapture implements StatementInspector {

    private static final List<String> STATEMENTS = new ArrayList<>();

    @Override
    public String inspect(String sql) {
        synchronized (STATEMENTS) {
            STATEMENTS.add(sql);
        }
        return sql;
    }

    public static void clear() {
        synchronized (STATEMENTS) {
            STATEMENTS.clear();
        }
    }

    public static List<String> statements() {
        synchronized (STATEMENTS) {
            return List.copyOf(STATEMENTS);
        }
    }

    /** The single statement captured since the last {@link #clear()}; fails if there were zero or several. */
    public static String single() {
        List<String> all = statements();
        if (all.size() != 1) {
            throw new AssertionError("expected exactly one captured statement, got " + all.size() + ": " + all);
        }
        return all.getFirst();
    }
}
