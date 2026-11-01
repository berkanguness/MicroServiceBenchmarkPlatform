package com.benchmark.lab_result_service.core.api;

import org.jspecify.annotations.Nullable;

public sealed abstract class LabResultException extends RuntimeException
        permits InvalidRequestException, PatientNotFoundException, ServiceUnavailableException {
    protected LabResultException(String message, @Nullable Throwable cause, boolean writableStackTrace) {
        super(message, cause, false, writableStackTrace);
    }
    public abstract ErrorCode code();
}
