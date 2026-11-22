package com.benchmark.lab_result_service.adapter.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.benchmark.lab_result_service.core.api.enums.ErrorCode;
import com.benchmark.lab_result_service.core.api.enums.Protocol;
import com.benchmark.lab_result_service.core.api.exception.ServiceUnavailableException;
import com.benchmark.lab_result_service.core.api.service.LabErrorRecorder;
import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import com.benchmark.lab_result_service.testsupport.GraphQlDocuments;
import com.benchmark.lab_result_service.testsupport.PgSideConnections;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One GraphQL {@code UNAVAILABLE} path through the real stack (P5 brief; technique of P4's {@code RestErrorIT}(a),
 * pool exhaustion): after a healthy S1, the test holds the only pool connection and sends S1 again. The field is
 * {@code null}, {@code extensions.code=UNAVAILABLE} with the fixed message, HTTP 200; the request is recorded exactly
 * once ({@code lab.errors{GRAPHQL,UNAVAILABLE}} +1) and the shared service counts one
 * {@code lab.service.calls{outcome=UNAVAILABLE}}. The fixture is loaded through a test-only side connection, never
 * through the app datasource.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.minimum-idle=1"})
class GraphQlUnavailableIT extends AbstractPostgresIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort int port;
    @Autowired MeterRegistry registry;
    @Autowired DataSource dataSource;
    @MockitoSpyBean LabErrorRecorder recorder;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void healthyFirst() throws Exception {
        PgSideConnections.loadFixtureOnce(postgres, "/fixtures/p1-fixture.sql");
        JsonNode ok = JSON.readTree(s1().body());
        assertThat(ok.has("errors")).isFalse();
        assertThat(ok.get("data").get("patientSummary").get("latestReport").get("id").asString()).isEqualTo("101");
    }

    @Test
    void poolExhaustionIsUnavailableRecordedOnce() throws Exception {
        double errorsBefore = graphQlUnavailable();
        double callsBefore = serviceUnavailable();
        HttpResponse<String> response;
        long elapsedMs;
        try (Connection held = dataSource.getConnection()) {
            assertThat(dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
            long start = System.nanoTime();
            response = s1();
            elapsedMs = (System.nanoTime() - start) / 1_000_000;
        }
        System.out.printf("GraphQlUnavailableIT pool exhaustion: HTTP %d after %d ms: %s%n", response.statusCode(),
                elapsedMs, response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("data").get("patientSummary").isNull()).isTrue();
        assertThat(body.get("errors")).hasSize(1);
        JsonNode error = body.get("errors").get(0);
        assertThat(error.get("message").asString()).isEqualTo("Service temporarily unavailable");
        assertThat(error.get("extensions").get("code").asString()).isEqualTo("UNAVAILABLE");
        assertThat(error.get("extensions").get("classification").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.body()).doesNotContain("Exception", "SQL", "jdbc", "Hikari", "\tat ");
        assertThat(elapsedMs).as("≈ connection-timeout 2000 ms").isBetween(1_900L, 3_000L);

        assertThat(graphQlUnavailable() - errorsBefore).as("lab.errors{GRAPHQL,UNAVAILABLE}").isEqualTo(1);
        assertThat(serviceUnavailable() - callsBefore).as("lab.service.calls{getPatientSummary,UNAVAILABLE}").isEqualTo(1);
        ArgumentCaptor<Throwable> cause = ArgumentCaptor.forClass(Throwable.class);
        verify(recorder, times(1)).record(eq(Protocol.GRAPHQL), any(ErrorCode.class), cause.capture());
        verify(recorder).record(eq(Protocol.GRAPHQL), eq(ErrorCode.UNAVAILABLE), any());
        assertThat(cause.getValue()).isInstanceOf(ServiceUnavailableException.class)
                .hasCauseInstanceOf(CannotCreateTransactionException.class);
        assertThat(PgSideConnections.causeChain(cause.getValue())).anyMatch(SQLTransientConnectionException.class::isInstance);

        assertThat(JSON.readTree(s1().body()).has("errors")).as("the pool serves again once the connection is back").isFalse();
    }

    private HttpResponse<String> s1() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GraphQlDocuments.body("S1", "3"))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private double graphQlUnavailable() {
        return registry.get("lab.errors").tag("protocol", "GRAPHQL").tag("code", "UNAVAILABLE").counter().count();
    }

    private double serviceUnavailable() {
        return registry.get("lab.service.calls").tag("method", "getPatientSummary").tag("outcome", "UNAVAILABLE")
                .timer().count();
    }
}
