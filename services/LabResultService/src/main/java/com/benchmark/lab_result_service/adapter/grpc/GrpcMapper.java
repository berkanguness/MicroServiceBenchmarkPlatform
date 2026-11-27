package com.benchmark.lab_result_service.adapter.grpc;

import com.benchmark.lab_result_service.core.api.dto.LabReportView;
import com.benchmark.lab_result_service.core.api.dto.LabTestItemView;
import com.benchmark.lab_result_service.core.api.dto.PatientLabHistoryView;
import com.benchmark.lab_result_service.core.api.dto.PatientSummaryView;
import com.benchmark.lab_result_service.core.api.dto.PatientView;
import com.benchmark.proto.LabItemResponse;
import com.benchmark.proto.LabReport;
import com.benchmark.proto.Patient;
import com.benchmark.proto.PatientLabHistoryResponse;
import com.benchmark.proto.PatientSummaryResponse;
import com.google.protobuf.Timestamp;


final class GrpcMapper
{
    private GrpcMapper() {}

    static Patient patient(PatientView p) {
        return Patient.newBuilder()
                .setId(p.id())
                .setTcNo(p.tcNo())
                .setFullName(p.fullName())
                .setBirthDate(p.birthDate().toString())
                .setBloodType(p.bloodType())
                .build();
    }

    static LabItemResponse item(LabTestItemView i) {
        return LabItemResponse.newBuilder()
                .setId(i.id())
                .setTestCode(i.testCode())
                .setTestName(i.testName())
                .setResultValue(i.resultValue())
                .setUnit(i.unit())
                .setIsCritical(i.isCritical())
                .build();
    }

    static LabReport report(LabReportView r) {
        LabReport.Builder b = LabReport.newBuilder()
                .setId(r.id())
                .setDoctorName(r.doctorName())
                .setSampleType(r.sampleType())
                .setCreatedAt(Timestamp.newBuilder()
                        .setSeconds(r.createdAt().getEpochSecond())
                        .setNanos(r.createdAt().getNano()));
        r.items().forEach(i -> b.addItems(item(i)));
        return b.build();
    }

    static PatientSummaryResponse summary(PatientSummaryView v) {
        PatientSummaryResponse.Builder b = PatientSummaryResponse.newBuilder()
                .setPatient(patient(v.patient()));
        if (v.latestReport() != null) {
            b.setLatestReport(report(v.latestReport()));
        }
        return b.build();
    }

    static PatientLabHistoryResponse history(PatientLabHistoryView v) {
        PatientLabHistoryResponse.Builder b = PatientLabHistoryResponse.newBuilder()
                .setPatient(patient(v.patient()));
        v.reports().forEach(r -> b.addReports(report(r)));
        return b.build();
    }
}
