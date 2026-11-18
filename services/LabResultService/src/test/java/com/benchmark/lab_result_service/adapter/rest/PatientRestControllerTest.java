package com.benchmark.lab_result_service.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.benchmark.lab_result_service.core.api.enums.ErrorCode;
import com.benchmark.lab_result_service.core.api.enums.Protocol;
import com.benchmark.lab_result_service.core.api.exception.InvalidRequestException;
import com.benchmark.lab_result_service.core.api.exception.PatientNotFoundException;
import com.benchmark.lab_result_service.core.api.exception.ServiceUnavailableException;
import com.benchmark.lab_result_service.core.api.service.LabErrorRecorder;
import com.benchmark.lab_result_service.core.api.service.LabResultQueryService;
import com.benchmark.lab_result_service.testsupport.GeneratedViews;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Web slice of the REST adapter (contract §10 P4): the mocked shared service, the real controller, advice and Jackson.
 *
 * <p>Golden files ({@code src/test/resources/golden/}) are the exact bodies the packaged application returned for
 * S1 (patient 3) and S2 (patient 1) against a PostgreSQL 17 database seeded by the P2 seeder (P4 M1 smoke). The
 * mocked service answers with the same data built from the generator ({@link GeneratedViews}).
 */
@WebMvcTest(PatientRestController.class)
class PatientRestControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SECRET = "FATAL: password authentication failed for user \"lab\" at jdbc:postgresql://db:5432/labdb";

    @Autowired MockMvc mvc;
    @MockitoBean LabResultQueryService service;
    @MockitoBean LabErrorRecorder recorder;

    // ---------------------------------------------------------------------------------------------------- 200

    @Test
    void scenario1SummaryMatchesTheGoldenBodyByteForByte() throws Exception {
        when(service.getPatientSummary(3)).thenReturn(GeneratedViews.summary(3));

        MvcResult result = mvc.perform(get("/api/v1/patients/3/summary"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        assertGolden(result, "golden/s1-patient-3-summary.json");
        verify(service).getPatientSummary(3);
        verifyNoMoreInteractions(service);
        verifyNoInteractions(recorder);
    }

    @Test
    void scenario2LabHistoryMatchesTheGoldenBodyByteForByte() throws Exception {
        when(service.getPatientLabHistory(1)).thenReturn(GeneratedViews.history(1));

        MvcResult result = mvc.perform(get("/api/v1/patients/1/lab-history"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        assertGolden(result, "golden/s2-patient-1-lab-history.json");
        verify(service).getPatientLabHistory(1);
        verifyNoMoreInteractions(service);
    }

    @Test
    void goldenFieldNamesAndTypesFollowTheContractTable() throws Exception {
        JsonNode s1 = JSON.readTree(golden("golden/s1-patient-3-summary.json"));
        assertThat(s1.get("patient").get("id").isIntegralNumber()).isTrue();
        assertThat(s1.get("patient").get("tcNo").asString()).matches("\\d{11}");
        assertThat(s1.get("patient").get("birthDate").asString()).matches("\\d{4}-\\d{2}-\\d{2}");
        JsonNode report = s1.get("latestReport");
        assertThat(report.get("id").asLong()).isEqualTo(101);
        assertThat(report.get("createdAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        JsonNode item = report.get("items").get(0);
        assertThat(item.get("id").isIntegralNumber()).isTrue();
        assertThat(item.get("resultValue").isNumber()).isTrue();
        assertThat(item.get("isCritical").isBoolean()).as("record component name isCritical, not 'critical'").isTrue();
        assertThat(item.has("critical")).isFalse();
        assertThat(item.propertyNames()).containsExactly("id", "testCode", "testName", "resultValue", "unit", "isCritical");

        JsonNode s2 = JSON.readTree(golden("golden/s2-patient-1-lab-history.json"));
        assertThat(s2.get("reports").size()).isEqualTo(100);
        s2.get("reports").forEach(r -> assertThat(r.get("items").size()).isEqualTo(10));
    }

    @Test
    void patientWithoutReportsHasANullLatestReportAndAnEmptyHistory() throws Exception {
        when(service.getPatientSummary(2)).thenReturn(GeneratedViews.summary(2));
        when(service.getPatientLabHistory(2)).thenReturn(GeneratedViews.history(2));

        String body = mvc.perform(get("/api/v1/patients/2/summary")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode summary = JSON.readTree(body);
        assertThat(summary.has("latestReport")).as("latestReport is present, as JSON null").isTrue();
        assertThat(summary.get("latestReport").isNull()).isTrue();
        mvc.perform(get("/api/v1/patients/2/lab-history")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reports").isArray())
                .andExpect(jsonPath("$.reports").isEmpty());
    }

    @Test
    void patientEndpointReturnsThePatientView() throws Exception {
        when(service.getPatient(3)).thenReturn(GeneratedViews.patient(3));

        mvc.perform(get("/api/v1/patients/3"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(header().doesNotExist("Content-Encoding"));
        verify(service).getPatient(3);
        verifyNoMoreInteractions(service);
    }

    // ---------------------------------------------------------------------------------------------------- 400

    @ParameterizedTest
    @ValueSource(strings = {"abc", "9223372036854775808"})
    void unparsableIdIs400WithoutCallingTheService(String raw) throws Exception {
        expectProblem(get("/api/v1/patients/" + raw + "/summary"), 400, "Bad Request",
                "patientId must be a positive 64-bit integer", "INVALID_ARGUMENT", "/api/v1/patients/" + raw + "/summary");
        verifyNoInteractions(service);
        verify(recorder).record(eq(Protocol.REST), eq(ErrorCode.INVALID_ARGUMENT), any(InvalidRequestException.class));
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void zeroIsRejectedByTheServiceAs400() throws Exception {
        InvalidRequestException e = new InvalidRequestException("patientId", "must be a positive 64-bit integer");
        when(service.getPatientLabHistory(0)).thenThrow(e);

        expectProblem(get("/api/v1/patients/0/lab-history"), 400, "Bad Request",
                "patientId must be a positive 64-bit integer", "INVALID_ARGUMENT", "/api/v1/patients/0/lab-history");
        verify(service).getPatientLabHistory(0);
        verify(recorder).record(Protocol.REST, ErrorCode.INVALID_ARGUMENT, e);
        verifyNoMoreInteractions(recorder);
    }

    // ---------------------------------------------------------------------------------------------------- 404

    @Test
    void unknownPatientIs404() throws Exception {
        PatientNotFoundException e = new PatientNotFoundException(1001);
        when(service.getPatientLabHistory(1001)).thenThrow(e);

        expectProblem(get("/api/v1/patients/1001/lab-history"), 404, "Not Found", "Patient 1001 not found",
                "PATIENT_NOT_FOUND", "/api/v1/patients/1001/lab-history");
        verify(recorder).record(Protocol.REST, ErrorCode.PATIENT_NOT_FOUND, e);
        verifyNoMoreInteractions(recorder);
    }

    // ---------------------------------------------------------------------------------------------------- 503

    @Test
    void unavailableIs503WithFixedDetail() throws Exception {
        ServiceUnavailableException e = new ServiceUnavailableException(new CannotCreateTransactionException(
                "Could not open JPA EntityManager", new SQLTransientConnectionException(SECRET)));
        when(service.getPatientSummary(3)).thenThrow(e);

        String body = expectProblem(get("/api/v1/patients/3/summary"), 503, "Service Unavailable",
                "Service temporarily unavailable", "UNAVAILABLE", "/api/v1/patients/3/summary");
        assertSanitized(body);
        verify(recorder).record(Protocol.REST, ErrorCode.UNAVAILABLE, e);
        verifyNoMoreInteractions(recorder);
    }

    // ---------------------------------------------------------------------------------------------------- 500

    @Test
    void anythingElseIs500WithFixedDetail() throws Exception {
        RuntimeException e = new InvalidDataAccessResourceUsageException("select * from lab_report where " + SECRET,
                new SQLException(SECRET, "42P01"));
        when(service.getPatientSummary(3)).thenThrow(e);

        String body = expectProblem(get("/api/v1/patients/3/summary"), 500, "Internal Server Error",
                "Internal error", "INTERNAL", "/api/v1/patients/3/summary");
        assertSanitized(body);
        verify(recorder).record(Protocol.REST, ErrorCode.INTERNAL, e);
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void anErrorWithANullMessageIsStill500() throws Exception {
        when(service.getPatientLabHistory(anyLong())).thenThrow(new NullPointerException());

        expectProblem(get("/api/v1/patients/1/lab-history"), 500, "Internal Server Error", "Internal error",
                "INTERNAL", "/api/v1/patients/1/lab-history");
        verify(recorder).record(eq(Protocol.REST), eq(ErrorCode.INTERNAL), any(NullPointerException.class));
    }

    // ------------------------------------------------------------------------------------- Spring defaults kept

    @Test
    void unmatchedRoutesAndWrongMethodsKeepSpringDefaults() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/patients/3/summary"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"));
        mvc.perform(get("/api/v1/patients/3/unknown")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/patients/3").accept(MediaType.APPLICATION_XML)).andExpect(status().isNotAcceptable());
        verifyNoInteractions(service);
        verifyNoInteractions(recorder);
    }

    /** springdoc puts a YAML converter on the classpath; the endpoints still only produce JSON. */
    @Test
    void yamlIsNotNegotiable() throws Exception {
        mvc.perform(get("/api/v1/patients/3/summary").accept(MediaType.parseMediaType("application/yaml")))
                .andExpect(status().isNotAcceptable());
        verifyNoInteractions(service);
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private String expectProblem(org.springframework.test.web.servlet.RequestBuilder request, int status, String title,
            String detail, String code, String instance) throws Exception {
        ResultActions actions = mvc.perform(request)
                .andExpect(status().is(status))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.code").value(code));
        String body = actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JSON.readTree(body).propertyNames())
                .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code");
        return body;
    }

    private static void assertSanitized(String body) {
        assertThat(body).doesNotContain("password", "jdbc:", "select", "lab_report", "Exception", "\tat ", "42P01");
    }

    private static void assertGolden(MvcResult result, String resource) throws IOException {
        String actual = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String expected = golden(resource);
        assertThat(JSON.readTree(actual)).as("JSON tree").isEqualTo(JSON.readTree(expected));
        assertThat(actual).as("exact bytes (field order, number and date formatting)").isEqualTo(expected);
    }

    private static String golden(String resource) throws IOException {
        try (InputStream in = PatientRestControllerTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
