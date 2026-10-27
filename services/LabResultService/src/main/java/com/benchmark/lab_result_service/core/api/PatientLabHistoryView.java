package com.benchmark.lab_result_service.core.api;

import java.util.List;
import java.util.Objects;

/** Scenario 2: patient + every report (createdAt DESC, id DESC), each with its items (id ASC). Reports without items are kept with an empty list. */
public record PatientLabHistoryView(PatientView patient, List<LabReportView> reports) {
    public PatientLabHistoryView { Objects.requireNonNull(patient); reports = List.copyOf(reports); }
}
