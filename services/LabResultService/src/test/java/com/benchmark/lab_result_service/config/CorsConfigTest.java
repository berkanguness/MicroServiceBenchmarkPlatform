package com.benchmark.lab_result_service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.benchmark.lab_result_service.adapter.graphql.LabGraphQlController;
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
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.graphql.autoconfigure.GraphQlAutoConfiguration;
import org.springframework.boot.graphql.autoconfigure.servlet.GraphQlWebMvcAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.graphql.server.webmvc.GraphQlHttpHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * CORS (contract §8.1 R3, §10 P4): one global MVC mapping for {@code /api/**} and {@code /graphql}, only outside
 * {@code bench}/{@code bench-nomon}, only with a non-empty origin list.
 *
 * <p>From P5 on {@code /graphql} is Spring GraphQL's real endpoint (P4 used a test-only {@code RouterFunction}
 * stand-in): the slice adds {@link LabGraphQlController} and the GraphQL auto-configuration, so the preflight goes
 * through the {@code RouterFunctionMapping} that {@code GraphQlWebMvcAutoConfiguration} registers, and a real
 * {@code POST /graphql} carries the CORS header when an origin is allowed.
 */
class CorsConfigTest {

    static final String ORIGIN = "http://example.test";

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
            Mockito.when(service.getPatient(3)).thenReturn(GeneratedViews.patient(3));
        }

        /**
         * The real GraphQL endpoint answers a query, sent once. {@code GraphQlHttpHandler} answers with
         * {@code ServerResponse.async(future)}, and Spring MVC writes a future that is already complete (our resolvers
         * are synchronous) synchronously, without a servlet async dispatch. Only if async processing did start is the
         * same result dispatched.
         */
        ResultActions graphQlPost(String origin) throws Exception {
            var builder = post("/graphql").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .content("{\"query\":\"{ patient(id: \\\"3\\\") { id } }\"}");
            if (origin != null) {
                builder.header(HttpHeaders.ORIGIN, origin);
            }
            ResultActions sent = mvc.perform(builder);
            MvcResult result = sent.andReturn();
            return result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)) : sent;
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
            graphQlPost(ORIGIN).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.patient.id").value("3"))
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
            graphQlPost(ORIGIN).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.patient.id").value("3"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
            // methods outside the list are refused, other origins get nothing
            preflight("/api/v1/patients/3/summary", "DELETE").andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/patients/3/summary").header(HttpHeaders.ORIGIN, "http://other.test"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Nested
    @WebMvcTest({PatientRestController.class, LabGraphQlController.class})
    @ImportAutoConfiguration({GraphQlAutoConfiguration.class, GraphQlWebMvcAutoConfiguration.class})
    class EmptyOriginList extends Slice {
        @Test
        void graphQlIsTheRealSpringGraphQlEndpoint() {
            assertThat(context.getBeansOfType(GraphQlHttpHandler.class)).hasSize(1);
            assertThat(context.getBeansOfType(LabGraphQlController.class)).hasSize(1);
        }

        @Test
        void noMappingAndNoCorsHeaders() throws Exception {
            assertThat(context.getBean(CorsConfig.class).allowedOrigins()).isEmpty();
            assertNoCors();
        }
    }

    @Nested
    @WebMvcTest({PatientRestController.class, LabGraphQlController.class})
    @ImportAutoConfiguration({GraphQlAutoConfiguration.class, GraphQlWebMvcAutoConfiguration.class})
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
    @WebMvcTest({PatientRestController.class, LabGraphQlController.class})
    @ImportAutoConfiguration({GraphQlAutoConfiguration.class, GraphQlWebMvcAutoConfiguration.class})
    @ContextConfiguration(initializers = EnvironmentVariableInitializer.class)
    class EnvironmentVariableOutsideBench extends Slice {
        @Test
        void environmentVariableEnablesCors() throws Exception {
            assertThat(context.getEnvironment().getProperty("app.cors.allowed-origins")).isEqualTo(ORIGIN);
            assertCorsAllowed();
        }
    }

    @Nested
    @WebMvcTest({PatientRestController.class, LabGraphQlController.class})
    @ImportAutoConfiguration({GraphQlAutoConfiguration.class, GraphQlWebMvcAutoConfiguration.class})
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
    @WebMvcTest({PatientRestController.class, LabGraphQlController.class})
    @ImportAutoConfiguration({GraphQlAutoConfiguration.class, GraphQlWebMvcAutoConfiguration.class})
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
