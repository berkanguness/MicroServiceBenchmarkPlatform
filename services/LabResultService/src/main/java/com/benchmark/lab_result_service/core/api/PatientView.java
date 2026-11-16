package com.benchmark.lab_result_service.core.api;

import java.time.LocalDate;
import java.util.Objects;

public record PatientView(long id, String tcNo, String fullName, LocalDate birthDate, String bloodType) {
    public PatientView {
        Objects.requireNonNull(tcNo); Objects.requireNonNull(fullName);
        Objects.requireNonNull(birthDate); Objects.requireNonNull(bloodType);
    }
}
