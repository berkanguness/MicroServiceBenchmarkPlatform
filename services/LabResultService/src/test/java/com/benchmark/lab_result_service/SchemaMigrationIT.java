package com.benchmark.lab_result_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * V1 on an empty PostgreSQL 17 (contract §4, §10 P1). Every method runs in a rolled-back transaction, so each
 * constraint check starts from the migrated, empty schema.
 */
@Transactional
class SchemaMigrationIT extends AbstractPostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;

    @BeforeEach
    void baseRows() {
        jdbc.update("insert into patient (id, tc_no, full_name, birth_date, blood_type)"
                + " values (1, '12345678901', 'Base Patient', date '1990-01-01', 'A+')");
        jdbc.update("insert into lab_report (id, patient_id, doctor_name, sample_type, created_at)"
                + " values (1, 1, 'Dr. Base', 'BLOOD', timestamptz '2026-01-01 00:00:00+00')");
    }

    @Test
    void flywayAppliedExactlyV1OnTheEmptyDatabase() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "select version, script, success from flyway_schema_history where version is not null order by installed_rank");
        assertThat(history).hasSize(1);
        assertThat(history.getFirst())
                .containsEntry("version", "1")
                .containsEntry("script", "V1__init_schema.sql")
                .containsEntry("success", true);
        assertThat(jdbc.queryForObject("select current_setting('server_version_num')::int / 10000", Integer.class))
                .isEqualTo(17);
    }

    @Test
    void hibernateValidatedTheSchema() {
        // The context only starts if ddl-auto=validate accepted the entities against V1.
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'public'"
                + " order by table_name", String.class))
                .containsExactly("dataset_meta", "flyway_schema_history", "lab_report", "lab_test_item", "patient");
    }

    @Test
    void namedIndexesExistWithTheirColumns() {
        assertThat(indexDef("ix_lab_report_patient_created"))
                .endsWith("ON public.lab_report USING btree (patient_id, created_at DESC, id DESC)");
        assertThat(indexDef("ix_lab_test_item_report_id"))
                .endsWith("ON public.lab_test_item USING btree (report_id, id)");
    }

    @Test
    void namedConstraintsExist() {
        List<String> names = jdbc.queryForList("select c.conname from pg_constraint c"
                + " join pg_namespace n on n.oid = c.connamespace where n.nspname = 'public'"
                + " and c.conrelid::regclass::text in ('patient','lab_report','lab_test_item','dataset_meta')"
                + " and c.contype in ('p','u','c','f')", String.class);
        assertThat(names).containsExactlyInAnyOrder(
                "pk_patient", "uq_patient_tc_no", "ck_patient_tc_no_format", "ck_patient_full_name_not_blank",
                "ck_patient_birth_date", "ck_patient_blood_type",
                "pk_lab_report", "fk_lab_report_patient", "ck_lab_report_doctor_not_blank", "ck_lab_report_sample_type",
                "pk_lab_test_item", "fk_lab_test_item_report", "uq_lab_test_item_report_test",
                "ck_lab_test_item_result_finite",
                "pk_dataset_meta", "ck_dataset_meta_checksum_hex");
        // confdeltype 'r' = RESTRICT (D8); 'a' would be NO ACTION
        assertThat(jdbc.queryForList("select conname || ':' || confdeltype::text from pg_constraint where contype = 'f'"
                + " order by conname", String.class))
                .containsExactly("fk_lab_report_patient:r", "fk_lab_test_item_report:r");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            ck_patient_tc_no_format        | insert into patient (id, tc_no, full_name, birth_date, blood_type) values (2, '01234567890', 'X', date '1990-01-01', 'A+')
            ck_patient_tc_no_format        | insert into patient (id, tc_no, full_name, birth_date, blood_type) values (2, '1234567890a', 'X', date '1990-01-01', 'A+')
            ck_patient_full_name_not_blank | insert into patient (id, tc_no, full_name, birth_date, blood_type) values (2, '12345678902', '   ', date '1990-01-01', 'A+')
            ck_patient_birth_date          | insert into patient (id, tc_no, full_name, birth_date, blood_type) values (2, '12345678902', 'X', date '1899-12-31', 'A+')
            ck_patient_blood_type          | insert into patient (id, tc_no, full_name, birth_date, blood_type) values (2, '12345678902', 'X', date '1990-01-01', 'C+')
            uq_patient_tc_no               | insert into patient (id, tc_no, full_name, birth_date, blood_type) values (2, '12345678901', 'X', date '1990-01-01', 'A+')
            ck_lab_report_doctor_not_blank | insert into lab_report (id, patient_id, doctor_name, sample_type, created_at) values (2, 1, '', 'BLOOD', now())
            ck_lab_report_sample_type      | insert into lab_report (id, patient_id, doctor_name, sample_type, created_at) values (2, 1, 'Dr. X', 'SWEAT', now())
            fk_lab_report_patient          | insert into lab_report (id, patient_id, doctor_name, sample_type, created_at) values (2, 999, 'Dr. X', 'BLOOD', now())
            ck_lab_test_item_result_finite | insert into lab_test_item (id, report_id, test_code, test_name, result_value, unit) values (1, 1, 'T01', 'Test', 'NaN'::float8, 'u')
            ck_lab_test_item_result_finite | insert into lab_test_item (id, report_id, test_code, test_name, result_value, unit) values (1, 1, 'T01', 'Test', 'Infinity'::float8, 'u')
            ck_lab_test_item_result_finite | insert into lab_test_item (id, report_id, test_code, test_name, result_value, unit) values (1, 1, 'T01', 'Test', '-Infinity'::float8, 'u')
            fk_lab_test_item_report        | insert into lab_test_item (id, report_id, test_code, test_name, result_value, unit) values (1, 999, 'T01', 'Test', 1.0, 'u')
            ck_dataset_meta_checksum_hex   | insert into dataset_meta (name, generator_version, seed, patient_count, report_count, item_count, checksum_sha256) values ('x', 1, 1, 1, 1, 1, repeat('G', 64))
            """)
    void constraintRejects(String constraint, String sql) {
        assertViolates(constraint, () -> jdbc.update(sql));
    }

    @Test
    void itemTestCodeIsUniquePerReport() {
        jdbc.update("insert into lab_test_item (id, report_id, test_code, test_name, result_value, unit)"
                + " values (1, 1, 'T01', 'Test', 1.0, 'u')");
        assertViolates("uq_lab_test_item_report_test", () -> jdbc.update("insert into lab_test_item"
                + " (id, report_id, test_code, test_name, result_value, unit) values (2, 1, 'T01', 'Test', 2.0, 'u')"));
    }

    @Test
    void deletingAPatientWithReportsIsRestricted() {
        assertViolates("fk_lab_report_patient", () -> jdbc.update("delete from patient"));
    }

    @Test
    void deletingAReportWithItemsIsRestricted() {
        jdbc.update("insert into lab_test_item (id, report_id, test_code, test_name, result_value, unit)"
                + " values (1, 1, 'T01', 'Test', 1.0, 'u')");
        assertViolates("fk_lab_test_item_report", () -> jdbc.update("delete from lab_report"));
    }

    /** Positive control for the RESTRICT tests: without dependants the same deletes succeed. */
    @Test
    void deletesWithoutDependantsSucceed() {
        assertThat(jdbc.update("delete from lab_report")).isEqualTo(1);
        assertThat(jdbc.update("delete from patient")).isEqualTo(1);
    }

    /** Positive control for the CHECKs: a valid row passes, and identity BY DEFAULT generates ids. */
    @Test
    void validRowsAreAcceptedAndIdsGenerated() {
        Long id = jdbc.queryForObject("insert into lab_test_item (report_id, test_code, test_name, result_value, unit)"
                + " values (1, 'T01', 'Test', 0.0, 'u') returning id", Long.class);
        assertThat(id).isNotNull().isPositive();
        assertThat(jdbc.queryForObject("select is_critical from lab_test_item where id = ?", Boolean.class, id))
                .isFalse();
    }

    private String indexDef(String name) {
        return jdbc.queryForObject("select indexdef from pg_indexes where schemaname = 'public' and indexname = ?",
                String.class, name);
    }

    private static void assertViolates(String constraint, Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    Throwable t = e;
                    while (t != null && !(t instanceof PSQLException)) {
                        t = t.getCause();
                    }
                    assertThat(t).as("PSQLException in the cause chain").isNotNull();
                    assertThat(((PSQLException) t).getServerErrorMessage()).isNotNull();
                    assertThat(((PSQLException) t).getServerErrorMessage().getConstraint()).isEqualTo(constraint);
                });
    }
}
