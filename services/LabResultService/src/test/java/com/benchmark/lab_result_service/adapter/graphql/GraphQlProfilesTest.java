package com.benchmark.lab_result_service.adapter.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.benchmark.lab_result_service.core.api.service.LabErrorRecorder;
import com.benchmark.lab_result_service.core.api.service.LabResultQueryService;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.graphql.autoconfigure.GraphQlProperties;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * GraphQL settings per profile (contract §8.1 rows {@code spring.graphql.*}): path {@code /graphql}; introspection and
 * GraphiQL off by default (and so in {@code bench}, which sets nothing else in P5), both on only in {@code docs}.
 * Introspection is checked by behaviour, the rest from the YAML files.
 *
 * <p>{@code docs} is checked through the bound property, not by an introspection query: Boot 4.1.1's
 * {@code GraphQlAutoConfiguration} calls graphql-java's JVM-wide {@code Introspection.enabledJvmWide(false)} when
 * introspection is disabled and never re-enables it ({@code REPRODUCED}, javap + a failing docs query after a default
 * context in the same JVM). One application context per JVM in production makes that harmless; in a test JVM it makes
 * a behaviour check of {@code docs} depend on test order.
 */
class GraphQlProfilesTest {

    private static final String INTROSPECTION = "{ __schema { queryType { name } } }";

    @Test
    void yamlFilesFollowTheContractTable() throws IOException {
        PropertySource<?> defaults = yaml("application.yaml");
        assertThat(defaults.getProperty("spring.graphql.http.path")).isEqualTo("/graphql");
        assertThat(defaults.getProperty("spring.graphql.schema.introspection.enabled")).isEqualTo(false);
        assertThat(defaults.getProperty("spring.graphql.graphiql.enabled")).isEqualTo(false);
        assertThat(defaults.getProperty("spring.graphql.cors.allowed-origins")).as("one CORS mechanism (CorsConfig)").isNull();

        PropertySource<?> docs = yaml("application-docs.yaml");
        assertThat(docs.getProperty("spring.graphql.schema.introspection.enabled")).isEqualTo(true);
        assertThat(docs.getProperty("spring.graphql.graphiql.enabled")).isEqualTo(true);
    }

    @Nested
    @GraphQlTest(LabGraphQlController.class)
    @Import(GraphQlExceptionResolver.class)
    class DefaultProfile {
        @Autowired GraphQlTester graphQl;
        @MockitoBean LabResultQueryService service;
        @MockitoBean LabErrorRecorder recorder;

        @Autowired GraphQlProperties properties;

        @Test
        void introspectionIsRejected() {
            assertThat(properties.getSchema().getIntrospection().isEnabled()).isFalse();
            graphQl.document(INTROSPECTION).execute().errors().satisfy(errors -> assertThat(errors).singleElement()
                    .satisfies(e -> assertThat(e.getErrorType()).hasToString("IntrospectionDisabled")));
        }
    }

    @Nested
    @GraphQlTest(LabGraphQlController.class)
    @Import(GraphQlExceptionResolver.class)
    @ActiveProfiles("docs")
    class DocsProfile {
        @Autowired GraphQlProperties properties;
        @MockitoBean LabResultQueryService service;
        @MockitoBean LabErrorRecorder recorder;

        @Test
        void introspectionIsEnabled() {
            assertThat(properties.getSchema().getIntrospection().isEnabled()).isTrue();
            assertThat(properties.getGraphiql().isEnabled()).isTrue();
        }
    }

    private static PropertySource<?> yaml(String resource) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
        assertThat(sources).hasSize(1);
        return sources.getFirst();
    }
}
