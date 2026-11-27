package com.benchmark.patient_service.dto;

public record PatientDto(
        long id,
        String tcNo,
        String fullName,
        String birthDate,
        String bloodType

){}
