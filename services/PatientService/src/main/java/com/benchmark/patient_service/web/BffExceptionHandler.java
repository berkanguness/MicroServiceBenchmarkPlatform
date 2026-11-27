package com.benchmark.patient_service.web;

import com.benchmark.patient_service.client.LabClientException;
import io.grpc.StatusRuntimeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

@RestControllerAdvice
public class BffExceptionHandler
{
    @ExceptionHandler(StatusRuntimeException.class)
    ProblemDetail grpc(StatusRuntimeException e){
        HttpStatus status = switch (e.getStatus().getCode()){
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_ARGUMENT ->  HttpStatus.BAD_REQUEST;
            case UNAVAILABLE, DEADLINE_EXCEEDED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ProblemDetail.forStatusAndDetail(status, "gRPC:" + e.getStatus().getCode());
    }

    @ExceptionHandler(RestClientResponseException.class)
    ProblemDetail rest(RestClientResponseException e){
        return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.getStatusCode().value()), "LabResultService HTTP" + e.getStatusCode().value());
    }

    @ExceptionHandler(LabClientException.class)
    ProblemDetail graphql(LabClientException e){
        return ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e){
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

}
