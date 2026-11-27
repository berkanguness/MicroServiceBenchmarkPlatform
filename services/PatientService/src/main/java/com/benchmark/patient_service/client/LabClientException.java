package com.benchmark.patient_service.client;

import org.springframework.http.HttpStatus;

public class LabClientException extends RuntimeException
{
    private final HttpStatus status;

    public LabClientException(HttpStatus status, String message){
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
