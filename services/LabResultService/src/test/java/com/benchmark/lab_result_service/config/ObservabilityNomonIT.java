package com.benchmark.lab_result_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import com.benchmark.lab_result_service.testsupport.GraphQlDocuments;
import com.benchmark.lab_result_service.testsupport.HttpProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.noop.NoopCounter;
import io.micrometer.core.instrument.noop.NoopTimer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract §10 P6 {@code ObservabilityNomonIT}, profiles {@code bench,bench-nomon} (over {@code test}, P1 fixture):
 * {@code /actuator/prometheus} answers 404 on the management port that still serves health and info (so the 404 is the
 * missing endpoint, not a wrong port); no Prometheus registry; every meter, {@code lab.service.calls} and
 * {@code lab.errors} included, is a no-op and none is registered, also after REST and GraphQL requests; every
 * observation is a no-op; the service still answers on both protocols.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"bench", "bench-nomon"})                  // after the inherited "test", in the production order
@Sql(scripts = "/fixtures/p1-fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ObservabilityNomonIT extends AbstractPostgresIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort int port;
    @LocalManagementPort int managementPort;
    @Autowired ApplicationContext context;
    @Autowired MeterRegistry registry;
    @Autowired ObservationRegistry observations;
    @Autowired Environment environment;

    private final HttpProbe http = new HttpProbe();

    @AfterEach
    void close() {
        http.close();
    }

    @Test
    void prometheusIsGoneButTheManagementPortStillAnswers() throws Exception {
        assertThat(http.get(managementPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
        assertThat(http.get(managementPort, "/actuator/health/readiness").statusCode())
                .as("control: same port, readiness").isEqualTo(200);
        assertThat(http.get(managementPort, "/actuator/info").statusCode()).as("control: same port, info").isEqualTo(200);
        assertThat(http.get(port, "/actuator/prometheus").statusCode()).as("not on the server port either").isEqualTo(404);
        assertThat(context.getBeansOfType(PrometheusMeterRegistry.class)).isEmpty();
    }

    @Test
    void theServiceStillAnswersAndNoMeterIsRecorded() throws Exception {
        HttpResponse<String> rest = http.get(port, "/api/v1/patients/3/summary");
        assertThat(rest.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(rest.body()).path("patient").path("id").asLong()).isEqualTo(3);
        HttpResponse<String> graphQl = http.graphQl(port, GraphQlDocuments.body("S2", "1"));
        assertThat(graphQl.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(graphQl.body()).path("data").path("patientLabHistory").path("reports").size())
                .isEqualTo(100);
        assertThat(http.get(port, "/api/v1/patients/1001/summary").statusCode()).as("an error path too").isEqualTo(404);

        List<String> registered = registry.getMeters().stream().map(m -> m.getId().getName()).toList();
        assertThat(registered).as("management.metrics.enable.all=false denies every meter").isEmpty();

        // the service's lab.service.calls and the recorder's lab.errors resolve to no-op meters
        Timer serviceCalls = Timer.builder("lab.service.calls").tag("method", "getPatientSummary")
                .tag("outcome", "SUCCESS").register(registry);
        assertThat(serviceCalls).isInstanceOf(NoopTimer.class);
        serviceCalls.record(java.time.Duration.ofMillis(5));
        assertThat(serviceCalls.count()).isZero();
        Counter errors = Counter.builder("lab.errors").tag("protocol", "REST").tag("code", "PATIENT_NOT_FOUND")
                .register(registry);
        assertThat(errors).isInstanceOf(NoopCounter.class);
        assertThat(registry.find("lab.service.calls").meters()).isEmpty();
        assertThat(registry.getMeters()).extracting(Meter::getId).isEmpty();
    }

    @Test
    void everyObservationIsANoop() {
        for (String name : List.of("http.server.requests", "graphql.request", "graphql.datafetcher",
                "grpc.server", "lab.anything")) {
            assertThat(Observation.createNotStarted(name, observations).isNoop()).as(name).isTrue();
        }
        assertThat(environment.getProperty("spring.grpc.server.observation.enabled", Boolean.class)).isFalse();
    }
}
