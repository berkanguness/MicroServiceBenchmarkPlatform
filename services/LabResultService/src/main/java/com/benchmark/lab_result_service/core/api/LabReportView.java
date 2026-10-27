package com.benchmark.lab_result_service.core.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record LabReportView(long id, String doctorName, String sampleType, Instant createdAt, List<LabTestItemView> items) {
    public LabReportView {
        Objects.requireNonNull(doctorName); Objects.requireNonNull(sampleType); Objects.requireNonNull(createdAt);
        items = List.copyOf(items);                  // immutable, ordered by item id ASC
    }
}
