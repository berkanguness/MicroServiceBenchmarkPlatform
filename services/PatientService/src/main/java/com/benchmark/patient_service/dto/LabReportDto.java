package com.benchmark.patient_service.dto;

import java.util.List;

public record LabReportDto(
        long id,
        String doctorName,
        String sampleType,
        String createdAt,
        List<LabItemDto> items
){}
