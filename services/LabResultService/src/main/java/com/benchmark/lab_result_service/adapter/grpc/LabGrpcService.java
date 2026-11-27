package com.benchmark.lab_result_service.adapter.grpc;

import com.benchmark.lab_result_service.core.api.enums.ErrorCode;
import com.benchmark.lab_result_service.core.api.enums.Protocol;
import com.benchmark.lab_result_service.core.api.exception.InvalidRequestException;
import com.benchmark.lab_result_service.core.api.exception.LabResultException;
import com.benchmark.lab_result_service.core.api.exception.PatientNotFoundException;
import com.benchmark.lab_result_service.core.api.exception.ServiceUnavailableException;
import com.benchmark.lab_result_service.core.api.service.LabErrorRecorder;
import com.benchmark.lab_result_service.core.api.service.LabResultQueryService;
import com.benchmark.proto.LabServiceGrpc;
import com.benchmark.proto.Patient;
import com.benchmark.proto.PatientLabHistoryResponse;
import com.benchmark.proto.PatientRequest;
import com.benchmark.proto.PatientSummaryResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;


@Service
public class LabGrpcService extends LabServiceGrpc.LabServiceImplBase {

    static final String UNAVAILABLE_MESSAGE = "Service temporarily unavailable";
    static final String INTERNAL_MESSAGE = "Internal error";

    private final LabResultQueryService service;
    private final LabErrorRecorder recorder;

    public LabGrpcService(LabResultQueryService service, LabErrorRecorder recorder) {
        this.service = service;
        this.recorder = recorder;
    }

    @Override
    public void getPatient(PatientRequest request, StreamObserver<Patient> out) {
        call(out, () -> GrpcMapper.patient(service.getPatient(request.getPatientId())));
    }

    @Override
    public void getPatientSummary(PatientRequest request, StreamObserver<PatientSummaryResponse> out) {
        call(out, () -> GrpcMapper.summary(service.getPatientSummary(request.getPatientId())));
    }

    @Override
    public void getPatientLabHistory(PatientRequest request, StreamObserver<PatientLabHistoryResponse> out) {
        call(out, () -> GrpcMapper.history(service.getPatientLabHistory(request.getPatientId())));
    }

    private <T> void call(StreamObserver<T> out, Supplier<T> body) {
        T response;
        try {
            response = body.get();
        } catch (LabResultException e) {
            recorder.record(Protocol.GRPC, e.code(), e);
            out.onError(status(e).asRuntimeException());
            return;
        } catch (RuntimeException e) {
            recorder.record(Protocol.GRPC, ErrorCode.INTERNAL, e);
            out.onError(Status.INTERNAL.withDescription(INTERNAL_MESSAGE).asRuntimeException());
            return;
        }

        if (out instanceof ServerCallStreamObserver<T> call && call.isCancelled()) {
            recorder.record(Protocol.GRPC, ErrorCode.CLIENT_ABORTED, null);
            return;
        }

        try {
            out.onNext(response);
            out.onCompleted();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.CANCELLED) {
                recorder.record(Protocol.GRPC, ErrorCode.CLIENT_ABORTED, e);
            } else {
                throw e;
            }
        }
    }

    private static Status status(LabResultException e) {
        return switch (e) {
            case InvalidRequestException x -> Status.INVALID_ARGUMENT.withDescription(x.getMessage());
            case PatientNotFoundException x -> Status.NOT_FOUND.withDescription(x.getMessage());
            case ServiceUnavailableException x -> Status.UNAVAILABLE.withDescription(UNAVAILABLE_MESSAGE);
        };
    }
}