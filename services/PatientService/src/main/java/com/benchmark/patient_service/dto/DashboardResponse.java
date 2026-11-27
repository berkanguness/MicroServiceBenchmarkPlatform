package com.benchmark.patient_service.dto;

import java.util.List;

public record DashboardResponse(
        String protocol,
        String scenario,
        double hopLatencyMs,
        long payloadBytes,
        String payloadMeasure,
        PatientDto patient,
        List<LabReportDto> reports
)
{}
