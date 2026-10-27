package com.benchmark.lab_result_service.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CoreApiTest {

    private static final LocalDate BIRTH = LocalDate.of(1980, 1, 2);
    private static final Instant CREATED = Instant.parse("2026-01-01T10:00:00Z");

    private static PatientView patient() {
        return new PatientView(3, "10000000146", "Ada Yilmaz", BIRTH, "A+");
    }

    private static LabTestItemView item(long id) {
        return new LabTestItemView(id, "GLU", "Glucose", 5.4, "mmol/L", false);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void patientViewRejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> new PatientView(1, null, "n", BIRTH, "A+"));
        assertThatNullPointerException().isThrownBy(() -> new PatientView(1, "t", null, BIRTH, "A+"));
        assertThatNullPointerException().isThrownBy(() -> new PatientView(1, "t", "n", null, "A+"));
        assertThatNullPointerException().isThrownBy(() -> new PatientView(1, "t", "n", BIRTH, null));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void labTestItemViewRejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> new LabTestItemView(1, null, "n", 1.0, "u", false));
        assertThatNullPointerException().isThrownBy(() -> new LabTestItemView(1, "c", null, 1.0, "u", false));
        assertThatNullPointerException().isThrownBy(() -> new LabTestItemView(1, "c", "n", 1.0, null, false));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void labReportViewRejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> new LabReportView(1, null, "BLOOD", CREATED, List.of()));
        assertThatNullPointerException().isThrownBy(() -> new LabReportView(1, "Dr", null, CREATED, List.of()));
        assertThatNullPointerException().isThrownBy(() -> new LabReportView(1, "Dr", "BLOOD", null, List.of()));
        assertThatNullPointerException().isThrownBy(() -> new LabReportView(1, "Dr", "BLOOD", CREATED, null));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void aggregateViewsRejectNulls() {
        assertThatNullPointerException().isThrownBy(() -> new PatientSummaryView(null, null));
        assertThatNullPointerException().isThrownBy(() -> new PatientLabHistoryView(null, List.of()));
        assertThatNullPointerException().isThrownBy(() -> new PatientLabHistoryView(patient(), null));
    }

    @Test
    void summaryAllowsNullLatestReport() {
        assertThat(new PatientSummaryView(patient(), null).latestReport()).isNull();
    }

    @Test
    void labReportViewCopiesItems() {
        List<LabTestItemView> source = new ArrayList<>(List.of(item(1)));
        LabReportView report = new LabReportView(101, "Dr", "BLOOD", CREATED, source);

        source.add(item(2));

        assertThat(report.items()).containsExactly(item(1));
        assertThatThrownBy(() -> report.items().add(item(3))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void historyViewCopiesReports() {
        LabReportView first = new LabReportView(101, "Dr", "BLOOD", CREATED, List.of());
        List<LabReportView> source = new ArrayList<>(List.of(first));
        PatientLabHistoryView history = new PatientLabHistoryView(patient(), source);

        source.clear();

        assertThat(history.reports()).containsExactly(first);
        assertThatThrownBy(() -> history.reports().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void parseAcceptsNumericId() {
        assertThat(PatientIds.parse("3")).isEqualTo(3L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", "9223372036854775808"})
    void parseRejectsMalformedId(String raw) {
        assertThatThrownBy(() -> PatientIds.parse(raw))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> {
                    assertThat(e.field()).isEqualTo("patientId");
                    assertThat(e.reason()).isEqualTo("must be a positive 64-bit integer");
                    assertThat(e.getMessage()).isEqualTo("patientId must be a positive 64-bit integer");
                });
    }

    @Test
    void exceptionCodes() {
        assertThat(new InvalidRequestException("patientId", "x").code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(new PatientNotFoundException(1001).code()).isEqualTo(ErrorCode.PATIENT_NOT_FOUND);
        assertThat(new ServiceUnavailableException(new RuntimeException("db")).code()).isEqualTo(ErrorCode.UNAVAILABLE);
    }

    @Test
    void exceptionMessages() {
        assertThat(new PatientNotFoundException(1001).getMessage()).isEqualTo("Patient 1001 not found");
        assertThat(new PatientNotFoundException(1001).patientId()).isEqualTo(1001L);
        assertThat(new ServiceUnavailableException(new RuntimeException("db")).getMessage())
                .isEqualTo("Service temporarily unavailable");
    }

    @Test
    void expectedExceptionsHaveEmptyStackTrace() {
        assertThat(new InvalidRequestException("patientId", "x").getStackTrace()).isEmpty();
        assertThat(new PatientNotFoundException(1001).getStackTrace()).isEmpty();
    }

    @Test
    void unavailableKeepsStackTraceAndCause() {
        RuntimeException cause = new RuntimeException("db");
        ServiceUnavailableException e = new ServiceUnavailableException(cause);
        assertThat(e.getStackTrace()).isNotEmpty();
        assertThat(e.getCause()).isSameAs(cause);
    }
}
