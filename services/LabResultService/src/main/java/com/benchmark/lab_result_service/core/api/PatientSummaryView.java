package com.benchmark.lab_result_service.core.api;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Scenario 1: patient + latest report (by createdAt DESC, id DESC) with its items; latestReport is null when the patient has no reports. */
public record PatientSummaryView(PatientView patient, @Nullable LabReportView latestReport) {
    public PatientSummaryView { Objects.requireNonNull(patient); }
}
