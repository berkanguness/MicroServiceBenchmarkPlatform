package com.benchmark.lab_result_service.config;

import com.benchmark.lab_result_service.seed.DatasetManifest;
import com.benchmark.lab_result_service.seed.DatasetVerifier;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The {@code lab.*} part of {@code GET :8081/actuator/info} (contract §11.2): the dataset a run measured and the
 * runtime settings it ran with. QA saves the whole response next to every run's raw output.
 *
 * <ul>
 *   <li>{@code lab.dataset.*}: one {@code dataset_meta} row (§4) plus the startup {@link DatasetVerifier} result. Read
 *       on each {@code /actuator/info} call and on nothing else: one short autocommit query on a pool connection, so
 *       it adds no work to measured requests unless the endpoint itself is called during a run. With no
 *       {@code ym491} row, {@code present=false} and the dataset values are {@code null}, which the actuator's JSON
 *       leaves out (only {@code present} and {@code verifyMode} remain); a database error sets {@code error} (the
 *       exception class only) instead of failing the endpoint.</li>
 *   <li>{@code lab.runtime.*}: active profiles, the bound {@code server.tomcat.*}/{@code server.*} values, Hikari's
 *       effective pool settings, gRPC properties and JVM facts. {@code grpc.executorThreads} reports the spring-grpc
 *       default until Backend B's {@code GrpcServerExecutorProvider} exists (§12 Q3).</li>
 * </ul>
 * JVM input arguments are reported with the value of any {@code -D…password/secret/token/credential…=} redacted.
 */
@Component
public class LabInfoContributor implements InfoContributor {

    /** §11.2 / §12 Q3: what {@code lab.runtime.grpc.executorThreads} says until B's executor provider exists. */
    static final String GRPC_DEFAULT_EXECUTOR = "default (unbounded cached pool)";
    static final String REDACTED = "******";

    private static final Pattern SECRET_SYSTEM_PROPERTY =
            Pattern.compile("(?i)^(-D[^=]*(password|secret|token|credential)[^=]*=).*$");

    private static final String DATASET_QUERY = "select name, generator_version, seed, patient_count, report_count,"
            + " item_count, checksum_sha256, seeded_at from dataset_meta where name = ?";

    private final Environment environment;
    private final ObjectProvider<DataSource> dataSource;
    private final ObjectProvider<DatasetVerifier> verifier;
    private final ObjectProvider<ServerProperties> serverProperties;
    private final ObjectProvider<TomcatServerProperties> tomcatProperties;

    public LabInfoContributor(Environment environment, ObjectProvider<DataSource> dataSource,
                              ObjectProvider<DatasetVerifier> verifier, ObjectProvider<ServerProperties> serverProperties,
                              ObjectProvider<TomcatServerProperties> tomcatProperties) {
        this.environment = environment;
        this.dataSource = dataSource;
        this.verifier = verifier;
        this.serverProperties = serverProperties;
        this.tomcatProperties = tomcatProperties;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> lab = new LinkedHashMap<>();
        lab.put("dataset", dataset());
        lab.put("runtime", runtime());
        builder.withDetail("lab", lab);
    }

    // --- lab.dataset ------------------------------------------------------------------------------------------------

    Map<String, @Nullable Object> dataset() {
        Map<String, @Nullable Object> d = new LinkedHashMap<>();
        for (String key : List.of("name", "generatorVersion", "seed", "patientCount", "reportCount", "itemCount",
                "checksumSha256", "seededAt")) {
            d.put(key, null);
        }
        d.put("present", false);
        DatasetVerifier v = verifier.getIfAvailable();
        d.put("verifyMode", v == null ? null : v.mode().name().toLowerCase(Locale.ROOT));
        Instant verifiedAt = v == null ? null : v.verifiedAt();
        d.put("verifiedAt", verifiedAt == null ? null : verifiedAt.toString());

        DataSource ds = dataSource.getIfAvailable();
        if (ds == null) {
            d.put("error", "no DataSource");
            return d;
        }
        try {
            List<Map<String, Object>> rows = new JdbcTemplate(ds).queryForList(DATASET_QUERY,
                    DatasetManifest.expected().name());
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.getFirst();
                d.put("name", row.get("name"));
                d.put("generatorVersion", ((Number) row.get("generator_version")).intValue());
                d.put("seed", ((Number) row.get("seed")).longValue());
                d.put("patientCount", ((Number) row.get("patient_count")).intValue());
                d.put("reportCount", ((Number) row.get("report_count")).intValue());
                d.put("itemCount", ((Number) row.get("item_count")).intValue());
                d.put("checksumSha256", row.get("checksum_sha256"));
                d.put("seededAt", instant(row.get("seeded_at")));
                d.put("present", true);
            }
        } catch (DataAccessException e) {
            d.put("error", e.getClass().getSimpleName());     // never the message (could carry SQL or host names)
        }
        return d;
    }

    private static @Nullable String instant(@Nullable Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toInstant().toString();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant().toString();
        }
        return value == null ? null : value.toString();
    }

    // --- lab.runtime ------------------------------------------------------------------------------------------------

    Map<String, Object> runtime() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("activeProfiles", List.of(environment.getActiveProfiles()));
        r.put("tomcat", tomcat());
        ServerProperties server = serverProperties.getIfAvailable(
                () -> Binder.get(environment).bindOrCreate("server", ServerProperties.class));
        r.put("http2Enabled", server.getHttp2().isEnabled());
        r.put("compressionEnabled", server.getCompression().getEnabled());
        r.put("virtualThreads", environment.getProperty("spring.threads.virtual.enabled", Boolean.class, false));
        r.put("hikari", hikari());
        r.put("grpc", grpc());
        r.put("jvm", jvm());
        return r;
    }

    private Map<String, @Nullable Object> tomcat() {
        TomcatServerProperties tomcat = tomcatProperties.getIfAvailable(
                () -> Binder.get(environment).bindOrCreate("server.tomcat", TomcatServerProperties.class));
        Map<String, @Nullable Object> t = new LinkedHashMap<>();
        t.put("threadsMax", tomcat.getThreads().getMax());
        t.put("maxKeepAliveRequests", tomcat.getMaxKeepAliveRequests());
        Duration keepAlive = tomcat.getKeepAliveTimeout();
        t.put("keepAliveTimeout", keepAlive == null ? null : keepAlive.toString());   // null: Tomcat uses connectionTimeout
        t.put("acceptCount", tomcat.getAcceptCount());
        t.put("maxConnections", tomcat.getMaxConnections());
        t.put("maxQueueCapacity", tomcat.getThreads().getMaxQueueCapacity());
        return t;
    }

    private Map<String, @Nullable Object> hikari() {
        Map<String, @Nullable Object> h = new LinkedHashMap<>();
        HikariDataSource hikari = hikariDataSource();
        h.put("maximumPoolSize", hikari == null ? null : hikari.getMaximumPoolSize());
        h.put("minimumIdle", hikari == null ? null : hikari.getMinimumIdle());
        h.put("connectionTimeoutMs", hikari == null ? null : hikari.getConnectionTimeout());
        h.put("autoCommit", hikari == null ? null : hikari.isAutoCommit());
        return h;
    }

    private @Nullable HikariDataSource hikariDataSource() {
        DataSource ds = dataSource.getIfAvailable();
        if (ds instanceof HikariDataSource hikari) {
            return hikari;
        }
        try {
            return ds != null && ds.isWrapperFor(HikariDataSource.class) ? ds.unwrap(HikariDataSource.class) : null;
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    private Map<String, @Nullable Object> grpc() {
        Map<String, @Nullable Object> g = new LinkedHashMap<>();
        g.put("port", environment.getProperty("spring.grpc.server.port", Integer.class));
        g.put("reflectionEnabled", environment.getProperty("spring.grpc.server.reflection.enabled", Boolean.class, true));
        g.put("executorThreads", GRPC_DEFAULT_EXECUTOR);
        return g;
    }

    private static Map<String, Object> jvm() {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("inputArguments", ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .map(LabInfoContributor::redact).toList());
        j.put("gcNames", ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).toList());
        j.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        j.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        return j;
    }

    static String redact(String argument) {
        return SECRET_SYSTEM_PROPERTY.matcher(argument).replaceFirst("$1" + REDACTED);
    }
}
