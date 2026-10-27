package com.benchmark.lab_result_service.core.api;

public final class ServiceUnavailableException extends LabResultException {
    public ServiceUnavailableException(Throwable cause) {                  // message: "Service temporarily unavailable"
        super("Service temporarily unavailable", cause, true);
    }
    @Override public ErrorCode code() { return ErrorCode.UNAVAILABLE; }
}
