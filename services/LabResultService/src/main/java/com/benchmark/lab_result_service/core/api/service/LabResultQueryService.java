package com.benchmark.lab_result_service.core.api.service;

import com.benchmark.lab_result_service.core.api.dto.PatientLabHistoryView;
import com.benchmark.lab_result_service.core.api.dto.PatientSummaryView;
import com.benchmark.lab_result_service.core.api.dto.PatientView;
import com.benchmark.lab_result_service.core.api.exception.InvalidRequestException;
import com.benchmark.lab_result_service.core.api.exception.PatientNotFoundException;
import com.benchmark.lab_result_service.core.api.exception.ServiceUnavailableException;

/** The single business entry point. Every protocol adapter calls exactly one method, exactly once, per request. */
public interface LabResultQueryService {

    /** @throws InvalidRequestException patientId <= 0
     *  @throws PatientNotFoundException no such patient
     *  @throws ServiceUnavailableException database unreachable / pool exhausted / transaction cannot begin */
    PatientView getPatient(long patientId);

    /** Scenario 1. Same exceptions as getPatient. */
    PatientSummaryView getPatientSummary(long patientId);

    /** Scenario 2, the full aggregate, always (no paging, no field selection). Same exceptions as getPatient. */
    PatientLabHistoryView getPatientLabHistory(long patientId);
}
