package com.benchmark.lab_result_service.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.benchmark.lab_result_service.core.api.dto.LabReportView;
import com.benchmark.lab_result_service.core.api.dto.LabTestItemView;
import com.benchmark.lab_result_service.core.api.dto.PatientLabHistoryView;
import com.benchmark.lab_result_service.core.api.dto.PatientSummaryView;
import com.benchmark.lab_result_service.core.api.dto.PatientView;
import com.benchmark.lab_result_service.core.api.exception.InvalidRequestException;
import com.benchmark.lab_result_service.core.api.exception.PatientNotFoundException;
import com.benchmark.lab_result_service.core.api.exception.ServiceUnavailableException;
import com.benchmark.lab_result_service.persistence.repository.LabReadRepository;
import com.benchmark.lab_result_service.persistence.row.ItemRow;
import com.benchmark.lab_result_service.persistence.row.ReportRow;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Contract §10 P3 {@code DefaultLabResultQueryServiceTest}: mocked repository and transaction manager. */
class DefaultLabResultQueryServiceTest {

    private static final PatientView PATIENT = new PatientView(1, "10000000146", "Ada Yilmaz", LocalDate.of(1980, 1, 2), "A+");
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private LabReadRepository repository;
    private PlatformTransactionManager txManager;
    private SimpleMeterRegistry registry;
    private DefaultLabResultQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(LabReadRepository.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        registry = new SimpleMeterRegistry();
        service = new DefaultLabResultQueryService(repository, new TransactionTemplate(txManager), registry);
    }

    private static ReportRow report(long id, int minutesAgo) {
        return new ReportRow(id, "Dr " + id, "BLOOD", T0.minusSeconds(60L * minutesAgo));
    }

    private static ItemRow item(long reportId, long id) {
        return new ItemRow(reportId, id, "T" + id, "Test " + id, id + 0.5, "u", id % 2 == 0);
    }

    // ------------------------------------------------------------------------------------------------ happy paths

    @Test
    void getPatientReturnsTheProjection() {
        when(repository.findPatient(1)).thenReturn(Optional.of(PATIENT));

        assertThat(service.getPatient(1)).isEqualTo(PATIENT);
        verify(txManager).commit(any());
        assertOneCall("getPatient", "SUCCESS");
    }

    @Test
    void historyGroupsItemsByReportInSqlOrderAndKeepsReportsWithoutItems() {
        // SQL order: reports createdAt DESC, id DESC; items report_id, id
        List<ReportRow> reports = List.of(report(7, 0), report(9, 5), report(8, 5));
        when(repository.findPatient(1)).thenReturn(Optional.of(PATIENT));
        when(repository.findReports(1)).thenReturn(reports);
        when(repository.findItems(List.of(7L, 9L, 8L)))
                .thenReturn(List.of(item(7, 70), item(7, 71), item(8, 80), item(8, 81)));

        PatientLabHistoryView history = service.getPatientLabHistory(1);

        assertThat(history.patient()).isEqualTo(PATIENT);
        assertThat(history.reports()).extracting(LabReportView::id).containsExactly(7L, 9L, 8L);
        assertThat(history.reports().get(0).items()).extracting(LabTestItemView::id).containsExactly(70L, 71L);
        assertThat(history.reports().get(1).items()).isEmpty();
        assertThat(history.reports().get(2).items()).extracting(LabTestItemView::id).containsExactly(80L, 81L);
        LabTestItemView first = history.reports().get(0).items().get(0);
        assertThat(first).isEqualTo(new LabTestItemView(70, "T70", "Test 70", 70.5, "u", true));
        LabReportView firstReport = history.reports().get(0);
        assertThat(firstReport.doctorName()).isEqualTo("Dr 7");
        assertThat(firstReport.createdAt()).isEqualTo(T0);
        assertOneCall("getPatientLabHistory", "SUCCESS");
    }

    @Test
    void emptyHistoryRunsNoItemQuery() {
        when(repository.findPatient(2)).thenReturn(Optional.of(PATIENT));
        when(repository.findReports(2)).thenReturn(List.of());

        assertThat(service.getPatientLabHistory(2).reports()).isEmpty();
        verify(repository, never()).findItems(any());
    }

    @Test
    void summaryAssemblesTheLatestReportWithItsItems() {
        when(repository.findPatient(3)).thenReturn(Optional.of(PATIENT));
        when(repository.findLatestReport(3)).thenReturn(Optional.of(report(101, 0)));
        when(repository.findItemsOfReport(101)).thenReturn(List.of(item(101, 1001), item(101, 1002)));

        PatientSummaryView summary = service.getPatientSummary(3);

        assertThat(summary.latestReport()).isNotNull();
        assertThat(summary.latestReport().id()).isEqualTo(101);
        assertThat(summary.latestReport().items()).extracting(LabTestItemView::id).containsExactly(1001L, 1002L);
        InOrder order = inOrder(repository);
        order.verify(repository).findPatient(3);
        order.verify(repository).findLatestReport(3);
        order.verify(repository).findItemsOfReport(101);
        assertOneCall("getPatientSummary", "SUCCESS");
    }

    @Test
    void summaryWithoutReportsHasNullLatestReport() {
        when(repository.findPatient(2)).thenReturn(Optional.of(PATIENT));
        when(repository.findLatestReport(2)).thenReturn(Optional.empty());

        assertThat(service.getPatientSummary(2).latestReport()).isNull();
        verify(repository, never()).findItemsOfReport(anyLong());
    }

    // ------------------------------------------------------------------------------------------------ 400 / 404

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void nonPositiveIdIsRejectedWithoutTouchingTheDatabase(long id) {
        Consumer<Throwable> invalid = e -> assertThat(e).isInstanceOfSatisfying(InvalidRequestException.class, ire -> {
            assertThat(ire.field()).isEqualTo("patientId");
            assertThat(ire.reason()).isEqualTo("must be a positive 64-bit integer");
        });
        assertThatThrownBy(() -> service.getPatient(id)).satisfies(invalid);
        assertThatThrownBy(() -> service.getPatientSummary(id)).satisfies(invalid);
        assertThatThrownBy(() -> service.getPatientLabHistory(id)).satisfies(invalid);

        verifyNoInteractions(repository, txManager);
        assertCount("getPatient", "INVALID_ARGUMENT", 1);
        assertCount("getPatientSummary", "INVALID_ARGUMENT", 1);
        assertCount("getPatientLabHistory", "INVALID_ARGUMENT", 1);
    }

    @Test
    void absentPatientIsNotFoundAfterOneQueryAndTheTransactionCommits() {
        when(repository.findPatient(1001)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPatient(1001)).isInstanceOf(PatientNotFoundException.class);
        assertThatThrownBy(() -> service.getPatientSummary(1001)).isInstanceOf(PatientNotFoundException.class);
        assertThatThrownBy(() -> service.getPatientLabHistory(1001))
                .isInstanceOfSatisfying(PatientNotFoundException.class, e -> assertThat(e.patientId()).isEqualTo(1001));

        verify(repository, never()).findLatestReport(anyLong());
        verify(repository, never()).findReports(anyLong());
        verify(txManager, never()).rollback(any());
        assertCount("getPatient", "PATIENT_NOT_FOUND", 1);
        assertCount("getPatientSummary", "PATIENT_NOT_FOUND", 1);
        assertCount("getPatientLabHistory", "PATIENT_NOT_FOUND", 1);
    }

    // ------------------------------------------------------------------------------------------------ 503 / 500

    @Test
    void transactionThatCannotBeginIsUnavailable() {
        var cause = new CannotCreateTransactionException("Could not open JPA EntityManager for transaction");
        when(txManager.getTransaction(any())).thenThrow(cause);

        assertThatThrownBy(() -> service.getPatientSummary(3))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, e -> assertThat(e.getCause()).isSameAs(cause));
        verifyNoInteractions(repository);
        assertOneCall("getPatientSummary", "UNAVAILABLE");
    }

    @Test
    void connectionFailureInsideTheTransactionIsUnavailable() {
        var cause = new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection");
        when(repository.findPatient(1)).thenThrow(cause);

        assertThatThrownBy(() -> service.getPatient(1))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, e -> assertThat(e.getCause()).isSameAs(cause));
        verify(txManager).rollback(any());
        assertOneCall("getPatient", "UNAVAILABLE");
    }

    @Test
    void otherDataAccessExceptionsPropagateUnchanged() {
        var grammar = new InvalidDataAccessResourceUsageException("bad SQL");
        when(repository.findPatient(1)).thenThrow(grammar);
        assertThatThrownBy(() -> service.getPatient(1)).isSameAs(grammar);

        var integrity = new DataIntegrityViolationException("constraint");
        when(repository.findPatient(2)).thenThrow(integrity);
        assertThatThrownBy(() -> service.getPatientLabHistory(2)).isSameAs(integrity);

        assertCount("getPatient", "INTERNAL", 1);
        assertCount("getPatientLabHistory", "INTERNAL", 1);
    }

    @Test
    void unexpectedRuntimeExceptionIsInternalAndTimedOnce() {
        var bug = new IllegalStateException("bug");
        when(repository.findPatient(3)).thenReturn(Optional.of(PATIENT));
        when(repository.findLatestReport(3)).thenThrow(bug);

        assertThatThrownBy(() -> service.getPatientSummary(3)).isSameAs(bug);
        assertOneCall("getPatientSummary", "INTERNAL");
    }

    @Test
    void timersArePreRegisteredForEveryMethodAndOutcome() {
        assertThat(registry.find(DefaultLabResultQueryService.TIMER).timers()).hasSize(3 * 5);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Exactly one sample across all 15 timers, and it is the expected (method, outcome). */
    private void assertOneCall(String method, String outcome) {
        long total = registry.find(DefaultLabResultQueryService.TIMER).timers().stream().mapToLong(Timer::count).sum();
        assertThat(total).as("samples across all lab.service.calls timers").isEqualTo(1);
        assertCount(method, outcome, 1);
    }

    private void assertCount(String method, String outcome, long expected) {
        Timer timer = registry.get(DefaultLabResultQueryService.TIMER).tag("method", method).tag("outcome", outcome).timer();
        assertThat(timer.count()).as("lab.service.calls{method=%s,outcome=%s}", method, outcome).isEqualTo(expected);
    }
}
