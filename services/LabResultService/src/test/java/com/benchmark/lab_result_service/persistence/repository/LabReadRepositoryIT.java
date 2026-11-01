package com.benchmark.lab_result_service.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.benchmark.lab_result_service.core.api.PatientView;
import com.benchmark.lab_result_service.persistence.repository.ReadSequence.HistoryRows;
import com.benchmark.lab_result_service.persistence.repository.ReadSequence.SummaryRows;
import com.benchmark.lab_result_service.persistence.row.ItemRow;
import com.benchmark.lab_result_service.persistence.row.ReportRow;
import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import com.benchmark.lab_result_service.testsupport.OfficialTcKimlikValidator;
import com.benchmark.lab_result_service.testsupport.SqlCapture;
import jakarta.persistence.EntityManagerFactory;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Repository-level IT on the P1 fixture (contract §6.8, §10 P1): fixture sanity, §5.3 statement counts through
 * {@link ReadSequence}, ordering, §5.4 IN padding and §5.7 index use. Test-only edge rows (patient 2001) are
 * inserted inside the test method's rolled-back transaction.
 */
@Sql(scripts = "/fixtures/p1-fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@TestPropertySource(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.benchmark.lab_result_service.testsupport.SqlCapture")
@Import(ReadSequence.class)
class LabReadRepositoryIT extends AbstractPostgresIT {

    private static final Pattern BIND_MARKER = Pattern.compile("\\?");

    @Autowired LabReadRepository repository;
    @Autowired ReadSequence sequence;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;

    private Statistics stats;

    @BeforeEach
    void resetCounters() {
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        assertThat(stats.isStatisticsEnabled()).as("hibernate.generate_statistics in the test profile").isTrue();
        stats.clear();
        SqlCapture.clear();
    }

    // ---------------------------------------------------------------- fixture sanity (§6.1, §6.8)

    @Test
    void fixtureHasTheExactProductionShape() {
        assertThat(count("select count(*) from patient")).isEqualTo(1_000);
        assertThat(count("select count(*) from lab_report")).isEqualTo(10_080);
        assertThat(count("select count(*) from lab_test_item")).isEqualTo(100_800);

        assertThat(count("select count(*) from lab_report where patient_id = 1")).isEqualTo(100);
        assertThat(count("select count(*) from lab_report where patient_id = 2")).isZero();
        assertThat(count("select count(*) from lab_report where patient_id = 3")).isEqualTo(10);
        assertThat(count("select count(*) from patient where id = 1001")).isZero();
        assertThat(count("select count(*) from (select report_id from lab_test_item group by report_id"
                + " having count(*) <> 10) x")).as("every report has exactly 10 items").isZero();
        assertThat(jdbc.queryForObject("select min(id) || '..' || max(id) from lab_report where patient_id = 3",
                String.class)).isEqualTo("101..110");
        assertThat(jdbc.queryForObject("select min(id) || '..' || max(id) from lab_test_item where report_id = 101",
                String.class)).isEqualTo("1001..1010");
    }

    @Test
    void sequencesAreRealignedAfterExplicitIds() {
        assertThat(count("select last_value from patient_id_seq")).isEqualTo(1_000);
        assertThat(count("select last_value from lab_report_id_seq")).isEqualTo(10_080);
        assertThat(count("select last_value from lab_test_item_id_seq")).isEqualTo(100_800);
    }

    @Test
    void everyFixtureTcNoIsWellFormedUniqueAndOfficiallyInvalid() {
        List<String> tcNos = jdbc.queryForList("select tc_no from patient order by id", String.class);
        assertThat(tcNos).hasSize(1_000);
        assertThat(new HashSet<>(tcNos)).hasSize(1_000);
        assertThat(tcNos).allMatch(t -> t.matches("^[1-9][0-9]{10}$"));
        List<String> valid = tcNos.stream().filter(OfficialTcKimlikValidator::isValid).toList();
        assertThat(valid).as("D4: no fixture tcNo may be an officially valid identity").isEmpty();
        assertThat(tcNos.getFirst()).isEqualTo("10000000013");
    }

    // ---------------------------------------------------------------- statement counts (§5.3)

    @Test
    void statisticsCountEveryPreparedStatement() {
        // self-check of the measuring instrument: two calls must count two
        repository.findPatient(3);
        repository.findPatient(3);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void absentPatientCostsOneStatementForEveryMethod() {
        assertThat(counted(1, () -> sequence.patient(1001))).isEmpty();
        assertThat(counted(1, () -> sequence.summary(1001))).isEmpty();
        assertThat(counted(1, () -> sequence.history(1001))).isEmpty();
    }

    @Test
    void patientWithoutReports() {
        assertThat(counted(1, () -> sequence.patient(2))).isPresent();

        SummaryRows summary = counted(2, () -> sequence.summary(2)).orElseThrow();
        assertThat(summary.latestReport()).isNull();
        assertThat(summary.items()).isEmpty();

        HistoryRows history = counted(2, () -> sequence.history(2)).orElseThrow();
        assertThat(history.reports()).isEmpty();
        assertThat(history.items()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3})
    void patientWithReportsCostsOneTwoThree(long patientId) {
        PatientView patient = counted(1, () -> sequence.patient(patientId)).orElseThrow();
        assertThat(patient.id()).isEqualTo(patientId);

        SummaryRows summary = counted(3, () -> sequence.summary(patientId)).orElseThrow();
        assertThat(summary.items()).hasSize(10);

        HistoryRows history = counted(3, () -> sequence.history(patientId)).orElseThrow();
        assertThat(history.reports()).hasSize(patientId == 1 ? 100 : 10);
        assertThat(history.items()).hasSize(patientId == 1 ? 1_000 : 100);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveIdRunsNoStatement(long patientId) {
        assertThatThrownBy(() -> sequence.patient(patientId)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sequence.summary(patientId)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sequence.history(patientId)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stats.getPrepareStatementCount()).isZero();
    }

    @Test
    @Transactional
    void latestReportWithoutItemsAndTiedCreatedAt() {
        insertEdgePatient2001();
        stats.clear();

        SummaryRows summary = counted(3, () -> sequence.summary(2001)).orElseThrow();
        assertThat(summary.latestReport()).isNotNull();
        assertThat(summary.latestReport().id()).isEqualTo(20_001);
        assertThat(summary.items()).isEmpty();

        HistoryRows history = counted(3, () -> sequence.history(2001)).orElseThrow();
        assertThat(history.reports()).extracting(ReportRow::id)
                .as("created_at DESC, tie broken by id DESC; the item-less report is kept")
                .containsExactly(20_001L, 20_003L, 20_002L);
        assertThat(history.items()).extracting(ItemRow::reportId, ItemRow::id)
                .as("ordered by report id, then item id ASC (inserted out of order)")
                .containsExactly(
                        tuple(20_002L, 300_001L),
                        tuple(20_002L, 300_002L),
                        tuple(20_003L, 300_003L),
                        tuple(20_003L, 300_004L));
    }

    @Test
    void readPathNeverHydratesEntities() {
        sequence.patient(1);
        sequence.summary(1);
        sequence.history(1);
        assertThat(stats.getEntityLoadCount()).isZero();
        assertThat(stats.getEntityFetchCount()).isZero();
        assertThat(stats.getCollectionLoadCount()).isZero();
    }

    // ---------------------------------------------------------------- ordering and content (§3.1, §5.2)

    @Test
    void historyOfPatientOneIsOrderedAndComplete() {
        HistoryRows history = sequence.history(1).orElseThrow();
        List<ReportRow> reports = history.reports();
        assertThat(reports).extracting(ReportRow::id)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 100).boxed().toList());
        for (int i = 1; i < reports.size(); i++) {
            Instant newer = reports.get(i - 1).createdAt();
            Instant older = reports.get(i).createdAt();
            assertThat(newer).isAfter(older);
        }
        assertThat(reports.getFirst().createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(history.items()).extracting(ItemRow::id)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 1_000).boxed().toList());
    }

    @Test
    void summaryOfPatientThreeIsReport101WithItems1001To1010() {
        SummaryRows summary = sequence.summary(3).orElseThrow();
        assertThat(summary.patient()).isEqualTo(new PatientView(3, "10000000035", "Test Patient 3",
                LocalDate.of(1980, 1, 4), "B-"));
        ReportRow latest = summary.latestReport();
        assertThat(latest).isEqualTo(new ReportRow(101, "Dr. Test 3", "BLOOD", Instant.parse("2026-01-01T00:00:00Z")));
        assertThat(summary.items()).extracting(ItemRow::id)
                .containsExactlyElementsOf(LongStream.rangeClosed(1_001, 1_010).boxed().toList());
        assertThat(summary.items().getFirst())
                .isEqualTo(new ItemRow(101, 1_001, "T01", "Test 1", 1.5, "u", false));
        assertThat(summary.items().getLast().critical()).isTrue();
    }

    // ---------------------------------------------------------------- generated SQL (§5.2, §5.4)

    @Test
    void itemQueryPadsTheInListToAPowerOfTwo() {
        assertThat(bindMarkers(itemSqlFor(100))).isEqualTo(128);
        assertThat(bindMarkers(itemSqlFor(10))).isEqualTo(16);
        assertThat(bindMarkers(itemSqlFor(3))).isEqualTo(4);
        assertThat(bindMarkers(itemSqlFor(1))).isEqualTo(1);
    }

    @Test
    void latestReportQueryIsLimitedToOneRow() {
        assertThat(repository.findLatestReport(3)).map(ReportRow::id).contains(101L);
        String sql = SqlCapture.single().toLowerCase();
        assertThat(sql).containsPattern("(limit \\?|limit 1|fetch first (\\?|1) rows? only)\\s*$");
    }

    @Test
    void foreignKeyIdsAreReadWithoutJoins() {
        repository.findReports(1);
        repository.findLatestReport(1);
        repository.findItemsOfReport(1);
        repository.findItems(List.of(1L, 2L));
        List<String> sql = SqlCapture.statements();
        assertThat(sql).hasSize(4);
        assertThat(sql).allSatisfy(s -> assertThat(s.toLowerCase()).doesNotContain(" join "));
    }

    // ---------------------------------------------------------------- index use (§5.7)

    @Test
    void itemQueryUsesAnIndexLedByReportId() {
        String sql = itemSqlFor(100);
        assertThat(usesIndexLedBy(sql, patientOneReportIdBinder(), "lab_test_item", "report_id")).isTrue();
    }

    @Test
    void reportQueryUsesAnIndexLedByPatientId() {
        repository.findReports(1);
        String sql = SqlCapture.single();
        for (long patientId : new long[] {1, 3}) {
            assertThat(usesIndexLedBy(sql, (ps, n) -> ps.setLong(1, patientId), "lab_report", "patient_id"))
                    .as("patient %d", patientId).isTrue();
        }
    }

    /** Negative control: without any report_id-led index the same assertion must fail. Rolled back. */
    @Test
    @Transactional
    void itemIndexAssertionFailsWithoutReportIdIndexes() {
        String sql = itemSqlFor(100);
        jdbc.execute("drop index ix_lab_test_item_report_id");
        jdbc.execute("alter table lab_test_item drop constraint uq_lab_test_item_report_test");
        assertThat(usesIndexLedBy(sql, patientOneReportIdBinder(), "lab_test_item", "report_id")).isFalse();
    }

    /** Negative control: without the patient_id-led index the report assertion must fail. Rolled back. */
    @Test
    @Transactional
    void reportIndexAssertionFailsWithoutPatientIdIndex() {
        repository.findReports(1);
        String sql = SqlCapture.single();
        jdbc.execute("drop index ix_lab_report_patient_created");
        assertThat(usesIndexLedBy(sql, (ps, n) -> ps.setLong(1, 1), "lab_report", "patient_id")).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private <T> T counted(int expected, Supplier<T> call) {
        long before = stats.getPrepareStatementCount();
        T result = call.get();
        assertThat(stats.getPrepareStatementCount() - before).as("prepared statements").isEqualTo(expected);
        return result;
    }

    private String itemSqlFor(int reportCount) {
        SqlCapture.clear();
        List<ItemRow> items = repository.findItems(LongStream.rangeClosed(1, reportCount).boxed().toList());
        assertThat(items).hasSize(reportCount * 10);
        return SqlCapture.single();
    }

    private static int bindMarkers(String sql) {
        return (int) BIND_MARKER.matcher(sql).results().count();
    }

    /** Binds patient 1's 100 report ids 1..100 and repeats the last one into the padded slots (as Hibernate does). */
    private static Binder patientOneReportIdBinder() {
        return (ps, markers) -> {
            for (int i = 1; i <= markers; i++) {
                ps.setLong(i, Math.min(i, 100));
            }
        };
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps, int markers) throws SQLException;
    }

    /**
     * Runs {@code EXPLAIN (FORMAT JSON)} for the generated SQL and reports whether the plan contains an index
     * access on {@code table} through an index whose leading column (resolved from {@code pg_index.indkey[0]})
     * is {@code leadingColumn}. Index names are used only as catalogue keys, never asserted.
     */
    private boolean usesIndexLedBy(String sql, Binder binder, String table, String leadingColumn) {
        int markers = bindMarkers(sql);
        String planJson = jdbc.query("explain (format json) " + sql, ps -> binder.bind(ps, markers),
                rs -> rs.next() ? rs.getString(1) : null);
        assertThat(planJson).isNotNull();
        List<String> indexNames = new ArrayList<>();
        collectIndexAccess(new ObjectMapper().readTree(planJson), indexNames);
        for (String index : indexNames) {
            List<String> match = jdbc.queryForList("""
                    select a.attname
                    from pg_index i
                    join pg_class ic on ic.oid = i.indexrelid
                    join pg_class t  on t.oid  = i.indrelid
                    join pg_attribute a on a.attrelid = i.indrelid and a.attnum = i.indkey[0]
                    where ic.relname = ? and t.relname = ?""", String.class, index, table);
            if (match.contains(leadingColumn)) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> INDEX_ACCESS = Set.of("Index Scan", "Index Only Scan", "Bitmap Index Scan");

    private static void collectIndexAccess(JsonNode node, List<String> indexNames) {
        if (node.isArray()) {
            node.forEach(child -> collectIndexAccess(child, indexNames));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode type = node.get("Node Type");
        JsonNode index = node.get("Index Name");
        if (type != null && index != null && INDEX_ACCESS.contains(type.asString())) {
            indexNames.add(index.asString());
        }
        node.forEach(child -> collectIndexAccess(child, indexNames));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        assertThat(value).isNotNull();
        return value;
    }

    /**
     * Patient 2001 (test-only): report 20001 is the latest and has 0 items; reports 20002 and 20003 share the same
     * created_at, so 20003 must come first. Items are inserted out of id order.
     */
    private void insertEdgePatient2001() {
        jdbc.update("insert into patient (id, tc_no, full_name, birth_date, blood_type)"
                + " values (2001, '20000000013', 'Edge Case', date '1990-01-01', 'O+')");
        jdbc.update("insert into lab_report (id, patient_id, doctor_name, sample_type, created_at) values"
                + " (20001, 2001, 'Dr. Edge', 'BLOOD', timestamptz '2026-02-01 00:00:00+00'),"
                + " (20002, 2001, 'Dr. Edge', 'SERUM', timestamptz '2026-01-15 00:00:00+00'),"
                + " (20003, 2001, 'Dr. Edge', 'URINE', timestamptz '2026-01-15 00:00:00+00')");
        jdbc.update("insert into lab_test_item (id, report_id, test_code, test_name, result_value, unit, is_critical)"
                + " values (300004, 20003, 'T02', 'Test 2', 2.5, 'u', false),"
                + "        (300002, 20002, 'T02', 'Test 2', 2.5, 'u', false),"
                + "        (300003, 20003, 'T01', 'Test 1', 1.5, 'u', false),"
                + "        (300001, 20002, 'T01', 'Test 1', 1.5, 'u', true)");
    }
}
