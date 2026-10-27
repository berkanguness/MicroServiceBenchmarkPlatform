package com.benchmark.lab_result_service.core.api;

import java.util.Objects;

public record LabTestItemView(long id, String testCode, String testName, double resultValue, String unit, boolean isCritical) {
    public LabTestItemView {
        Objects.requireNonNull(testCode); Objects.requireNonNull(testName); Objects.requireNonNull(unit);
    }
}
