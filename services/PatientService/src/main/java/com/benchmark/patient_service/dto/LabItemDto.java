package com.benchmark.patient_service.dto;

public record LabItemDto (
        long id,
        String testCode,
        String testName,
        double resultValue,
        String unit,
        boolean critical
){}
