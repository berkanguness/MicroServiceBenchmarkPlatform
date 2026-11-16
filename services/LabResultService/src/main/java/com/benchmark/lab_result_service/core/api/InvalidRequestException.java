package com.benchmark.lab_result_service.core.api;

public final class InvalidRequestException extends LabResultException {
    private final String field;  private final String reason;
    public InvalidRequestException(String field, String reason) {          // message: field + " " + reason
        super(field + " " + reason, null, false);                           // expected error: no stack trace
        this.field = field; this.reason = reason;
    }
    public String field()  { return field; }
    public String reason() { return reason; }
    @Override public ErrorCode code() { return ErrorCode.INVALID_ARGUMENT; }
}
