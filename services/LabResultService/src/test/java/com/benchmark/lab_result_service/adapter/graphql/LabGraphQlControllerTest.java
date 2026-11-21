package com.benchmark.lab_result_service.adapter.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.benchmark.lab_result_service.core.api.dto.LabReportView;
import com.benchmark.lab_result_service.core.api.dto.LabTestItemView;
import com.benchmark.lab_result_service.core.api.dto.PatientSummaryView;
import com.benchmark.lab_result_service.core.api.dto.PatientView;
import com.benchmark.lab_result_service.core.api.exception.InvalidRequestException;
import com.benchmark.lab_result_service.core.api.exception.PatientNotFoundException;
import com.benchmark.lab_result_service.core.api.exception.ServiceUnavailableException;
import com.benchmark.lab_result_service.core.api.service.LabErrorRecorder;
import com.benchmark.lab_result_service.core.api.service.LabResultQueryService;
import com.benchmark.lab_result_service.testsupport.GeneratedViews;
import com.benchmark.lab_result_service.testsupport.GraphQlDocuments;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;

/**
 * GraphQL slice of the adapter (contract §10 P5 {@code LabGraphQlControllerTest}): the real schema, controller and
 * {@link GraphQlExceptionResolver}, a mocked shared service, executed through {@code ExecutionGraphQlService} (no
 * HTTP, so no {@link GraphQlErrorAccountingInterceptor}; accounting is covered by {@code GraphQlErrorAccountingIT}
 * and {@code GraphQlErrorAccountingInterceptorTest}).
 *
 * <p>The mocked service answers with the generator's data ({@link GeneratedViews}), the same data as the REST golden
 * files, so S1/S2 are compared with those files: after converting GraphQL ID strings to numbers (D11) the trees must be
 * equal, which pins {@code birthDate}/{@code createdAt}/{@code resultValue}/{@code isCritical} to REST's JSON.
 */
@GraphQlTest(LabGraphQlController.class)
@Import(GraphQlExceptionResolver.class)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class LabGraphQlControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SECRET = "FATAL: password authentication failed for user \"lab\" at jdbc:postgresql://db:5432/labdb";

    @Autowired GraphQlTester graphQl;
    /** Boot's {@code JsonMapper} bean: the one Boot's MVC JSON converter, and so the REST adapter, writes with. */
    @Autowired JsonMapper bootJsonMapper;
    @MockitoBean LabResultQueryService service;
    /** The slice also picks up {@code @ControllerAdvice} beans (the REST handler needs it); nothing here may record. */
    @MockitoBean LabErrorRecorder recorder;

    @AfterEach
    void theResolverNeverRecords() {
        verifyNoInteractions(recorder);
    }

    // ------------------------------------------------------------------------------------------------- data

    @Test
    void s1MatchesTheRestGoldenFile() throws IOException {
        when(service.getPatientSummary(3)).thenReturn(GeneratedViews.summary(3));

        Map<String, Object> data = execute("S1", "3").path("patientSummary").entity(Map.class).get();

        assertThat(GraphQlDocuments.idsToNumbers(JSON.valueToTree(data)))
                .isEqualTo(JSON.readTree(golden("golden/s1-patient-3-summary.json")));
        verify(service).getPatientSummary(3);
        verifyNoMoreInteractions(service);
    }

    @Test
    void s2MatchesTheRestGoldenFile() throws IOException {
        when(service.getPatientLabHistory(1)).thenReturn(GeneratedViews.history(1));

        Map<String, Object> data = execute("S2", "1").path("patientLabHistory").entity(Map.class).get();

        assertThat(GraphQlDocuments.idsToNumbers(JSON.valueToTree(data)))
                .isEqualTo(JSON.readTree(golden("golden/s2-patient-1-lab-history.json")));
        verify(service).getPatientLabHistory(1);
        verifyNoMoreInteractions(service);
    }

    /** S2b: the same single service call as S2; only the selection (and so the serialized size) is smaller. */
    @Test
    void s2bSelectsOnlyTestNameAndResultValueFromTheSameServiceCall() {
        when(service.getPatientLabHistory(1)).thenReturn(GeneratedViews.history(1));

        GraphQlTester.Response response = execute("S2b", "1");

        List<Map<String, Object>> reports = response.path("patientLabHistory.reports").entityList(Map.class)
                .hasSize(100).get().stream().map(r -> (Map<String, Object>) r).toList();
        assertThat(reports).allSatisfy(r -> assertThat(r).containsOnlyKeys("items"));
        List<Map<String, Object>> items = response.path("patientLabHistory.reports[0].items")
                .entityList(Map.class).hasSize(10).get().stream().map(i -> (Map<String, Object>) i).toList();
        assertThat(items).allSatisfy(i -> assertThat(i).containsOnlyKeys("testName", "resultValue"));
        response.path("patientLabHistory.patient").pathDoesNotExist();
        verify(service).getPatientLabHistory(1);
        verifyNoMoreInteractions(service);
    }

    @Test
    void patientWithoutReportsHasANullLatestReportAndAnEmptyHistory() {
        when(service.getPatientSummary(2)).thenReturn(GeneratedViews.summary(2));
        when(service.getPatientLabHistory(2)).thenReturn(GeneratedViews.history(2));

        execute("S1", "2").path("patientSummary.latestReport").valueIsNull();
        execute("S2", "2").path("patientLabHistory.reports").entityList(Object.class).hasSize(0);
    }

    @Test
    void patientQueryReturnsThePatientView() {
        when(service.getPatient(3)).thenReturn(GeneratedViews.patient(3));

        graphQl.document("{ patient(id: \"3\") { id tcNo fullName birthDate bloodType } }").execute()
                .path("patient.id").entity(String.class).isEqualTo("3");
        verify(service).getPatient(3);
        verifyNoMoreInteractions(service);
    }

    /**
     * Contract §7.2 said {@code INFERRED 80%}: graphql-java's String coercion ({@code toString()}) equals Jackson 3's
     * ISO output. Pinned here for the cases the golden files do not contain: a whole minute ({@code :00} seconds must
     * still be printed), the epoch, a date before 1970 and a leap day, each compared with what Boot's {@code JsonMapper}
     * bean (the REST adapter's) writes.
     */
    @Test
    void dateAndInstantStringsEqualJackson3() {
        List<Instant> instants = List.of(Instant.parse("2025-12-31T17:03:00Z"), Instant.EPOCH,
                Instant.parse("2024-02-29T23:59:59Z"), Instant.parse("1969-07-20T20:17:40Z"));
        LocalDate birthDate = LocalDate.parse("1904-02-29");
        PatientView patient = new PatientView(9, "10000000099", "Edge Case", birthDate, "O+");
        for (Instant createdAt : instants) {
            LabReportView report = new LabReportView(7, "Dr. X", "SERUM", createdAt,
                    List.of(new LabTestItemView(70, "GLU", "Glucose", 100.0, "mg/dL", false)));
            when(service.getPatientSummary(9)).thenReturn(new PatientSummaryView(patient, report));

            GraphQlTester.Response response = execute("S1", "9");

            response.path("patientSummary.latestReport.createdAt").entity(String.class)
                    .isEqualTo(bootJsonMapper.readTree(bootJsonMapper.writeValueAsString(createdAt)).asString());
            response.path("patientSummary.patient.birthDate").entity(String.class)
                    .isEqualTo(bootJsonMapper.readTree(bootJsonMapper.writeValueAsString(birthDate)).asString());
            response.path("patientSummary.latestReport.items[0].resultValue").entity(Double.class).isEqualTo(100.0);
        }
        assertThat(bootJsonMapper.writeValueAsString(Instant.parse("2025-12-31T17:03:00Z")))
                .isEqualTo("\"2025-12-31T17:03:00Z\"");
    }

    // ------------------------------------------------------------------------------------------------- errors

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", "9223372036854775808"})
    void malformedIdIsBadRequestWithoutCallingTheService(String raw) {
        ResponseError error = singleError(execute("S1", raw), "patientSummary");

        assertError(error, "BAD_REQUEST", "INVALID_ARGUMENT", "patientId must be a positive 64-bit integer");
        verifyNoInteractions(service);
    }

    @Test
    void zeroIsRejectedByTheServiceAsBadRequest() {
        when(service.getPatientLabHistory(0)).thenThrow(
                new InvalidRequestException("patientId", "must be a positive 64-bit integer"));

        assertError(singleError(execute("S2", "0"), "patientLabHistory"), "BAD_REQUEST", "INVALID_ARGUMENT",
                "patientId must be a positive 64-bit integer");
        verify(service).getPatientLabHistory(0);
    }

    @Test
    void unknownPatientIsNotFound() {
        when(service.getPatientLabHistory(1001)).thenThrow(new PatientNotFoundException(1001));

        assertError(singleError(execute("S2", "1001"), "patientLabHistory"), "NOT_FOUND", "PATIENT_NOT_FOUND",
                "Patient 1001 not found");
    }

    @Test
    void unavailableIsInternalErrorWithFixedMessage() {
        when(service.getPatientSummary(3)).thenThrow(new ServiceUnavailableException(new CannotCreateTransactionException(
                "Could not open JPA EntityManager", new SQLTransientConnectionException(SECRET))));

        ResponseError error = singleError(execute("S1", "3"), "patientSummary");

        assertError(error, "INTERNAL_ERROR", "UNAVAILABLE", "Service temporarily unavailable");
        assertSanitized(error);
    }

    @Test
    void anythingElseIsInternalWithFixedMessage() {
        when(service.getPatientSummary(3)).thenThrow(new InvalidDataAccessResourceUsageException(
                "select * from lab_report where " + SECRET, new SQLException(SECRET, "42P01")));

        ResponseError error = singleError(execute("S1", "3"), "patientSummary");

        assertError(error, "INTERNAL_ERROR", "INTERNAL", "Internal error");
        assertSanitized(error);
    }

    @Test
    void anErrorWithANullMessageIsStillInternal() {
        when(service.getPatientLabHistory(anyLong())).thenThrow(new NullPointerException());

        assertError(singleError(execute("S2", "1"), "patientLabHistory"), "INTERNAL_ERROR", "INTERNAL", "Internal error");
    }

    /** The error carries only our code (graphql-java adds the classification); no exception class, trace or other internals. */
    @Test
    void errorExtensionsHoldOnlyClassificationAndCode() {
        when(service.getPatientSummary(3)).thenThrow(new IllegalStateException(SECRET));

        ResponseError error = singleError(execute("S1", "3"), "patientSummary");

        assertThat(error.getExtensions()).containsOnlyKeys("code");
        assertThat(error.getPath()).isEqualTo("patientSummary");
        assertThat(error.getLocations()).isNotEmpty();
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private GraphQlTester.Response execute(String operation, String id) {
        return graphQl.document(GraphQlDocuments.BY_NAME.get(operation)).operationName(operation).variable("id", id)
                .execute();
    }

    /** Exactly one error, at {@code rootField}, whose data value is null. */
    private static ResponseError singleError(GraphQlTester.Response response, String rootField) {
        ResponseError[] holder = new ResponseError[1];
        response.errors().satisfy(errors -> {
            assertThat(errors).hasSize(1);
            holder[0] = errors.getFirst();
        });
        response.path(rootField).valueIsNull();
        return holder[0];
    }

    private static void assertError(ResponseError error, String classification, String code, String message) {
        assertThat(error.getErrorType()).hasToString(classification);
        // in-process tester: the error's own extensions; graphql-java adds "classification" to them on the wire
        // (asserted over HTTP in GraphQlErrorAccountingIT)
        assertThat(error.getExtensions()).containsExactly(Map.entry("code", code));
        assertThat(error.getMessage()).isEqualTo(message);
    }

    private static void assertSanitized(ResponseError error) {
        String all = error.getMessage() + " " + error.getExtensions();
        assertThat(all).doesNotContain("password", "jdbc:", "select", "lab_report", "Exception", "\tat ", "42P01");
    }

    private static String golden(String resource) throws IOException {
        try (InputStream in = LabGraphQlControllerTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
