package com.benchmark.lab_result_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.benchmark.lab_result_service.seed.DatasetManifest;
import com.benchmark.lab_result_service.testsupport.AbstractPostgresIT;
import com.benchmark.lab_result_service.testsupport.HttpProbe;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract §10 P6 {@code InfoEndpointIT}: {@code GET /actuator/info} on the management port contains every key of
 * §11.2, on the real seeded dataset (P2 seeder, {@code cheap} verification) and the {@code bench} settings (profiles
 * {@code test,bench,seed}, the production order with the test datasource first). The key list below is written
 * independently of {@link LabInfoContributor}, so a dropped key fails here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(value = {"test", "bench", "seed"}, inheritProfiles = false)
@TestPropertySource(properties = "app.dataset.verify-mode=cheap")
class InfoEndpointIT extends AbstractPostgresIT {

    /** Every §11.2 key, as a JSON path below the {@code /actuator/info} root. */
    static final List<String> KEYS = List.of(
            "build.group", "build.artifact", "build.name", "build.version", "build.time", "build.git.sha",
            "java.version", "java.vendor.name", "java.runtime.version", "java.jvm.name",
            "os.name", "os.version", "os.arch",
            "process.pid", "process.cpus", "process.memory.heap.max",
            "lab.dataset.name", "lab.dataset.generatorVersion", "lab.dataset.seed", "lab.dataset.patientCount",
            "lab.dataset.reportCount", "lab.dataset.itemCount", "lab.dataset.checksumSha256", "lab.dataset.seededAt",
            "lab.dataset.verifyMode", "lab.dataset.verifiedAt",
            "lab.runtime.activeProfiles",
            "lab.runtime.tomcat.threadsMax", "lab.runtime.tomcat.maxKeepAliveRequests",
            "lab.runtime.tomcat.keepAliveTimeout", "lab.runtime.tomcat.acceptCount",
            "lab.runtime.tomcat.maxConnections", "lab.runtime.tomcat.maxQueueCapacity",
            "lab.runtime.http2Enabled", "lab.runtime.compressionEnabled", "lab.runtime.virtualThreads",
            "lab.runtime.hikari.maximumPoolSize", "lab.runtime.hikari.minimumIdle",
            "lab.runtime.hikari.connectionTimeoutMs", "lab.runtime.hikari.autoCommit",
            "lab.runtime.grpc.port", "lab.runtime.grpc.reflectionEnabled", "lab.runtime.grpc.executorThreads",
            "lab.runtime.jvm.inputArguments", "lab.runtime.jvm.gcNames", "lab.runtime.jvm.availableProcessors",
            "lab.runtime.jvm.maxHeapBytes");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalManagementPort int managementPort;

    @Test
    void infoContainsEveryContractKey() throws Exception {
        JsonNode info = fetch();
        List<String> missing = new ArrayList<>();
        for (String key : KEYS) {
            JsonNode node = at(info, key);
            if (node.isMissingNode() || node.isNull()) {
                missing.add(key);
            }
        }
        assertThat(missing).as("§11.2 keys missing or null in %s", info).isEmpty();
    }

    @Test
    void datasetAndRuntimeValuesAreTheRealOnes() throws Exception {
        JsonNode lab = fetch().path("lab");
        DatasetManifest expected = DatasetManifest.expected();
        JsonNode d = lab.path("dataset");
        assertThat(d.path("present").asBoolean()).isTrue();
        assertThat(d.path("name").asString()).isEqualTo(expected.name());
        assertThat(d.path("generatorVersion").asInt()).isEqualTo(expected.generatorVersion());
        assertThat(d.path("seed").asLong()).isEqualTo(expected.seed());
        assertThat(d.path("patientCount").asInt()).isEqualTo(1000);
        assertThat(d.path("reportCount").asInt()).isEqualTo(10080);
        assertThat(d.path("itemCount").asInt()).isEqualTo(100800);
        assertThat(d.path("checksumSha256").asString()).isEqualTo(expected.checksumSha256());
        assertThat(d.path("verifyMode").asString()).isEqualTo("cheap");
        Instant seededAt = Instant.parse(d.path("seededAt").asString());
        Instant verifiedAt = Instant.parse(d.path("verifiedAt").asString());
        assertThat(verifiedAt).isAfterOrEqualTo(seededAt.minusSeconds(5)).isBeforeOrEqualTo(Instant.now());

        JsonNode r = lab.path("runtime");
        assertThat(r.path("activeProfiles").valueStream().map(JsonNode::asString)).containsExactly("test", "bench", "seed");
        assertThat(r.path("tomcat").path("threadsMax").asInt()).isEqualTo(200);
        assertThat(r.path("tomcat").path("maxKeepAliveRequests").asInt()).isEqualTo(-1);
        assertThat(r.path("tomcat").path("keepAliveTimeout").asString()).isEqualTo("PT1M");
        assertThat(r.path("http2Enabled").asBoolean(true)).isFalse();
        assertThat(r.path("compressionEnabled").asBoolean(true)).isFalse();
        assertThat(r.path("virtualThreads").asBoolean(true)).isFalse();
        assertThat(r.path("hikari").path("maximumPoolSize").asInt()).isEqualTo(20);
        assertThat(r.path("hikari").path("minimumIdle").asInt()).isEqualTo(20);
        assertThat(r.path("hikari").path("connectionTimeoutMs").asLong()).isEqualTo(2000);
        assertThat(r.path("hikari").path("autoCommit").asBoolean()).isTrue();
        assertThat(r.path("grpc").path("port").asInt()).as("test profile: random").isZero();
        assertThat(r.path("grpc").path("reflectionEnabled").asBoolean(true)).as("bench: RFC 0001 §3.3").isFalse();
        assertThat(r.path("grpc").path("executorThreads").asString()).isEqualTo("default (unbounded cached pool)");
        assertThat(r.path("jvm").path("availableProcessors").asInt()).isEqualTo(Runtime.getRuntime().availableProcessors());
        assertThat(r.path("jvm").path("maxHeapBytes").asLong()).isPositive();
        assertThat(r.path("jvm").path("gcNames").isArray()).isTrue();
        assertThat(r.path("jvm").path("inputArguments").isArray()).isTrue();
    }

    private JsonNode fetch() throws Exception {
        try (HttpProbe http = new HttpProbe()) {
            HttpResponse<String> response = http.get(managementPort, "/actuator/info");
            assertThat(response.statusCode()).isEqualTo(200);
            return JSON.readTree(response.body());
        }
    }

    /** {@code a.b.c} → {@code /a/b/c}; the build-info key {@code git.sha} may also appear unsplit. */
    private static JsonNode at(JsonNode root, String dotted) {
        JsonNode node = root.at("/" + dotted.replace('.', '/'));
        if (node.isMissingNode() && dotted.equals("build.git.sha")) {
            node = root.path("build").path("git.sha");
        }
        return node;
    }
}
