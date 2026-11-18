package com.benchmark.lab_result_service.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.benchmark.lab_result_service.core.api.dto.LabReportView;
import com.benchmark.lab_result_service.core.api.dto.LabTestItemView;
import com.benchmark.lab_result_service.core.api.dto.PatientLabHistoryView;
import com.benchmark.lab_result_service.core.api.dto.PatientSummaryView;
import com.benchmark.lab_result_service.core.api.exception.InvalidRequestException;
import com.benchmark.lab_result_service.core.api.exception.PatientNotFoundException;
import com.benchmark.lab_result_service.core.api.service.LabResultQueryService;
import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.util.Comparator;
import java.util.List;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Contract §10 P3 {@code LabResultQueryServiceIT} on the real seeded dataset (P2 seeder, {@code seed} profile, cheap
 * verification): patient 1 history = 100 × 10; patient 3 summary = report 101 with 10 items; patient 2 empty; 1001
 * not found. Statement counts per §5.3 are asserted through the real service, and the service timer is checked.
 */
@ActiveProfiles(value = {"test", "seed"}, inheritProfiles = false)
@TestPropertySource(properties = "app.dataset.verify-mode=cheap")
class LabResultQueryServiceIT extends AbstractPostgresIT {

    @Autowired LabResultQueryService service;
    @Autowired EntityManagerFactory emf;
    @Autowired MeterRegistry registry;
    @Autowired DataSource dataSource;

    private Statistics stats;

    @BeforeEach
    void statistics() {
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        assertThat(stats.isStatisticsEnabled()).as("hibernate.generate_statistics in the test profile").isTrue();
    }

    @Test
    void theServiceBeanIsTheSharedImplementation() {
        assertThat(service).isInstanceOf(DefaultLabResultQueryService.class);
    }

    /** Chaos F1: the default in-flight bound reaches the driver also when @ServiceConnection supplies the URL. */
    @Test
    void jdbcSocketTimeoutDefaultsToFiveSeconds() throws Exception {
        assertThat(dataSource.unwrap(HikariDataSource.class).getDataSourceProperties().getProperty("socketTimeout"))
                .isEqualTo("5");
    }

    @Test
    void patientOneHistoryIsOneHundredReportsOfTenItems() {
        long before = stats.getPrepareStatementCount();
        PatientLabHistoryView history = service.getPatientLabHistory(1);
        assertThat(stats.getPrepareStatementCount() - before).as("§5.3 statements").isEqualTo(3);

        assertThat(history.patient().id()).isEqualTo(1);
        assertThat(history.reports()).hasSize(100).allSatisfy(r -> assertThat(r.items()).hasSize(10));
        assertThat(history.reports()).extracting(LabReportView::id)
                .containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 100).boxed().toList());
        assertThat(history.reports()).isSortedAccordingTo(
                Comparator.comparing(LabReportView::createdAt).thenComparing(LabReportView::id).reversed());
        history.reports().forEach(r -> assertThat(r.items()).extracting(LabTestItemView::id).isSorted());
        List<Long> allItemIds = history.reports().stream().flatMap(r -> r.items().stream()).map(LabTestItemView::id).toList();
        assertThat(allItemIds).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 1000).boxed().toList());
    }

    @Test
    void patientThreeSummaryIsReport101WithTenItems() {
        long before = stats.getPrepareStatementCount();
        PatientSummaryView summary = service.getPatientSummary(3);
        assertThat(stats.getPrepareStatementCount() - before).as("§5.3 statements").isEqualTo(3);

        assertThat(summary.patient().id()).isEqualTo(3);
        assertThat(summary.latestReport()).isNotNull();
        assertThat(summary.latestReport().id()).isEqualTo(101);
        assertThat(summary.latestReport().items()).extracting(LabTestItemView::id)
                .containsExactlyElementsOf(LongStream.rangeClosed(1001, 1010).boxed().toList());
    }

    @Test
    void patientTwoIsEmpty() {
        long before = stats.getPrepareStatementCount();
        assertThat(service.getPatientLabHistory(2).reports()).isEmpty();
        assertThat(service.getPatientSummary(2).latestReport()).isNull();
        assertThat(service.getPatient(2).id()).isEqualTo(2);
        assertThat(stats.getPrepareStatementCount() - before).as("§5.3 statements: 2 + 2 + 1").isEqualTo(5);
    }

    @Test
    void absentAndInvalidIds() {
        long before = stats.getPrepareStatementCount();
        assertThatThrownBy(() -> service.getPatientLabHistory(1001)).isInstanceOf(PatientNotFoundException.class);
        assertThatThrownBy(() -> service.getPatientSummary(1001)).isInstanceOf(PatientNotFoundException.class);
        assertThatThrownBy(() -> service.getPatient(1001)).isInstanceOf(PatientNotFoundException.class);
        assertThat(stats.getPrepareStatementCount() - before).as("one statement per 404").isEqualTo(3);

        before = stats.getPrepareStatementCount();
        assertThatThrownBy(() -> service.getPatient(0)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.getPatientLabHistory(-1)).isInstanceOf(InvalidRequestException.class);
        assertThat(stats.getPrepareStatementCount() - before).as("no statement for a 400").isZero();
    }

    @Test
    void serviceTimerIsRecordedWithMethodAndOutcome() {
        double before = count("getPatient", "SUCCESS");
        double notFoundBefore = count("getPatient", "PATIENT_NOT_FOUND");

        service.getPatient(5);
        assertThatThrownBy(() -> service.getPatient(1001)).isInstanceOf(PatientNotFoundException.class);

        assertThat(count("getPatient", "SUCCESS") - before).isEqualTo(1);
        assertThat(count("getPatient", "PATIENT_NOT_FOUND") - notFoundBefore).isEqualTo(1);
    }

    private double count(String method, String outcome) {
        return registry.get("lab.service.calls").tag("method", method).tag("outcome", outcome).timer().count();
    }
}
