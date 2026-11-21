package com.benchmark.lab_result_service.adapter.rest;

import com.benchmark.lab_result_service.core.api.dto.PatientLabHistoryView;
import com.benchmark.lab_result_service.core.api.dto.PatientSummaryView;
import com.benchmark.lab_result_service.core.api.dto.PatientView;
import com.benchmark.lab_result_service.core.api.service.LabResultQueryService;
import com.benchmark.lab_result_service.core.api.util.PatientIds;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST adapter (contract §7.1), port 8080. Mapping only: every endpoint parses the path id with {@link PatientIds#parse}
 * (the same rule as GraphQL) and calls exactly one method of the shared {@link LabResultQueryService}, exactly once.
 * The core.api records are serialized as they are (benchmark fairness: no protocol-specific query, cache or shortcut,
 * no extra headers). Errors are rendered by {@link RestExceptionHandler}.
 */
@RestController
@RequestMapping(path = "/api/v1/patients", produces = MediaType.APPLICATION_JSON_VALUE)
public class PatientRestController {

    private final LabResultQueryService service;

    public PatientRestController(LabResultQueryService service) {
        this.service = service;
    }

    @GetMapping("/{patientId}")
    public PatientView patient(@PathVariable String patientId) {
        return service.getPatient(PatientIds.parse(patientId));
    }

    /** Scenario 1. */
    @GetMapping("/{patientId}/summary")
    public PatientSummaryView summary(@PathVariable String patientId) {
        return service.getPatientSummary(PatientIds.parse(patientId));
    }

    /** Scenario 2. */
    @GetMapping("/{patientId}/lab-history")
    public PatientLabHistoryView labHistory(@PathVariable String patientId) {
        return service.getPatientLabHistory(PatientIds.parse(patientId));
    }
}
