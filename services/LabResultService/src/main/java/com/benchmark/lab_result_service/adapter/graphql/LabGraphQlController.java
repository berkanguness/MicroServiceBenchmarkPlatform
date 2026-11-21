package com.benchmark.lab_result_service.adapter.graphql;

import com.benchmark.lab_result_service.core.api.dto.PatientLabHistoryView;
import com.benchmark.lab_result_service.core.api.dto.PatientSummaryView;
import com.benchmark.lab_result_service.core.api.dto.PatientView;
import com.benchmark.lab_result_service.core.api.service.LabResultQueryService;
import com.benchmark.lab_result_service.core.api.util.PatientIds;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * GraphQL adapter (contract §7.2, D9), {@code POST /graphql} on port 8080. Mapping only: each root field parses its
 * {@code ID} argument with {@link PatientIds#parse} (the same rule as REST) and calls exactly one method of the shared
 * {@link LabResultQueryService}, exactly once, returning the core.api record as it is.
 *
 * <p>Nested fields are graphql-java's default property reads on those records. Benchmark fairness: there is no
 * {@code @SchemaMapping}, {@code @BatchMapping}, DataLoader, custom scalar, depth/complexity instrumentation,
 * {@code PreparsedDocumentProvider}, cache or protocol-specific query. A narrower selection (S2b) runs the same
 * service call, statements and assembly; only serialization differs. Errors are built by
 * {@link GraphQlExceptionResolver} and counted once per request by {@link GraphQlErrorAccountingInterceptor}.
 */
@Controller
public class LabGraphQlController {

    private final LabResultQueryService service;

    public LabGraphQlController(LabResultQueryService service) {
        this.service = service;
    }

    @QueryMapping
    public PatientView patient(@Argument String id) {
        return service.getPatient(PatientIds.parse(id));
    }

    /** Scenario 1. */
    @QueryMapping
    public PatientSummaryView patientSummary(@Argument String patientId) {
        return service.getPatientSummary(PatientIds.parse(patientId));
    }

    /** Scenario 2 (and S2b, which only selects fewer fields of the same aggregate). */
    @QueryMapping
    public PatientLabHistoryView patientLabHistory(@Argument String patientId) {
        return service.getPatientLabHistory(PatientIds.parse(patientId));
    }
}
