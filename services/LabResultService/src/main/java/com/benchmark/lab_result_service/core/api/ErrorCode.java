package com.benchmark.lab_result_service.core.api;

public enum ErrorCode {
    INVALID_ARGUMENT,     // 400 / BAD_REQUEST / INVALID_ARGUMENT
    PATIENT_NOT_FOUND,    // 404 / NOT_FOUND   / NOT_FOUND
    UNAVAILABLE,          // 503 / INTERNAL_ERROR + code / UNAVAILABLE
    INTERNAL,             // 500 / INTERNAL_ERROR / INTERNAL
    CLIENT_ABORTED        // metrics/log tag only; never sent to a client
}
