package com.benchmark.patient_service.client;

import com.benchmark.patient_service.dto.DashboardResponse;
import com.benchmark.patient_service.dto.LabItemDto;
import com.benchmark.patient_service.dto.LabReportDto;
import com.benchmark.patient_service.dto.PatientDto;
import com.benchmark.proto.LabReport;
import com.benchmark.proto.LabServiceGrpc;
import com.benchmark.proto.Patient;
import com.benchmark.proto.PatientLabHistoryResponse;
import com.benchmark.proto.PatientRequest;
import com.benchmark.proto.PatientSummaryResponse;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class GrpcLabClient implements LabClient {

    private final LabServiceGrpc.LabServiceBlockingStub stub;

    public GrpcLabClient(LabServiceGrpc.LabServiceBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    public String protocol() {
        return "grpc";
    }

    @Override
    public DashboardResponse fetch(Scenario scenario, long patientId) {
        PatientRequest request = PatientRequest.newBuilder().setPatientId(patientId).build();
        LabServiceGrpc.LabServiceBlockingStub call = stub.withDeadlineAfter(30, TimeUnit.SECONDS);

        long start = System.nanoTime();
        if (scenario == Scenario.S1) {
            PatientSummaryResponse r = call.getPatientSummary(request);
            double ms = (System.nanoTime() - start) / 1_000_000.0;
            List<LabReport> reports = r.hasLatestReport() ? List.of(r.getLatestReport()) : List.of();
            return response(scenario, ms, r.getSerializedSize(), r.getPatient(), reports);
        }

        PatientLabHistoryResponse r = call.getPatientLabHistory(request);
        double ms = (System.nanoTime() - start) / 1_000_000.0;
        return response(scenario, ms, r.getSerializedSize(), r.getPatient(), r.getReportsList());
    }

    private DashboardResponse response(Scenario scenario, double ms, int bytes, Patient p, List<LabReport> reports) {
        PatientDto patient = new PatientDto(p.getId(), p.getTcNo(), p.getFullName(), p.getBirthDate(), p.getBloodType());
        List<LabReportDto> dtos = reports.stream().map(GrpcLabClient::report).toList();
        return new DashboardResponse(protocol(), scenario.name(), ms, bytes, "protobuf-serialized-size", patient, dtos);
    }

    private static LabReportDto report(LabReport r) {
        List<LabItemDto> items = r.getItemsList().stream()
                .map(i -> new LabItemDto(i.getId(), i.getTestCode(), i.getTestName(),
                        i.getResultValue(), i.getUnit(), i.getIsCritical()))
                .toList();
        Instant createdAt = Instant.ofEpochSecond(r.getCreatedAt().getSeconds(), r.getCreatedAt().getNanos());
        return new LabReportDto(r.getId(), r.getDoctorName(), r.getSampleType(), createdAt.toString(), items);
    }
}