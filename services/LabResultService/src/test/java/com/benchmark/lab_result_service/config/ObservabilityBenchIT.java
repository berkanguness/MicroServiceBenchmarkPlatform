package com.benchmark.lab_result_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import com.benchmark.lab_result_service.testsupport.GraphQlDocuments;
import com.benchmark.lab_result_service.testsupport.HttpProbe;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract §10 P6 {@code ObservabilityBenchIT}, profile {@code bench} (over {@code test}, P1 fixture, no dataset
 * manifest): every §11.1 meter is scraped from {@code /actuator/prometheus} on the <em>management</em> port with the
 * common tag {@code application="lab-result-service"}; the API connector really runs with
 * {@code maxKeepAliveRequests = -1} and the bench profile binds {@code server.tomcat.keep-alive-timeout = 60s} (R1; see
 * {@link #tomcatRunsWithTheBenchKeepAliveSettings}); the server port serves no {@code /actuator/**}; readiness answers
 * on the management port; {@code /actuator/info} does not fail without a {@code dataset_meta} row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("bench")                                   // after the inherited "test": bench wins where both set a key
@Sql(scripts = "/fixtures/p1-fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ObservabilityBenchIT extends AbstractPostgresIT {

    static final String APPLICATION_TAG = "application=\"lab-result-service\"";

    /** §10 P6: the Prometheus names (prefixes of the exported series) that must be present under bench. */
    static final List<String> REQUIRED = List.of("http_server_requests_seconds", "graphql_request_seconds",
            "hikaricp_connections", "lab_service_calls_seconds", "lab_errors_total", "jvm_memory_used_bytes",
            "process_cpu_usage");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort int port;
    @LocalManagementPort int managementPort;
    @Autowired ApplicationContext context;
    @Autowired Environment environment;

    private final HttpProbe http = new HttpProbe();

    @AfterEach
    void close() {
        http.close();
    }

    @Test
    void prometheusOnTheManagementPortHasEveryRequiredMeterWithTheApplicationTag() throws Exception {
        // one REST S1 and one GraphQL S1, so the observation-based meters exist
        assertThat(http.get(port, "/api/v1/patients/3/summary").statusCode()).isEqualTo(200);
        HttpResponse<String> graphQl = http.graphQl(port, GraphQlDocuments.body("S1", "3"));
        assertThat(graphQl.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(graphQl.body()).path("data").path("patientSummary").path("patient").path("id")
                .asString()).isEqualTo("3");

        HttpResponse<String> scrape = http.get(managementPort, "/actuator/prometheus");
        assertThat(scrape.statusCode()).isEqualTo(200);
        String body = scrape.body();
        for (String meter : REQUIRED) {
            Pattern series = Pattern.compile("(?m)^" + meter + "[a-z_]*\\{[^}]*" + Pattern.quote(APPLICATION_TAG));
            assertThat(series.matcher(body).find()).as("%s with %s", meter, APPLICATION_TAG).isTrue();
        }
        // the shared service timer saw both protocols' S1 (protocol-neutral, one sample per call)
        assertThat(sample(body, "lab_service_calls_seconds_count",
                "method=\"getPatientSummary\"", "outcome=\"SUCCESS\"")).isGreaterThanOrEqualTo(2.0);
        assertThat(sample(body, "http_server_requests_seconds_count", "uri=\"/graphql\"")).isGreaterThanOrEqualTo(1.0);
        assertThat(sample(body, "lab_errors_total", "protocol=\"REST\"", "code=\"PATIENT_NOT_FOUND\"")).isEqualTo(0.0);
        assertThat(body).as("no percentile histograms (§8.1 notes)").doesNotContain("_bucket{");
        // every series carries the common tag
        body.lines().filter(line -> !line.startsWith("#") && line.contains("{"))
                .forEach(line -> assertThat(line).contains(APPLICATION_TAG));
    }

    /**
     * R1 keep-alive. {@code maxKeepAliveRequests = -1} differs from Tomcat's default (100), so the connector check alone
     * proves it. The connector's 60000 ms keep-alive timeout does <em>not</em>: Tomcat 11 falls back to its connection
     * timeout, whose default is also 60 s. So the test also requires {@code server.tomcat.keep-alive-timeout} to be bound
     * to 60 s in the environment (what Boot's Tomcat customizer applies); without the bench key it is unbound and this
     * fails.
     */
    @Test
    void tomcatRunsWithTheBenchKeepAliveSettings() {
        assertThat(Binder.get(environment).bind("server.tomcat.keep-alive-timeout", Duration.class).orElse(null))
                .as("server.tomcat.keep-alive-timeout bound by the bench profile").isEqualTo(Duration.ofSeconds(60));
        TomcatWebServer server = (TomcatWebServer) ((WebServerApplicationContext) context).getWebServer();
        ProtocolHandler handler = server.getTomcat().getConnector().getProtocolHandler();
        assertThat(handler).isInstanceOf(AbstractHttp11Protocol.class);
        AbstractHttp11Protocol<?> http11 = (AbstractHttp11Protocol<?>) handler;
        assertThat(http11.getMaxKeepAliveRequests()).isEqualTo(-1);
        assertThat(http11.getKeepAliveTimeout()).as("effective; equals Tomcat 11's default").isEqualTo(60_000);
        assertThat(server.getPort()).isEqualTo(port).isNotEqualTo(managementPort);
    }

    @Test
    void actuatorIsOnlyOnTheManagementPort() throws Exception {
        assertThat(http.get(port, "/api/v1/patients/3/summary").statusCode())
                .as("control: the server port is right").isEqualTo(200);
        for (String path : List.of("/actuator", "/actuator/health", "/actuator/health/readiness", "/actuator/info",
                "/actuator/prometheus")) {
            assertThat(http.get(port, path).statusCode()).as("server port %s", path).isEqualTo(404);
        }
        HttpResponse<String> readiness = http.get(managementPort, "/actuator/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(readiness.body()).path("status").asString()).isEqualTo("UP");
        assertThat(http.get(managementPort, "/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(http.get(managementPort, "/api/v1/patients/3/summary").statusCode())
                .as("the management port serves no API").isEqualTo(404);
    }

    /** Brief: {@code /actuator/info} must not fail on an empty {@code dataset_meta}; this is what it returns then. */
    @Test
    void infoAnswersWithoutADatasetManifest() throws Exception {
        HttpResponse<String> info = http.get(managementPort, "/actuator/info");
        assertThat(info.statusCode()).isEqualTo(200);
        JsonNode dataset = JSON.readTree(info.body()).path("lab").path("dataset");
        assertThat(dataset.path("present").asBoolean(true)).isFalse();
        // the actuator's JSON leaves out null members, so without a manifest row the dataset keys are absent
        for (String key : List.of("name", "generatorVersion", "seed", "patientCount", "reportCount", "itemCount",
                "checksumSha256", "seededAt", "verifiedAt")) {
            assertThat(dataset.has(key)).as(key).isFalse();
        }
        assertThat(dataset.path("verifyMode").asString()).isEqualTo("off");
        assertThat(dataset.has("error")).isFalse();
    }

    @Test
    void noCorsUnderBench() {
        assertThat(context.getBeansOfType(CorsConfig.class)).isEmpty();
    }

    /** The value of the first sample of {@code name} whose labels contain all of {@code labels}. */
    static double sample(String body, String name, String... labels) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\{([^}]*)\\} (\\S+)$").matcher(body);
        while (m.find()) {
            String labelSet = m.group(1);
            if (java.util.Arrays.stream(labels).allMatch(labelSet::contains)) {
                return Double.parseDouble(m.group(2));
            }
        }
        throw new AssertionError("no sample " + name + " with " + List.of(labels));
    }
}
