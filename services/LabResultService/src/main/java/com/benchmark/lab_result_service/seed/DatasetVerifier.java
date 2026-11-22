package com.benchmark.lab_result_service.seed;

import com.benchmark.lab_result_service.seed.DatasetProperties.VerifyMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Startup dataset verification (contract §6.7, R6), {@code app.dataset.verify-mode}:
 * <ul>
 *   <li>{@code cheap} (default): the {@code ym491} manifest exists with {@code generator_version = 1} and
 *       {@code seed = 4912026}; {@code count(*)} of the three tables = manifest counts = the counts of
 *       {@code seed/dataset-v1.json}.</li>
 *   <li>{@code full}: {@code cheap}, then the canonical digest (§6.5) is recomputed from the database rows and must equal
 *       both the manifest row's checksum and {@code seed/dataset-v1.json}.</li>
 *   <li>{@code off}: nothing (tests with tiny fixtures only).</li>
 * </ul>
 * Any failure throws, so {@code SpringApplication.run} fails and readiness ({@code ApplicationReadyEvent}) is never
 * reached. Runs after the {@link DatasetSeeder} ({@code @Order(1)}). Read-only: one {@code REPEATABLE READ}, read-only
 * transaction, so all queries of one verification see the same snapshot.
 */
@Component
@Order(1)
public class DatasetVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatasetVerifier.class);
    private static final int FETCH_SIZE = 1_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readTx;
    private final VerifyMode mode;

    /** When the last verification passed (§11.2 {@code lab.dataset.verifiedAt}); {@code null} before that or when off. */
    private volatile @Nullable Instant verifiedAt;

    public DatasetVerifier(DataSource dataSource, PlatformTransactionManager transactionManager,
                           DatasetProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setFetchSize(FETCH_SIZE);            // pgjdbc streams with a cursor inside a transaction
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.mode = properties.dataset().verifyMode();
    }

    public VerifyMode mode() {
        return mode;
    }

    /**
     * The instant the startup verification passed, for {@code /actuator/info} (§11.2). {@code null} if it is off or has
     * not run; a failed verification fails startup, so a running application with a non-off mode always has one.
     */
    public @Nullable Instant verifiedAt() {
        return verifiedAt;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mode == VerifyMode.OFF) {
            log.warn("dataset verification is off (app.dataset.verify-mode=off); for tests with tiny fixtures only");
            return;
        }
        long start = System.nanoTime();
        List<String> problems = readTx.execute(status -> verify());
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (problems == null || !problems.isEmpty()) {
            throw new IllegalStateException("Dataset verification failed (verify-mode=" + label() + "): "
                    + String.join("; ", problems == null ? List.of("no result") : problems));
        }
        verifiedAt = Instant.now();
        log.info("dataset verified (verify-mode={}) in {} ms", label(), ms);
    }

    private List<String> verify() {
        DatasetManifest expected = DatasetManifest.expected();
        List<String> problems = new ArrayList<>();

        List<Map<String, Object>> rows = jdbc.queryForList("select generator_version, seed, patient_count,"
                + " report_count, item_count, checksum_sha256 from dataset_meta where name = ?", expected.name());
        if (rows.isEmpty()) {
            problems.add("no '" + expected.name() + "' row in dataset_meta (empty database without the seed profile?)");
            return problems;
        }
        Map<String, Object> m = rows.getFirst();
        check(problems, "manifest generator_version", num(m, "generator_version"), expected.generatorVersion());
        check(problems, "manifest seed", num(m, "seed"), expected.seed());
        check(problems, "manifest patient_count", num(m, "patient_count"), expected.patientCount());
        check(problems, "manifest report_count", num(m, "report_count"), expected.reportCount());
        check(problems, "manifest item_count", num(m, "item_count"), expected.itemCount());
        check(problems, "count(*) patient", count("patient"), num(m, "patient_count"));
        check(problems, "count(*) lab_report", count("lab_report"), num(m, "report_count"));
        check(problems, "count(*) lab_test_item", count("lab_test_item"), num(m, "item_count"));

        if (mode == VerifyMode.FULL && problems.isEmpty()) {
            String stored = (String) m.get("checksum_sha256");
            String recomputed = digestFromDatabase();
            if (!recomputed.equals(stored)) {
                problems.add("recomputed checksum " + recomputed + " != dataset_meta checksum " + stored);
            }
            if (!recomputed.equals(expected.checksumSha256())) {
                problems.add("recomputed checksum " + recomputed + " != " + DatasetManifest.RESOURCE + " checksum "
                        + expected.checksumSha256());
            }
        }
        return problems;
    }

    /** §6.5 digest recomputed from the rows as the database returns them. */
    private String digestFromDatabase() {
        CanonicalDigest digest = new CanonicalDigest();
        jdbc.query("select id, tc_no, full_name, birth_date, blood_type from patient order by id",
                rs -> {
                    digest.patient(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getObject(4, LocalDate.class), rs.getString(5));
                });
        jdbc.query("select id, patient_id, doctor_name, sample_type, created_at from lab_report order by id",
                rs -> {
                    Instant createdAt = rs.getObject(5, OffsetDateTime.class).toInstant();
                    digest.report(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), createdAt);
                });
        jdbc.query("select id, report_id, test_code, test_name, result_value, unit, is_critical"
                        + " from lab_test_item order by id",
                rs -> {
                    digest.item(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getDouble(5),
                            rs.getString(6), rs.getBoolean(7));
                });
        return digest.hex();
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return n == null ? -1 : n;
    }

    private static long num(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private static void check(List<String> problems, String what, long actual, long expected) {
        if (actual != expected) {
            problems.add(what + " = " + actual + ", expected " + expected);
        }
    }

    private String label() {
        return mode.name().toLowerCase(java.util.Locale.ROOT);
    }
}
