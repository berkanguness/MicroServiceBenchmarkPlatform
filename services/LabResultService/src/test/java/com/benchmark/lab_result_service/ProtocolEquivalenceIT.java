package com.benchmark.lab_result_service;

import static org.assertj.core.api.Assertions.assertThat;

import com.benchmark.lab_result_service.core.api.service.LabResultQueryService;
import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import com.benchmark.lab_result_service.testsupport.CanonicalTree;
import com.benchmark.lab_result_service.testsupport.CanonicalTree.IdEncoding;
import com.benchmark.lab_result_service.testsupport.GraphQlDocuments;
import com.benchmark.lab_result_service.testsupport.SqlCapture;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contract §10 P7a {@code ProtocolEquivalenceIT} on the real seeded dataset (P2 seeder, {@code seed} profile, cheap
 * verification), real HTTP stack ({@code RANDOM_PORT}, JDK {@link HttpClient} on HTTP/1.1 as k6 uses it).
 *
 * <p>For patients 1, 2, 3 and 500, REST JSON and GraphQL {@code data} of S1 and S2 become one {@link CanonicalTree}
 * (strict per-member types: ids → long, ISO strings kept verbatim, doubles compared exactly) and must be equal to each
 * other <b>and</b> to the tree built directly from the core.api record of the same service call; S2b must equal the
 * {@code testName}/{@code resultValue} projection of REST S2. Shape guards make sure the trees are not trivially
 * empty. Every request causes exactly one {@link LabResultQueryService} call (all methods counted, via the
 * {@code lab.service.calls} timer) and the R9/§5.3 statement count (Hibernate {@link Statistics}, global to the one
 * session factory, so the same scope for both protocols), and both protocols send the identical SQL text
 * ({@link SqlCapture}). The absent patient 1001 is the R9 "1 statement" case for both protocols.
 *
 * <p>{@link #payloadSizeTable()} writes {@code target/payload-sizes.json} (§11.3, R2): the UTF-8 bytes of the HTTP
 * bodies as actually sent and received ({@code byte[]} on both sides, never {@code String.length()}). gRPC rows are
 * P7b. {@code gitSha} is {@code build.git.sha} of {@code META-INF/build-info.properties} (§9.2: {@code -Dgit.sha},
 * default {@code unknown}), the same value {@code /actuator/info} reports. The file carries no timestamp, so two runs
 * on the same commit and dataset produce identical bytes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(value = {"test", "seed"}, inheritProfiles = false)
@TestPropertySource(properties = {
        "app.dataset.verify-mode=cheap",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.benchmark.lab_result_service.testsupport.SqlCapture"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProtocolEquivalenceIT extends AbstractPostgresIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path PAYLOAD_SIZES = Path.of(System.getProperty("basedir", ".")).resolve("target/payload-sizes.json");

    @LocalServerPort int port;
    @Autowired EntityManagerFactory emf;
    @Autowired MeterRegistry registry;
    @Autowired LabResultQueryService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectProvider<BuildProperties> buildProperties;

    private HttpClient http;
    private Statistics stats;

    @BeforeAll
    void client() {
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    void closeClient() {
        http.close();
    }

    @BeforeEach
    void statistics() {
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        assertThat(stats.isStatisticsEnabled()).as("hibernate.generate_statistics in the test profile").isTrue();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Equivalence

    @ParameterizedTest(name = "patient {0}")
    @ValueSource(longs = {1, 2, 3, 500})
    void restAndGraphQlYieldOneCanonicalTree(long patientId) throws Exception {
        int statements = patientId == 2 ? 2 : 3; // R9 / §5.3: no reports 2, with reports 3

        // S1
        Exchange restS1 = rest("S1", patientId);
        Exchange gqlS1 = graphQl("S1", patientId);
        Object coreS1 = CanonicalTree.of(service.getPatientSummary(patientId)); // outside every measured window
        Object restS1Tree = CanonicalTree.fromJson(restS1.json(), IdEncoding.JSON_NUMBER);
        Object gqlS1Tree = CanonicalTree.fromJson(gqlS1.data("patientSummary"), IdEncoding.ID_STRING);
        assertThat(gqlS1Tree).as("S1 GraphQL == REST, patient %d", patientId).isEqualTo(restS1Tree);
        assertThat(restS1Tree).as("S1 REST == core record, patient %d", patientId).isEqualTo(coreS1);
        assertCost(restS1, gqlS1, "getPatientSummary", statements);

        // S2
        Exchange restS2 = rest("S2", patientId);
        Exchange gqlS2 = graphQl("S2", patientId);
        Object coreS2 = CanonicalTree.of(service.getPatientLabHistory(patientId));
        Object restS2Tree = CanonicalTree.fromJson(restS2.json(), IdEncoding.JSON_NUMBER);
        Object gqlS2Tree = CanonicalTree.fromJson(gqlS2.data("patientLabHistory"), IdEncoding.ID_STRING);
        assertThat(gqlS2Tree).as("S2 GraphQL == REST, patient %d", patientId).isEqualTo(restS2Tree);
        assertThat(restS2Tree).as("S2 REST == core record, patient %d", patientId).isEqualTo(coreS2);
        assertCost(restS2, gqlS2, "getPatientLabHistory", statements);

        // S2b: same service call and statements as S2; data = projection of REST S2
        Exchange gqlS2b = graphQl("S2b", patientId);
        Object gqlS2bTree = CanonicalTree.fromJson(gqlS2b.data("patientLabHistory"), IdEncoding.ID_STRING);
        assertThat(gqlS2bTree).as("S2b GraphQL == projection of REST S2, patient %d", patientId)
                .isEqualTo(CanonicalTree.s2bProjection(restS2Tree));
        assertCost(restS2, gqlS2b, "getPatientLabHistory", statements);

        assertShape(patientId, restS1.json(), restS2.json());
    }

    /** Self-falsification: the equal trees above are the dataset of §6.1/§6.2, not two empty or truncated bodies. */
    private static void assertShape(long patientId, JsonNode s1, JsonNode s2) {
        assertThat(s1.path("patient").path("id").longValue()).isEqualTo(patientId);
        assertThat(s2.path("patient").path("id").longValue()).isEqualTo(patientId);
        assertThat(s1.path("patient").path("fullName").asString()).isNotBlank();
        JsonNode reports = s2.path("reports");
        switch ((int) patientId) {
            case 2 -> {
                assertThat(s1.get("latestReport").isNull()).as("patient 2: latestReport null").isTrue();
                assertThat(reports.isArray() && reports.isEmpty()).as("patient 2: reports []").isTrue();
            }
            case 1 -> assertReports(s1, reports, 100, 1L, 1L);
            // §6.2: patient p ≥ 3 → reports 101 + (p − 3) × 10 …; its latest gets the lowest id
            default -> {
                long latest = 101 + (patientId - 3) * 10;
                assertReports(s1, reports, 10, latest, null);
            }
        }
    }

    private static void assertReports(JsonNode s1, JsonNode reports, int count, long latestId, Long firstItemId) {
        assertThat(reports.size()).isEqualTo(count);
        reports.forEach(r -> assertThat(r.path("items").size()).isEqualTo(10));
        JsonNode latest = s1.path("latestReport");
        assertThat(latest.path("id").longValue()).isEqualTo(latestId);
        assertThat(latest.path("items").size()).isEqualTo(10);
        if (firstItemId != null) {
            assertThat(latest.path("items").get(0).path("id").longValue()).isEqualTo(firstItemId);
        }
        assertThat(reports.get(0).path("id").longValue()).as("history order: latest first").isEqualTo(latestId);
    }

    /** R9 "missing patient: 1 statement" on both protocols, one service call each, the same error code. */
    @Test
    void absentPatientIsTheSameOutcomeOnBothProtocols() throws Exception {
        for (String scenario : List.of("S1", "S2", "S2b")) {
            String method = scenario.equals("S1") ? "getPatientSummary" : "getPatientLabHistory";
            String root = scenario.equals("S1") ? "patientSummary" : "patientLabHistory";
            Exchange rest = scenario.equals("S2b") ? rest("S2", 1001) : rest(scenario, 1001);
            Exchange gql = graphQl(scenario, 1001);

            assertThat(rest.status()).isEqualTo(404);
            assertThat(rest.json().path("code").asString()).isEqualTo("PATIENT_NOT_FOUND");
            JsonNode body = gql.json();
            assertThat(gql.status()).isEqualTo(200);
            assertThat(body.path("data").get(root).isNull()).as("%s data.%s null", scenario, root).isTrue();
            assertThat(body.path("errors")).hasSize(1);
            assertThat(body.path("errors").get(0).path("extensions").path("code").asString())
                    .isEqualTo("PATIENT_NOT_FOUND");
            assertCost(rest, gql, method, 1);
        }
    }

    private static void assertCost(Exchange rest, Exchange graphQl, String method, int statements) {
        assertThat(rest.statements()).as("REST %s statements (R9)", rest.label()).isEqualTo(statements);
        assertThat(graphQl.statements()).as("GraphQL %s statements == REST", graphQl.label())
                .isEqualTo(rest.statements());
        assertThat(graphQl.sql()).as("GraphQL %s SQL == REST SQL", graphQl.label()).isEqualTo(rest.sql());
        assertThat(rest.sql()).hasSize(statements);
        for (Exchange e : List.of(rest, graphQl)) {
            assertThat(e.serviceCalls()).as("%s %s: service calls, all methods", e.protocol(), e.label())
                    .containsExactly(Map.entry(method, 1L));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // payload-sizes.json (§11.3)

    private record Row(String scenario, String protocol, long patientId) {}

    /** §11.3 rows in contract order; gRPC rows are added by P7b. */
    private static final List<Row> ROWS = List.of(
            new Row("S1", "REST", 3), new Row("S1", "GRAPHQL", 3),
            new Row("S2", "REST", 1), new Row("S2", "GRAPHQL", 1),
            new Row("S2b", "GRAPHQL", 1));

    @Test
    void payloadSizeTable() throws Exception {
        ObjectNode table = JsonNodeFactory.instance.objectNode();
        table.put("schemaVersion", 1);
        table.put("generatedBy", "ProtocolEquivalenceIT");
        table.put("gitSha", gitSha());
        ObjectNode dataset = table.putObject("dataset");
        Map<String, Object> meta = jdbc.queryForMap(
                "select name, generator_version, seed, checksum_sha256 from dataset_meta where name = 'ym491'");
        dataset.put("name", (String) meta.get("name"));
        dataset.put("generatorVersion", ((Number) meta.get("generator_version")).intValue());
        dataset.put("seed", ((Number) meta.get("seed")).longValue());
        dataset.put("checksumSha256", ((String) meta.get("checksum_sha256")).trim());
        ArrayNode entries = table.putArray("entries");

        for (Row row : ROWS) {
            Exchange e = row.protocol().equals("REST") ? rest(row.scenario(), row.patientId())
                    : graphQl(row.scenario(), row.patientId());
            assertThat(e.status()).isEqualTo(200);
            assertThat(e.contentEncoding()).as("no compression (§7.1)").isEmpty();
            assertThat(e.contentType()).startsWith("application/json");
            assertBodyBytesAreUtf8Bytes(e);

            ObjectNode entry = entries.addObject();
            entry.put("scenario", row.scenario());
            entry.put("protocol", row.protocol());
            entry.put("operation", e.operation());
            entry.put("patientId", row.patientId());
            entry.put("requestBodyBytes", e.requestBody().length);
            entry.put("responseBodyBytes", e.responseBody().length);
            entry.put("measure", "utf8-http-body");
        }

        // Turkish names make UTF-8 bytes > UTF-16 chars; a String.length() table would be smaller
        assertThat(entries.get(0).path("responseBodyBytes").longValue()).as("REST S1 bytes").isGreaterThan(
                new String(rest("S1", 3).responseBody(), StandardCharsets.UTF_8).length());

        Files.createDirectories(PAYLOAD_SIZES.getParent());
        Files.writeString(PAYLOAD_SIZES, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(table) + "\n",
                StandardCharsets.UTF_8);

        JsonNode written = JSON.readTree(Files.readString(PAYLOAD_SIZES, StandardCharsets.UTF_8));
        assertThat(written).as("file == table (re-parsed: int vs long nodes)").isEqualTo(JSON.readTree(table.toString()));
        assertThat(written.path("dataset").path("seed").longValue()).isEqualTo(4912026L);
        assertThat(written.path("dataset").path("generatorVersion").intValue()).isEqualTo(1);
        assertThat(written.path("dataset").path("checksumSha256").asString()).matches("[0-9a-f]{64}");
        assertThat(written.path("entries")).hasSize(ROWS.size());
    }

    /**
     * The recorded sizes are the bytes on the wire: where the server sent {@code Content-Length} (S1, small bodies),
     * it must equal the byte count; the request bytes are exactly the {@code byte[]} handed to the client; a body
     * with non-ASCII text has more bytes than UTF-16 chars, and decoding/re-encoding is lossless (valid UTF-8).
     */
    private static void assertBodyBytesAreUtf8Bytes(Exchange e) {
        e.contentLength().ifPresent(length ->
                assertThat(e.responseBody().length).as("%s %s Content-Length", e.protocol(), e.label())
                        .isEqualTo(length));
        String text = new String(e.responseBody(), StandardCharsets.UTF_8);
        assertThat(text.getBytes(StandardCharsets.UTF_8)).isEqualTo(e.responseBody());
        if (text.chars().anyMatch(c -> c > 0x7F)) {
            assertThat(e.responseBody().length).isGreaterThan(text.length());
        }
    }

    private String gitSha() {
        BuildProperties build = buildProperties.getIfAvailable();
        String sha = build == null ? null : build.get("git.sha");
        return sha == null || sha.isBlank() ? "unknown" : sha;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // HTTP + measurement

    private record Exchange(String protocol, String label, String operation, int status, byte[] requestBody,
            byte[] responseBody, String contentType, Optional<String> contentEncoding,
            OptionalLong contentLength, long statements, List<String> sql, Map<String, Long> serviceCalls) {

        JsonNode json() {
            return JSON.readTree(new String(responseBody, StandardCharsets.UTF_8));
        }

        JsonNode data(String rootField) {
            JsonNode body = json();
            assertThat(body.has("errors")).as("%s %s errors: %s", protocol, label, body.path("errors")).isFalse();
            assertThat(status).isEqualTo(200);
            return body.path("data").path(rootField);
        }
    }

    private Exchange rest(String scenario, long patientId) throws IOException, InterruptedException {
        String path = "/api/v1/patients/" + patientId + (scenario.equals("S1") ? "/summary" : "/lab-history");
        HttpRequest request = HttpRequest.newBuilder(uri(path)).header("Accept", "application/json").GET()
                .timeout(Duration.ofSeconds(30)).build();
        return exchange("REST", scenario + " " + patientId, "GET " + path, request, new byte[0]);
    }

    private Exchange graphQl(String scenario, long patientId) throws IOException, InterruptedException {
        byte[] body = GraphQlDocuments.body(scenario, Long.toString(patientId)).getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(uri("/graphql"))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).timeout(Duration.ofSeconds(30)).build();
        return exchange("GRAPHQL", scenario + " " + patientId, "POST /graphql " + scenario, request, body);
    }

    private Exchange exchange(String protocol, String label, String operation, HttpRequest request, byte[] requestBody)
            throws IOException, InterruptedException {
        Map<String, Long> callsBefore = serviceCalls();
        SqlCapture.clear();
        long before = stats.getPrepareStatementCount();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        long statements = stats.getPrepareStatementCount() - before;
        List<String> sql = SqlCapture.statements();
        Map<String, Long> callsAfter = serviceCalls();
        Map<String, Long> delta = new TreeMap<>();
        callsAfter.forEach((method, count) -> {
            long d = count - callsBefore.getOrDefault(method, 0L);
            if (d != 0) {
                delta.put(method, d);
            }
        });
        return new Exchange(protocol, label, operation, response.statusCode(), requestBody, response.body(),
                response.headers().firstValue("Content-Type").orElse(""),
                response.headers().firstValue("Content-Encoding"),
                response.headers().firstValueAsLong("Content-Length"), statements, sql, delta);
    }

    /** {@code lab.service.calls} count per {@code method} tag, over every outcome. */
    private Map<String, Long> serviceCalls() {
        Map<String, Long> calls = new TreeMap<>();
        for (Timer t : registry.find("lab.service.calls").timers()) {
            calls.merge(t.getId().getTag("method"), t.count(), Long::sum);
        }
        return calls;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
