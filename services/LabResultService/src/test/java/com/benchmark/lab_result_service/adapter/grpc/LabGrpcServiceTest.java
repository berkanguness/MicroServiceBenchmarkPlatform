package com.benchmark.lab_result_service.adapter.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.benchmark.lab_result_service.core.api.dto.LabReportView;
import com.benchmark.lab_result_service.core.api.dto.LabTestItemView;
import com.benchmark.lab_result_service.core.api.dto.PatientLabHistoryView;
import com.benchmark.lab_result_service.core.api.dto.PatientSummaryView;
import com.benchmark.lab_result_service.core.api.dto.PatientView;
import com.benchmark.lab_result_service.core.api.enums.ErrorCode;
import com.benchmark.lab_result_service.core.api.enums.Protocol;
import com.benchmark.lab_result_service.core.api.exception.InvalidRequestException;
import com.benchmark.lab_result_service.core.api.exception.PatientNotFoundException;
import com.benchmark.lab_result_service.core.api.exception.ServiceUnavailableException;
import com.benchmark.lab_result_service.core.api.service.LabErrorRecorder;
import com.benchmark.lab_result_service.core.api.service.LabResultQueryService;
import com.benchmark.proto.PatientLabHistoryResponse;
import com.benchmark.proto.PatientRequest;
import com.benchmark.proto.PatientSummaryResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LabGrpcServiceTest {

    private static final Instant CREATED = Instant.parse("2025-12-31T06:34:55Z");

    private final LabResultQueryService service = mock(LabResultQueryService.class);
    private final LabErrorRecorder recorder = mock(LabErrorRecorder.class);
    private final LabGrpcService grpc = new LabGrpcService(service, recorder);

    @SuppressWarnings("unchecked")
    private final StreamObserver<PatientLabHistoryResponse> historyOut = mock(StreamObserver.class);

    @SuppressWarnings("unchecked")
    private final StreamObserver<PatientSummaryResponse> summaryOut = mock(StreamObserver.class);

    private static PatientRequest request(long id) {
        return PatientRequest.newBuilder().setPatientId(id).build();
    }

    private static PatientView patient() {
        return new PatientView(1, "11111111111", "Test Hasta", LocalDate.of(1990, 5, 17), "A+");
    }

    private static LabReportView report(long id, int itemCount) {
        List<LabTestItemView> items = new ArrayList<>();
        for (int i = 1; i <= itemCount; i++) {
            items.add(new LabTestItemView(id * 100 + i, "T" + i, "Test " + i, i * 1.5, "mg/dL", i == itemCount));
        }
        return new LabReportView(id, "Dr. Test", "SERUM", CREATED, items);
    }

    private static StatusRuntimeException capturedError(StreamObserver<?> out) {
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(out).onError(captor.capture());
        return (StatusRuntimeException) captor.getValue();
    }

    @Test
    void historyIsMappedFieldByField() {
        when(service.getPatientLabHistory(1))
                .thenReturn(new PatientLabHistoryView(patient(), List.of(report(1, 3), report(2, 3))));

        grpc.getPatientLabHistory(request(1), historyOut);

        ArgumentCaptor<PatientLabHistoryResponse> captor = ArgumentCaptor.forClass(PatientLabHistoryResponse.class);
        verify(historyOut).onNext(captor.capture());
        verify(historyOut).onCompleted();
        PatientLabHistoryResponse r = captor.getValue();

        assertThat(r.getPatient().getFullName()).isEqualTo("Test Hasta");
        assertThat(r.getPatient().getBirthDate()).isEqualTo("1990-05-17");
        assertThat(r.getReportsList()).hasSize(2);
        assertThat(r.getReports(0).getCreatedAt().getSeconds()).isEqualTo(CREATED.getEpochSecond());
        assertThat(r.getReports(0).getItemsList()).hasSize(3);
        assertThat(r.getReports(0).getItems(2).getIsCritical()).isTrue();
        assertThat(r.getReports(0).getItems(0).getIsCritical()).isFalse();

        verify(service).getPatientLabHistory(1);
        verifyNoMoreInteractions(service);
        verifyNoInteractions(recorder);
    }

    @Test
    void summaryWithReportHasLatestReport() {
        when(service.getPatientSummary(3)).thenReturn(new PatientSummaryView(patient(), report(101, 10)));

        grpc.getPatientSummary(request(3), summaryOut);

        ArgumentCaptor<PatientSummaryResponse> captor = ArgumentCaptor.forClass(PatientSummaryResponse.class);
        verify(summaryOut).onNext(captor.capture());
        assertThat(captor.getValue().hasLatestReport()).isTrue();
        assertThat(captor.getValue().getLatestReport().getItemsList()).hasSize(10);
    }

    @Test
    void summaryWithoutReportLeavesLatestReportUnset() {
        when(service.getPatientSummary(3)).thenReturn(new PatientSummaryView(patient(), null));

        grpc.getPatientSummary(request(3), summaryOut);

        ArgumentCaptor<PatientSummaryResponse> captor = ArgumentCaptor.forClass(PatientSummaryResponse.class);
        verify(summaryOut).onNext(captor.capture());
        assertThat(captor.getValue().hasLatestReport()).isFalse();
    }

    @Test
    void unknownPatientIsNotFoundAndRecordedOnce() {
        PatientNotFoundException e = new PatientNotFoundException(1001);
        when(service.getPatientLabHistory(1001)).thenThrow(e);

        grpc.getPatientLabHistory(request(1001), historyOut);

        StatusRuntimeException error = capturedError(historyOut);
        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(error.getStatus().getDescription()).isEqualTo("Patient 1001 not found");
        verify(recorder).record(Protocol.GRPC, ErrorCode.PATIENT_NOT_FOUND, e);
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void invalidRequestIsInvalidArgument() {
        InvalidRequestException e = new InvalidRequestException("patientId", "must be positive");
        when(service.getPatientLabHistory(0)).thenThrow(e);

        grpc.getPatientLabHistory(request(0), historyOut);

        StatusRuntimeException error = capturedError(historyOut);
        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        verify(recorder).record(Protocol.GRPC, ErrorCode.INVALID_ARGUMENT, e);
    }

    @Test
    void unavailableServiceHidesDetails() {
        ServiceUnavailableException e = new ServiceUnavailableException(new RuntimeException("jdbc url secret"));
        when(service.getPatientLabHistory(1)).thenThrow(e);

        grpc.getPatientLabHistory(request(1), historyOut);

        StatusRuntimeException error = capturedError(historyOut);
        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(error.getStatus().getDescription()).isEqualTo("Service temporarily unavailable");
        verify(recorder).record(Protocol.GRPC, ErrorCode.UNAVAILABLE, e);
    }

    @Test
    void unexpectedExceptionIsInternalWithFixedMessage() {
        IllegalStateException e = new IllegalStateException("secret SQL");
        when(service.getPatientLabHistory(3)).thenThrow(e);

        grpc.getPatientLabHistory(request(3), historyOut);

        StatusRuntimeException error = capturedError(historyOut);
        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(error.getStatus().getDescription()).isEqualTo("Internal error");
        verify(recorder).record(Protocol.GRPC, ErrorCode.INTERNAL, e);
    }
}