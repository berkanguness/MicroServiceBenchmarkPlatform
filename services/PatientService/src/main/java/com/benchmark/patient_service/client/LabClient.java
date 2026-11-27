package com.benchmark.patient_service.client;

import com.benchmark.patient_service.dto.DashboardResponse;

public interface LabClient
{
    String protocol();

    DashboardResponse fetch(Scenario scenario, long patientId);
}
