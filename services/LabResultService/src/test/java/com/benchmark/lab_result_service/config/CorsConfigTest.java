package com.benchmark.lab_result_service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.benchmark.lab_result_service.adapter.rest.PatientRestController;
import com.benchmark.lab_result_service.core.api.service.LabErrorRecorder;
import com.benchmark.lab_result_service.core.api.service.LabResultQueryService;
import com.benchmark.lab_result_service.testsupport.GeneratedViews;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * CORS (contract §8.1 R3, §10 P4): one global MVC mapping for {@code /api/**} and {@code /graphql}, only outside
 * {@code bench}/{@code bench-nomon}, only with a non-empty origin list.
 *
 * <p>GraphQL (P5) is not wired yet, so a test-only {@code POST /graphql} {@link RouterFunction} stands in for Spring
 * GraphQL's endpoint: it is served by the same {@code RouterFunctionMapping} that GraphQL uses, so the preflight below
 * goes through the mapping the real endpoint will use.
 */
class CorsConfigTest {

    static final String ORIGIN = "http://example.test";

    /** Shared slice setup: the REST controller with a mocked service, plus the GraphQL stand-in. */
    @TestConfiguration(proxyBeanMethods = false)
    static class GraphQlStandIn {
        @Bean
        RouterFunction<ServerResponse> graphQlStandIn() {
            return RouterFunctions.route().POST("/graphql", request -> ServerResponse.ok().body("{}")).build();
        }
    }

    /**
     * Puts {@code APP_CORS_ALLOWED_ORIGINS} into a property source named and typed like the real OS environment
     * ({@code systemEnvironment}), so it outranks {@code application*.yaml} exactly as a container env var would.
     */
    static class EnvironmentVariableInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            ConfigurableEnvironment env = context.getEnvironment();
            Map<String, Object> vars = new HashMap<>(System.getenv());
            vars.put("APP_CORS_ALLOWED_ORIGINS", ORIGIN);
            env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, vars));
        }
    }

    abstract static class Slice {
        @Autowired MockMvc mvc;
        @Autowired ApplicationContext context;
        @MockitoBean LabResultQueryService service;
        @MockitoBean LabErrorRecorder recorder;

        @BeforeEach
        void stubService() {
            Mockito.when(service.getPatientSummary(3)).thenReturn(GeneratedViews.summary(3));
        }

        ResultActions preflight(String path, String method) throws Exception {
            return mvc.perform(options(path)
                    .header(HttpHeaders.ORIGIN, ORIGIN)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method));
        }

        ResultActions simpleGet() throws Exception {
            return mvc.perform(get("/api/v1/patients/3/summary").header(HttpHeaders.ORIGIN, ORIGIN));
        }

        void assertNoCors() throws Exception {
            simpleGet().andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
            preflight("/api/v1/patients/3/summary", "GET")
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
            preflight("/graphql", "POST")
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }

        void assertCorsAllowed() throws Exception {
            preflight("/api/v1/patients/3/summary", "GET").andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,OPTIONS"));
            preflight("/graphql", "POST").andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,OPTIONS"));
            simpleGet().andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
            // methods outside the list are refused, other origins get nothing
            preflight("/api/v1/patients/3/summary", "DELETE").andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/patients/3/summary").header(HttpHeaders.ORIGIN, "http://other.test"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Nested
    @WebMvcTest(PatientRestController.class)
    @Import(GraphQlStandIn.class)
    class EmptyOriginList extends Slice {
        @Test
        void noMappingAndNoCorsHeaders() throws Exception {
            assertThat(context.getBean(CorsConfig.class).allowedOrigins()).isEmpty();
            assertNoCors();
        }
    }

    @Nested
    @WebMvcTest(PatientRestController.class)
    @Import(GraphQlStandIn.class)
    @TestPropertySource(properties = "app.cors.allowed-origins= " + ORIGIN + " , ")
    class WithAnOrigin extends Slice {
        @Test
        void preflightIsAllowedOnApiAndGraphQl() throws Exception {
            assertThat(context.getBean(CorsConfig.class).allowedOrigins()).containsExactly(ORIGIN);
            assertCorsAllowed();
        }
    }

    /** Positive control for the next two cases: the env-level variable does reach the property outside bench. */
    @Nested
    @WebMvcTest(PatientRestController.class)
    @Import(GraphQlStandIn.class)
    @ContextConfiguration(initializers = EnvironmentVariableInitializer.class)
    class EnvironmentVariableOutsideBench extends Slice {
        @Test
        void environmentVariableEnablesCors() throws Exception {
            assertThat(context.getEnvironment().getProperty("app.cors.allowed-origins")).isEqualTo(ORIGIN);
            assertCorsAllowed();
        }
    }

    @Nested
    @WebMvcTest(PatientRestController.class)
    @Import(GraphQlStandIn.class)
    @ActiveProfiles("bench")
    @ContextConfiguration(initializers = EnvironmentVariableInitializer.class)
    class BenchWithEnvironmentVariable extends Slice {
        @Test
        void noCorsConfigBeanAndNoCorsHeaders() throws Exception {
            assertThat(context.getEnvironment().getProperty("app.cors.allowed-origins"))
                    .as("the env var outranks the YAML default, so only @Profile can keep CORS off").isEqualTo(ORIGIN);
            assertThat(context.getBeansOfType(CorsConfig.class)).isEmpty();
            assertNoCors();
        }
    }

    @Nested
    @WebMvcTest(PatientRestController.class)
    @Import(GraphQlStandIn.class)
    @ActiveProfiles("bench-nomon")
    @ContextConfiguration(initializers = EnvironmentVariableInitializer.class)
    class BenchNomonWithEnvironmentVariable extends Slice {
        @Test
        void noCorsConfigBeanAndNoCorsHeaders() throws Exception {
            assertThat(context.getBeansOfType(CorsConfig.class)).isEmpty();
            assertNoCors();
        }
    }
}
