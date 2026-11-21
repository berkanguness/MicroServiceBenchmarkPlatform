package com.benchmark.lab_result_service.adapter.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.benchmark.lab_result_service.core.api.service.LabResultQueryService;
import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import com.benchmark.lab_result_service.testsupport.GraphQlDocuments;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract §10 P5 {@code GraphQlStatementCountIT} (P1 fixture, real stack over HTTP): S1/S2/S2b cause exactly the
 * statements of the REST request for the same scenario and patient (§5.3: 3/3/3 for patients 3/1/1, 2 for patient 2),
 * exactly one shared-service call each, on the Tomcat request thread like REST. S2b runs the same
 * {@code getPatientLabHistory} as S2 (same 3 statements); only serialization differs.
 *
 * <p>It also turns the contract's {@code INFERRED 80%} (§7.2: {@code birthDate}/{@code createdAt} via graphql-java's
 * String coercion equal Jackson 3's ISO output) into evidence on the real stack: after converting GraphQL ID strings to
 * numbers, the GraphQL {@code data} tree equals the REST JSON tree of the same patient.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql(scripts = "/fixtures/p1-fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class GraphQlStatementCountIT extends AbstractPostgresIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort int port;
    @Autowired EntityManagerFactory emf;
    @Autowired MeterRegistry registry;
    @MockitoSpyBean LabResultQueryService service;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> serviceThreads = new CopyOnWriteArrayList<>();
    private Statistics stats;

    @BeforeEach
    void setUp() {
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        assertThat(stats.isStatisticsEnabled()).isTrue();
        doAnswer(inv -> { serviceThreads.add(Thread.currentThread().getName()); return inv.callRealMethod(); })
                .when(service).getPatientSummary(anyLong());
        doAnswer(inv -> { serviceThreads.add(Thread.currentThread().getName()); return inv.callRealMethod(); })
                .when(service).getPatientLabHistory(anyLong());
    }

    @ParameterizedTest(name = "{0} patient {1}: {3} statements")
    @CsvSource({
            "S1,  3, summary,     3, getPatientSummary,    patientSummary",
            "S2,  1, lab-history, 3, getPatientLabHistory, patientLabHistory",
            "S2b, 1, lab-history, 3, getPatientLabHistory, patientLabHistory",
            "S1,  2, summary,     2, getPatientSummary,    patientSummary",
            "S2,  2, lab-history, 2, getPatientLabHistory, patientLabHistory",
            "S2b, 2, lab-history, 2, getPatientLabHistory, patientLabHistory"})
    void graphQlRunsExactlyTheRestStatements(String operation, String id, String restPath, int expected, String method,
            String rootField) throws Exception {
        // REST reference
        Measured rest = measure(method, () -> send(HttpRequest.newBuilder(uri("/api/v1/patients/" + id + "/" + restPath))
                .header("Accept", "application/json").GET()));
        // GraphQL, as QA's k6 client sends it
        Measured graphQl = measure(method, () -> send(HttpRequest.newBuilder(uri("/graphql"))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GraphQlDocuments.body(operation, id)))));

        assertThat(rest.response.statusCode()).isEqualTo(200);
        assertThat(graphQl.response.statusCode()).isEqualTo(200);
        assertThat(graphQl.response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/json"));
        JsonNode body = JSON.readTree(graphQl.response.body());
        assertThat(body.has("errors")).as(graphQl.response.body()).isFalse();

        assertThat(rest.statements).as("REST statements (§5.3)").isEqualTo(expected);
        assertThat(graphQl.statements).as("GraphQL %s statements == REST", operation).isEqualTo(rest.statements);
        assertThat(rest.serviceCalls).as("REST service calls").isEqualTo(1);
        assertThat(graphQl.serviceCalls).as("GraphQL service calls (%s)", method).isEqualTo(1);
        assertThat(serviceThreads).as("service calls (REST, GraphQL) run on Tomcat request threads")
                .hasSize(2).allMatch(name -> name.startsWith("http-nio-"), "an http-nio-* thread");

        if (!operation.equals("S2b")) {
            JsonNode restTree = JSON.readTree(rest.response.body());
            JsonNode graphQlTree = GraphQlDocuments.idsToNumbers(body.get("data").get(rootField));
            assertThat(graphQlTree).as("GraphQL data (ids → numbers) == REST JSON").isEqualTo(restTree);
        }
    }

    private record Measured(HttpResponse<String> response, long statements, double serviceCalls) {}

    private interface Call {
        HttpResponse<String> run() throws IOException, InterruptedException;
    }

    private Measured measure(String method, Call call) throws IOException, InterruptedException {
        long before = stats.getPrepareStatementCount();
        double callsBefore = serviceCalls(method);
        HttpResponse<String> response = call.run();
        return new Measured(response, stats.getPrepareStatementCount() - before, serviceCalls(method) - callsBefore);
    }

    private double serviceCalls(String method) {
        return registry.find("lab.service.calls").tag("method", method).timers().stream()
                .mapToDouble(t -> t.count()).sum();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
