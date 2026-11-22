package com.benchmark.lab_result_service.seed;

import com.benchmark.lab_result_service.seed.SyntheticDatasetGenerator.Dataset;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds the synthetic dataset at startup (contract §6.6). Active only when {@code app.seed.enabled=true} (the
 * {@code seed} profile). Runs before the {@link DatasetVerifier} ({@code @Order(0)} vs {@code @Order(1)}).
 *
 * <p>Everything happens in one transaction: advisory lock 491 → manifest / empty-table decision → batched inserts with
 * explicit ids → {@code setval} → {@code ANALYZE} → manifest row last → commit. It <strong>never</strong> deletes,
 * truncates or updates: any state it does not recognise fails startup and must be reset manually.
 */
@Component
@Order(0)
@ConditionalOnBooleanProperty("app.seed.enabled")
public class DatasetSeeder implements ApplicationRunner {

    static final int BATCH_SIZE = 1_000;
    static final long ADVISORY_LOCK_KEY = 491L;

    private static final Logger log = LoggerFactory.getLogger(DatasetSeeder.class);

    private static final String INSERT_PATIENT =
            "insert into patient (id, tc_no, full_name, birth_date, blood_type) values (?, ?, ?, ?, ?)";
    private static final String INSERT_REPORT =
            "insert into lab_report (id, patient_id, doctor_name, sample_type, created_at) values (?, ?, ?, ?, ?)";
    private static final String INSERT_ITEM = "insert into lab_test_item"
            + " (id, report_id, test_code, test_name, result_value, unit, is_critical) values (?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_MANIFEST = "insert into dataset_meta"
            + " (name, generator_version, seed, patient_count, report_count, item_count, checksum_sha256)"
            + " values (?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DatasetSeeder(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        long start = System.nanoTime();
        DatasetManifest seeded = tx.execute(status -> seedInTransaction());
        if (seeded != null) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("seeded {}/{}/{} rows in {} ms", seeded.patientCount(), seeded.reportCount(),
                    seeded.itemCount(), ms);
        }
    }

    /**
     * Returns the manifest written if rows were inserted, {@code null} if a matching dataset is already present.
     * Throws (rolling the transaction back, nothing written) for any state it does not recognise.
     */
    private DatasetManifest seedInTransaction() {
        jdbc.execute("select pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");

        List<Map<String, Object>> manifest = jdbc.queryForList(
                "select generator_version, seed from dataset_meta where name = ?",
                SyntheticDatasetGenerator.DATASET_NAME);
        if (!manifest.isEmpty()) {
            Map<String, Object> row = manifest.getFirst();
            int version = ((Number) row.get("generator_version")).intValue();
            long seed = ((Number) row.get("seed")).longValue();
            if (version == SyntheticDatasetGenerator.GENERATOR_VERSION && seed == SyntheticDatasetGenerator.SEED) {
                log.info("dataset present, skipping seed");
                return null;
            }
            throw mismatch("manifest '" + SyntheticDatasetGenerator.DATASET_NAME + "' has generator_version="
                    + version + ", seed=" + seed + "; expected generator_version="
                    + SyntheticDatasetGenerator.GENERATOR_VERSION + ", seed=" + SyntheticDatasetGenerator.SEED);
        }
        boolean empty = isEmpty("patient") && isEmpty("lab_report") && isEmpty("lab_test_item");
        if (!empty) {
            throw mismatch("data rows exist but there is no '" + SyntheticDatasetGenerator.DATASET_NAME
                    + "' manifest");
        }

        Dataset dataset = SyntheticDatasetGenerator.generate();
        DatasetManifest m = DatasetManifest.of(dataset);
        if (!m.equals(DatasetManifest.expected())) {
            // Fail closed before writing anything: the generator no longer produces the committed dataset version.
            throw new IllegalStateException("Generator output " + m + " does not match " + DatasetManifest.RESOURCE
                    + " " + DatasetManifest.expected() + "; a generator change needs a new dataset version");
        }
        insertRows(dataset);
        for (String table : List.of("patient", "lab_report", "lab_test_item")) {
            jdbc.queryForObject("select setval(pg_get_serial_sequence('" + table + "', 'id'),"
                    + " (select max(id) from " + table + "))", Long.class);
        }
        jdbc.execute("analyze patient, lab_report, lab_test_item");

        jdbc.update(INSERT_MANIFEST, m.name(), m.generatorVersion(), m.seed(), m.patientCount(), m.reportCount(),
                m.itemCount(), m.checksumSha256());
        return m;
    }

    private void insertRows(Dataset dataset) {
        jdbc.batchUpdate(INSERT_PATIENT, dataset.patients(), BATCH_SIZE, (ps, p) -> {
            ps.setLong(1, p.id());
            ps.setString(2, p.tcNo());
            ps.setString(3, p.fullName());
            ps.setObject(4, p.birthDate());
            ps.setString(5, p.bloodType());
        });
        jdbc.batchUpdate(INSERT_REPORT, dataset.reports(), BATCH_SIZE, (ps, rep) -> {
            ps.setLong(1, rep.id());
            ps.setLong(2, rep.patientId());
            ps.setString(3, rep.doctorName());
            ps.setString(4, rep.sampleType());
            ps.setObject(5, OffsetDateTime.ofInstant(rep.createdAt(), ZoneOffset.UTC));
        });
        jdbc.batchUpdate(INSERT_ITEM, dataset.items(), BATCH_SIZE, (ps, i) -> {
            ps.setLong(1, i.id());
            ps.setLong(2, i.reportId());
            ps.setString(3, i.testCode());
            ps.setString(4, i.testName());
            ps.setDouble(5, i.resultValue());
            ps.setString(6, i.unit());
            ps.setBoolean(7, i.critical());
        });
    }

    private boolean isEmpty(String table) {
        return !Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from " + table + ")", Boolean.class));
    }

    private static IllegalStateException mismatch(String detail) {
        return new IllegalStateException("Dataset mismatch: " + detail
                + ". The seeder never deletes or updates rows. Reset the database volume manually.");
    }
}
