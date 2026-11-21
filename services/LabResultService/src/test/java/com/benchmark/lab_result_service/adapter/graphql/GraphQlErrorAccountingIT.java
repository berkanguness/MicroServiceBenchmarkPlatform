package com.benchmark.lab_result_service.adapter.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.benchmark.lab_result_service.core.api.enums.ErrorCode;
import com.benchmark.lab_result_service.core.api.enums.Protocol;
import com.benchmark.lab_result_service.core.api.exception.InvalidRequestException;
import com.benchmark.lab_result_service.core.api.exception.PatientNotFoundException;
import com.benchmark.lab_result_service.core.api.service.LabErrorRecorder;
import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import com.benchmark.lab_result_service.testsupport.GraphQlDocuments;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract §10 P5 {@code GraphQlErrorAccountingIT}: full context over HTTP ({@link HttpGraphQlTester}; the
 * interceptor is web-layer, so it is not part of {@code @GraphQlTest}), P1 fixture. The counting unit is the request
 * (§7.4): every failed request increments {@code lab.errors{protocol=GRAPHQL,…}} by exactly one and calls the
 * recorder exactly once; a successful request records nothing. Requests send {@code Accept: application/json}, as
 * QA's k6 client does (§7.2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Sql(scripts = "/fixtures/p1-fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class GraphQlErrorAccountingIT extends AbstractPostgresIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired HttpGraphQlTester tester;
    @Autowired MeterRegistry registry;
    @LocalServerPort int port;
    @MockitoSpyBean LabErrorRecorder recorder;

    private HttpGraphQlTester graphQl;
    private Map<ErrorCode, Double> before;

    @BeforeEach
    void setUp() {
        graphQl = tester.mutate().header("Accept", MediaType.APPLICATION_JSON_VALUE).build();
        before = counters();
    }

    @Test
    void syntaxErrorIsOneInvalidArgumentWithoutCause() {
        graphQl.document("{ patientSummary(").execute().errors().satisfy(errors -> {
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().getExtensions()).containsEntry("classification", "InvalidSyntax");
        });

        assertCounted(ErrorCode.INVALID_ARGUMENT);
        verify(recorder).record(Protocol.GRAPHQL, ErrorCode.INVALID_ARGUMENT, null);
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void validationErrorIsOneInvalidArgumentWithoutCause() {
        graphQl.document("{ patientSummary(patientId: \"3\") { noSuchField } }").execute().errors().satisfy(errors -> {
            assertThat(errors).isNotEmpty();
            assertThat(errors).allSatisfy(e -> assertThat(e.getExtensions()).containsEntry("classification", "ValidationError"));
        });

        assertCounted(ErrorCode.INVALID_ARGUMENT);
        verify(recorder).record(Protocol.GRAPHQL, ErrorCode.INVALID_ARGUMENT, null);
        verifyNoMoreInteractions(recorder);
    }

    /** Two failing root fields → two errors, one failed request: +1, the more severe code (INVALID_ARGUMENT). */
    @Test
    void twoFailingRootFieldsAreOneFailedRequest() {
        HttpGraphQlTester.Response response = graphQl.document("""
                { a: patientSummary(patientId: "1001") { patient { id } }
                  b: patientSummary(patientId: "abc") { patient { id } } }""").execute();

        response.errors().satisfy(errors -> {
            assertThat(errors).hasSize(2);
            assertThat(errors).extracting(e -> e.getExtensions().get("code"))
                    .containsExactlyInAnyOrder("PATIENT_NOT_FOUND", "INVALID_ARGUMENT");
            assertThat(errors).extracting(ResponseError::getPath).containsExactlyInAnyOrder("a", "b");
        });
        response.path("a").valueIsNull().path("b").valueIsNull();

        assertCounted(ErrorCode.INVALID_ARGUMENT);
        verify(recorder).record(eq(Protocol.GRAPHQL), eq(ErrorCode.INVALID_ARGUMENT), any(InvalidRequestException.class));
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void notFoundIsOnePatientNotFound() {
        HttpGraphQlTester.Response response = graphQl.document(GraphQlDocuments.S2).operationName("S2")
                .variable("id", "1001").execute();

        response.errors().satisfy(errors -> {
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().getMessage()).isEqualTo("Patient 1001 not found");
            assertThat(errors.getFirst().getExtensions())
                    .containsEntry("classification", "NOT_FOUND").containsEntry("code", "PATIENT_NOT_FOUND");
        });
        response.path("patientLabHistory").valueIsNull();

        assertCounted(ErrorCode.PATIENT_NOT_FOUND);
        verify(recorder).record(eq(Protocol.GRAPHQL), eq(ErrorCode.PATIENT_NOT_FOUND), any(PatientNotFoundException.class));
        verifyNoMoreInteractions(recorder);
    }

    /** Partial success: the failed field still makes the request a failed one (once). */
    @Test
    void oneFailingAndOneSuccessfulRootFieldIsOneFailedRequest() {
        HttpGraphQlTester.Response response = graphQl.document("""
                { ok: patientSummary(patientId: "3") { patient { id } }
                  missing: patientSummary(patientId: "1001") { patient { id } } }""").execute();

        response.errors().satisfy(errors -> assertThat(errors).hasSize(1));
        response.path("ok.patient.id").entity(String.class).isEqualTo("3").path("missing").valueIsNull();

        assertCounted(ErrorCode.PATIENT_NOT_FOUND);
        verify(recorder).record(eq(Protocol.GRAPHQL), eq(ErrorCode.PATIENT_NOT_FOUND), any(PatientNotFoundException.class));
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void successfulRequestsRecordNothing() {
        for (String[] op : List.of(new String[] {"S1", "3"}, new String[] {"S2", "1"}, new String[] {"S2b", "1"},
                new String[] {"S1", "2"}, new String[] {"S2", "2"})) {
            graphQl.document(GraphQlDocuments.BY_NAME.get(op[0])).operationName(op[0]).variable("id", op[1]).execute()
                    .errors().verify();
        }
        graphQl.document("{ patient(id: \"3\") { id } }").execute().path("patient.id").entity(String.class).isEqualTo("3");

        assertThat(counters()).isEqualTo(before);
        verifyNoInteractions(recorder);
    }

    /**
     * The wire format QA's k6 checks ({@code tests/k6/lib/contract.mjs expectedError}): HTTP 200,
     * {@code application/json}, {@code data.<root> === null}, every {@code errors[].extensions.code} equal, for the
     * same ids k6's smoke sends. The counter goes up by one per request.
     */
    @ParameterizedTest
    @CsvSource({"S1, 1001, PATIENT_NOT_FOUND, patientSummary", "S2, 0, INVALID_ARGUMENT, patientLabHistory",
            "S1, -1, INVALID_ARGUMENT, patientSummary", "S2, abc, INVALID_ARGUMENT, patientLabHistory",
            "S1, 9223372036854775808, INVALID_ARGUMENT, patientSummary"})
    void wireFormatOfFieldErrorsMatchesWhatK6Expects(String operation, String id, String code, String rootField)
            throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GraphQlDocuments.body(operation, id))).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/json"));
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("data").get(rootField).isNull()).isTrue();
        assertThat(body.get("errors")).hasSize(1);
        JsonNode error = body.get("errors").get(0);
        assertThat(error.get("extensions").get("code").asString()).isEqualTo(code);
        assertThat(error.get("extensions").get("classification").asString())
                .isEqualTo(code.equals("PATIENT_NOT_FOUND") ? "NOT_FOUND" : "BAD_REQUEST");
        assertThat(error.get("path").get(0).asString()).isEqualTo(rootField);

        assertCounted(ErrorCode.valueOf(code));
        verify(recorder).record(eq(Protocol.GRAPHQL), eq(ErrorCode.valueOf(code)), any());
        verify(recorder, never()).record(eq(Protocol.GRAPHQL), any(), isNull());
        verifyNoMoreInteractions(recorder);
    }

    /** Exactly {@code code} went up by one; every other GRAPHQL code (and every REST/GRPC series) is unchanged. */
    private void assertCounted(ErrorCode code) {
        Map<ErrorCode, Double> after = counters();
        for (ErrorCode c : ErrorCode.values()) {
            assertThat(after.get(c) - before.get(c)).as("lab.errors{protocol=GRAPHQL,code=%s}", c)
                    .isEqualTo(c == code ? 1.0 : 0.0);
        }
        assertThat(registry.find("lab.errors").tag("protocol", "REST").counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    private Map<ErrorCode, Double> counters() {
        Map<ErrorCode, Double> counts = new EnumMap<>(ErrorCode.class);
        for (ErrorCode code : ErrorCode.values()) {
            counts.put(code, registry.get("lab.errors").tag("protocol", "GRAPHQL").tag("code", code.name()).counter().count());
        }
        return counts;
    }
}
