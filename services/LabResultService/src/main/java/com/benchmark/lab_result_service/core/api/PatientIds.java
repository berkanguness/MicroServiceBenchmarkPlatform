package com.benchmark.lab_result_service.core.api;

/** Parses the textual ids of REST path variables and GraphQL ID arguments, with the same rule for both protocols. */
public final class PatientIds {
    private PatientIds() {}
    /** Long.parseLong(raw); on NumberFormatException (non-numeric, empty, overflow) throws
     *  InvalidRequestException("patientId", "must be a positive 64-bit integer").
     *  It does not check the sign: the service rejects <= 0 with the same field and reason. */
    public static long parse(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("patientId", "must be a positive 64-bit integer");
        }
    }
}
