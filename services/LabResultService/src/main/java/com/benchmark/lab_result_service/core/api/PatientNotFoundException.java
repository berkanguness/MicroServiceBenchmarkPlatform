package com.benchmark.lab_result_service.core.api;

public final class PatientNotFoundException extends LabResultException {
    private final long patientId;
    public PatientNotFoundException(long patientId) {                      // message: "Patient 1001 not found"
        super("Patient " + patientId + " not found", null, false);
        this.patientId = patientId;
    }
    public long patientId() { return patientId; }
    @Override public ErrorCode code() { return ErrorCode.PATIENT_NOT_FOUND; }
}
