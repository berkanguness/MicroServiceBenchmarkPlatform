package com.benchmark.lab_result_service.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.benchmark.lab_result_service.LabResultServiceApplication;
import com.benchmark.lab_result_service.seed.SyntheticDatasetGenerator.Dataset;
import com.benchmark.lab_result_service.seed.SyntheticDatasetGenerator.ItemRecord;
import com.benchmark.lab_result_service.seed.SyntheticDatasetGenerator.PatientRecord;
import com.benchmark.lab_result_service.seed.SyntheticDatasetGenerator.ReportRecord;
import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contract §6.6 / §6.7 / §10 P2 against a disposable PostgreSQL 17 container.
 *
 * <p>Each test gets its own fresh database inside one class-owned container and starts the real application with
 * {@link SpringApplication} (like {@code StartupDbDownIT}), because the assertions are about <em>startup</em>: a
 * second start that skips, a start that must fail, readiness that must never be reached. The {@code seed} profile is
 * activated after {@code test}, so it overrides the test profile's {@code app.seed.enabled=false}; the verify mode is
 * always set explicitly. Rows are altered only here, in the disposable container, never by the seeder.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class DatasetSeederIT {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(AbstractPostgresIT.POSTGRES_IMAGE);

    private static final Pattern SEEDED_LOG = Pattern.compile("seeded 1000/10080/100800 rows in (\\d+) ms");
    private static final List<String> TABLES = List.of("patient", "lab_report", "lab_test_item");
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private static Dataset dataset;

    @BeforeAll
    static void generate() {
        dataset = SyntheticDatasetGenerator.generate();
    }

    @Test
    void seedsAnEmptyDatabaseThenSkipsThenFullVerifiesAndDetectsAnAlteredRow(CapturedOutput output) throws Exception {
        String db = freshDatabase();

        // 1. seed an empty database; readiness is reached only after seeding and cheap verification
        List<ApplicationEvent> events = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> logAtReadiness = new AtomicReference<>();
        events = new ListenerList(events, e -> {
            if (e instanceof AvailabilityChangeEvent<?> change && change.getState() == ReadinessState.ACCEPTING_TRAFFIC) {
                logAtReadiness.set(output.getOut());
            }
        });
        try (ConfigurableApplicationContext ctx = start(db, true, "cheap", events)) {
            Matcher m = SEEDED_LOG.matcher(output.getOut());
            assertThat(m.find()).as("seed time is logged").isTrue();
            assertThat(Long.parseLong(m.group(1))).isNotNegative();
            assertThat(output.getOut()).contains("dataset verified (verify-mode=cheap)");
            assertThat(readinessEvents(events)).containsExactly(ReadinessState.ACCEPTING_TRAFFIC);
            assertThat(events).anyMatch(ApplicationReadyEvent.class::isInstance);
            assertThat(logAtReadiness.get()).as("seeded and verified before readiness flipped to ACCEPTING_TRAFFIC")
                    .containsPattern(SEEDED_LOG).contains("dataset verified (verify-mode=cheap)");
            assertThat(readinessOverHttp(ctx)).isEqualTo(200);
        }
        assertThat(counts(db)).containsExactly(1_000L, 10_080L, 100_800L);
        assertThat(manifest(db)).isEqualTo(DatasetManifest.expected());
        assertRowsEqualTheGenerator(db);
        assertSequencesAreRealigned(db);
        Instant seededAt = seededAt(db);

        // 2. second start skips: nothing inserted, manifest untouched
        int seededLogs = countMatches(SEEDED_LOG, output.getOut());
        try (ConfigurableApplicationContext ignored = start(db, true, "cheap", new ArrayList<>())) {
            assertThat(output.getOut()).contains("dataset present, skipping seed");
            assertThat(countMatches(SEEDED_LOG, output.getOut())).as("no second seed").isEqualTo(seededLogs);
        }
        assertThat(counts(db)).containsExactly(1_000L, 10_080L, 100_800L);
        assertThat(seededAt(db)).isEqualTo(seededAt);

        // 3. full mode passes on the good database (recomputes the digest from the DB rows)
        try (ConfigurableApplicationContext ignored = start(db, true, "full", new ArrayList<>())) {
            assertThat(output.getOut()).contains("dataset verified (verify-mode=full)");
        }

        // 4. alter one row (disposable container only) -> full fails, startup fails, readiness never reached
        execute(db, "update lab_test_item set result_value = result_value + 0.1 where id = 1001");
        List<ApplicationEvent> failedEvents = Collections.synchronizedList(new ArrayList<>());
        assertThatThrownBy(() -> start(db, true, "full", failedEvents))
                .hasStackTraceContaining("Dataset verification failed (verify-mode=full)")
                .hasStackTraceContaining("recomputed checksum")
                .hasStackTraceContaining("!= dataset_meta checksum " + DatasetManifest.expected().checksumSha256());
        assertNeverReady(failedEvents);
        assertThat(counts(db)).as("nothing deleted after the failure").containsExactly(1_000L, 10_080L, 100_800L);

        // cheap does not recompute the digest, so it still passes: full is the gate before every measured campaign
        try (ConfigurableApplicationContext ignored = start(db, false, "cheap", new ArrayList<>())) {
            assertThat(output.getOut()).contains("dataset verified (verify-mode=cheap)");
        }

        // 5. an extra row breaks the counts -> cheap fails too (the seeder skips: the manifest matches)
        execute(db, "insert into patient (id, tc_no, full_name, birth_date, blood_type)"
                + " values (5000, '19999999990', 'Extra Row', date '1990-01-01', 'A+')");
        List<ApplicationEvent> cheapFailedEvents = Collections.synchronizedList(new ArrayList<>());
        assertThatThrownBy(() -> start(db, true, "cheap", cheapFailedEvents))
                .hasStackTraceContaining("count(*) patient = 1001, expected 1000");
        assertNeverReady(cheapFailedEvents);
        assertThat(counts(db)).containsExactly(1_001L, 10_080L, 100_800L);
        assertThat(seededAt(db)).isEqualTo(seededAt);
    }

    @Test
    void mismatchingManifestFailsStartupAndChangesNothing(CapturedOutput output) throws Exception {
        String db = migratedDatabase();
        execute(db, "insert into dataset_meta (name, generator_version, seed, patient_count, report_count, item_count,"
                + " checksum_sha256) values ('ym491', 2, 4912026, 0, 0, 0, '" + "a".repeat(64) + "')");

        List<ApplicationEvent> events = Collections.synchronizedList(new ArrayList<>());
        assertThatThrownBy(() -> start(db, true, "cheap", events))
                .hasStackTraceContaining("Dataset mismatch: manifest 'ym491' has generator_version=2, seed=4912026")
                .hasStackTraceContaining("Reset the database volume manually");
        assertNeverReady(events);
        assertThat(counts(db)).containsExactly(0L, 0L, 0L);
        assertThat(manifest(db).generatorVersion()).as("manifest untouched").isEqualTo(2);
        assertThat(SEEDED_LOG.matcher(output.getOut()).find()).isFalse();

        // other seed, same version: also a mismatch
        execute(db, "update dataset_meta set generator_version = 1, seed = 42 where name = 'ym491'");
        assertThatThrownBy(() -> start(db, true, "cheap", new ArrayList<>()))
                .hasStackTraceContaining("Dataset mismatch: manifest 'ym491' has generator_version=1, seed=42");
        assertThat(counts(db)).containsExactly(0L, 0L, 0L);
        assertThat(manifest(db).seed()).isEqualTo(42L);
    }

    @Test
    void rowsWithoutManifestFailStartupAndChangeNothing() throws Exception {
        String db = migratedDatabase();
        execute(db, "insert into patient (id, tc_no, full_name, birth_date, blood_type)"
                + " values (1, '10000000013', 'Foreign Row', date '1980-01-01', 'O+')");

        List<ApplicationEvent> events = Collections.synchronizedList(new ArrayList<>());
        assertThatThrownBy(() -> start(db, true, "cheap", events))
                .hasStackTraceContaining("Dataset mismatch: data rows exist but there is no 'ym491' manifest");
        assertNeverReady(events);
        assertThat(counts(db)).containsExactly(1L, 0L, 0L);
        assertThat(queryString(db, "select full_name from patient where id = 1")).isEqualTo("Foreign Row");
        assertThat(queryLong(db, "select count(*) from dataset_meta")).isZero();
    }

    @Test
    void emptyDatabaseWithoutSeedFailsClosedAndNeverBecomesReady() throws Exception {
        String db = freshDatabase();

        List<ApplicationEvent> events = Collections.synchronizedList(new ArrayList<>());
        assertThatThrownBy(() -> start(db, false, "cheap", events))
                .hasStackTraceContaining("Dataset verification failed (verify-mode=cheap)")
                .hasStackTraceContaining("no 'ym491' row in dataset_meta");
        assertNeverReady(events);
        assertThat(counts(db)).containsExactly(0L, 0L, 0L);
    }

    // --- application start -------------------------------------------------------------------------------------

    private static ConfigurableApplicationContext start(String db, boolean seedProfile, String verifyMode,
                                                        List<ApplicationEvent> events) {
        SpringApplication app = new SpringApplicationBuilder(LabResultServiceApplication.class)
                .listeners((ApplicationListener<ApplicationEvent>) events::add)
                .build();
        return app.run(
                "--spring.profiles.active=" + (seedProfile ? "test,seed" : "test"),
                "--app.dataset.verify-mode=" + verifyMode,
                "--spring.datasource.url=" + jdbcUrl(db),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.datasource.hikari.minimum-idle=2",
                "--server.port=0",
                "--spring.grpc.server.port=0");
    }

    private static List<ReadinessState> readinessEvents(List<ApplicationEvent> events) {
        List<ReadinessState> states = new ArrayList<>();
        for (ApplicationEvent e : List.copyOf(events)) {
            if (e instanceof AvailabilityChangeEvent<?> change && change.getState() instanceof ReadinessState r) {
                states.add(r);
            }
        }
        return states;
    }

    private static void assertNeverReady(List<ApplicationEvent> events) {
        assertThat(readinessEvents(events)).as("readiness never ACCEPTING_TRAFFIC")
                .doesNotContain(ReadinessState.ACCEPTING_TRAFFIC);
        assertThat(events).as("no ApplicationReadyEvent").noneMatch(ApplicationReadyEvent.class::isInstance);
    }

    private static int readinessOverHttp(ConfigurableApplicationContext ctx) throws Exception {
        String port = ctx.getEnvironment().getRequiredProperty("local.server.port");
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health/readiness")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.body()).contains("\"UP\"");
            return response.statusCode();
        }
    }

    // --- database helpers ---------------------------------------------------------------------------------------

    private static String freshDatabase() throws SQLException {
        String name = "ym491_seed_it_" + DATABASES.incrementAndGet();
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement s = c.createStatement()) {
            s.execute("create database " + name);
        }
        return name;
    }

    /** A fresh database with the Flyway schema applied, no rows (started once without seeding or verification). */
    private static String migratedDatabase() throws SQLException {
        String db = freshDatabase();
        try (ConfigurableApplicationContext ignored = start(db, false, "off", new ArrayList<>())) {
            // Flyway ran
        }
        assertThat(counts(db)).containsExactly(0L, 0L, 0L);
        return db;
    }

    private static String jdbcUrl(String db) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + db
                + "?reWriteBatchedInserts=true";
    }

    private static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(db), postgres.getUsername(), postgres.getPassword());
    }

    private static void execute(String db, String sql) throws SQLException {
        try (Connection c = connect(db); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static long queryLong(String db, String sql) throws SQLException {
        try (Connection c = connect(db); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String queryString(String db, String sql) throws SQLException {
        try (Connection c = connect(db); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<Long> counts(String db) throws SQLException {
        List<Long> counts = new ArrayList<>();
        for (String table : TABLES) {
            counts.add(queryLong(db, "select count(*) from " + table));
        }
        return counts;
    }

    private static DatasetManifest manifest(String db) throws SQLException {
        try (Connection c = connect(db); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "select name, generator_version, seed, patient_count, report_count, item_count, checksum_sha256"
                        + " from dataset_meta")) {
            assertThat(rs.next()).as("one dataset_meta row").isTrue();
            DatasetManifest m = new DatasetManifest(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getInt(4),
                    rs.getInt(5), rs.getInt(6), rs.getString(7));
            assertThat(rs.next()).as("exactly one dataset_meta row").isFalse();
            return m;
        }
    }

    private static Instant seededAt(String db) throws SQLException {
        try (Connection c = connect(db); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select seeded_at from dataset_meta")) {
            rs.next();
            return rs.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    /** {@code nextval} after seeding is above the max id of each table (§6.6 step 4). */
    private static void assertSequencesAreRealigned(String db) throws SQLException {
        for (String table : TABLES) {
            long max = queryLong(db, "select max(id) from " + table);
            long next = queryLong(db, "select nextval(pg_get_serial_sequence('" + table + "', 'id'))");
            assertThat(next).as("nextval of %s", table).isGreaterThan(max);
        }
    }

    /** Every row in the database equals the generator's row, field by field (round-trip check of the insert path). */
    private static void assertRowsEqualTheGenerator(String db) throws SQLException {
        try (Connection c = connect(db); Statement s = c.createStatement()) {
            List<PatientRecord> patients = new ArrayList<>();
            try (ResultSet rs = s.executeQuery(
                    "select id, tc_no, full_name, birth_date, blood_type from patient order by id")) {
                while (rs.next()) {
                    patients.add(new PatientRecord(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getObject(4, LocalDate.class), rs.getString(5)));
                }
            }
            assertThat(patients).isEqualTo(dataset.patients());

            List<ReportRecord> reports = new ArrayList<>();
            try (ResultSet rs = s.executeQuery(
                    "select id, patient_id, doctor_name, sample_type, created_at from lab_report order by id")) {
                while (rs.next()) {
                    reports.add(new ReportRecord(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getObject(5, OffsetDateTime.class).toInstant()));
                }
            }
            assertThat(reports).isEqualTo(dataset.reports());

            List<ItemRecord> items = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("select id, report_id, test_code, test_name, result_value, unit,"
                    + " is_critical from lab_test_item order by id")) {
                while (rs.next()) {
                    items.add(new ItemRecord(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getDouble(5), rs.getString(6), rs.getBoolean(7)));
                }
            }
            assertThat(items).isEqualTo(dataset.items());
        }
        assertThat(queryLong(db,
                "select id from lab_report where patient_id = 3 order by created_at desc, id desc limit 1"))
                .as("latest report of patient 3").isEqualTo(101L);
    }

    /** Records every event and additionally hands it to {@code onEvent} at the moment it is published. */
    private static final class ListenerList extends ArrayList<ApplicationEvent> {
        private final transient Consumer<ApplicationEvent> onEvent;

        ListenerList(List<ApplicationEvent> initial, Consumer<ApplicationEvent> onEvent) {
            super(initial);
            this.onEvent = onEvent;
        }

        @Override
        public synchronized boolean add(ApplicationEvent e) {
            onEvent.accept(e);
            return super.add(e);
        }
    }

    private static int countMatches(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
