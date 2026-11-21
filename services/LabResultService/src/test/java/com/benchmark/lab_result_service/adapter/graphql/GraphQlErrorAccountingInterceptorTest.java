package com.benchmark.lab_result_service.adapter.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.benchmark.lab_result_service.core.api.enums.ErrorCode;
import com.benchmark.lab_result_service.core.api.enums.Protocol;
import com.benchmark.lab_result_service.core.api.exception.InvalidRequestException;
import com.benchmark.lab_result_service.core.api.exception.PatientNotFoundException;
import com.benchmark.lab_result_service.core.api.exception.ServiceUnavailableException;
import com.benchmark.lab_result_service.core.api.service.LabErrorRecorder;
import com.benchmark.lab_result_service.testsupport.GeneratedViews;
import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import java.net.URI;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.execution.DefaultExecutionGraphQlService;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import reactor.core.publisher.Mono;

/**
 * Unit test of the GraphQL error accounting (contract §7.4) on Spring GraphQL's real {@link WebGraphQlHandler} with the
 * real schema, {@link GraphQlExceptionResolver} and {@link GraphQlErrorAccountingInterceptor}; data fetchers are
 * wired directly, so faults can be raised where the Spring context cannot place them: on a non-request thread, in two
 * root fields at once, as an error not built by our resolver, or as a failure of the execution itself.
 */
class GraphQlErrorAccountingInterceptorTest {

    private final LabErrorRecorder recorder = mock(LabErrorRecorder.class);
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> new Thread(r, "not-a-request-thread"));
    private final Map<String, String> resolverThreads = new ConcurrentHashMap<>();
    /** Opened by {@link #execute} once graphql-java has attached its continuations; see {@link #async}. */
    private final CountDownLatch gate = new CountDownLatch(1);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    /**
     * The exception surfaces on a pool thread (an async data fetcher, see {@link #async}), so the resolver runs there,
     * not on the thread that sent the request. The holder is reached through the GraphQL context, not a thread-local:
     * recorded once. Repeated because a scheduling-dependent helper once let this pass or fail by chance.
     */
    @org.junit.jupiter.api.RepeatedTest(25)
    void anExceptionResolvedOnAnotherThreadIsRecordedOnce() {
        ServiceUnavailableException failure = new ServiceUnavailableException(new SQLTransientConnectionException("x"));
        WebGraphQlHandler handler = handler(Map.of("patientSummary", async(() -> { throw failure; })));

        WebGraphQlResponse response = execute(handler, "{ patientSummary(patientId: \"3\") { patient { id } } }");

        assertThat(resolverThreads).containsEntry("patientSummary", "not-a-request-thread");
        assertThat(response.getErrors()).singleElement().satisfies(e -> {
            assertThat(e.getExtensions()).containsEntry("code", "UNAVAILABLE");
            assertThat(e.getMessage()).isEqualTo("Service temporarily unavailable");
        });
        verify(recorder).record(Protocol.GRAPHQL, ErrorCode.UNAVAILABLE, failure);
        verifyNoMoreInteractions(recorder);
    }

    /** Two root fields failing concurrently on two pool threads: one record, the more severe code and its cause. */
    @ParameterizedTest(name = "{0} + {1} → {2}")
    @CsvSource({
            "PATIENT_NOT_FOUND, INVALID_ARGUMENT,  INVALID_ARGUMENT",
            "INVALID_ARGUMENT,  PATIENT_NOT_FOUND, INVALID_ARGUMENT",
            "INVALID_ARGUMENT,  UNAVAILABLE,       UNAVAILABLE",
            "UNAVAILABLE,       INTERNAL,          INTERNAL",
            "INTERNAL,          PATIENT_NOT_FOUND, INTERNAL",
            "PATIENT_NOT_FOUND, PATIENT_NOT_FOUND, PATIENT_NOT_FOUND"})
    void severityOrderDecidesTheSingleCode(ErrorCode first, ErrorCode second, ErrorCode expected) {
        RuntimeException a = exception(first);
        RuntimeException b = exception(second);
        WebGraphQlHandler handler = handler(Map.of(
                "patientSummary", async(() -> { throw a; }),
                "patientLabHistory", async(() -> { throw b; })));

        WebGraphQlResponse response = execute(handler,
                "{ patientSummary(patientId: \"3\") { patient { id } } patientLabHistory(patientId: \"1\") { patient { id } } }");

        assertThat(response.getErrors()).hasSize(2);
        ArgumentCaptor<Throwable> cause = ArgumentCaptor.forClass(Throwable.class);
        verify(recorder).record(eq(Protocol.GRAPHQL), eq(expected), cause.capture());
        verifyNoMoreInteractions(recorder);
        assertThat(cause.getValue()).isIn(a, b);
        assertThat(code(cause.getValue())).isEqualTo(expected);
    }

    @Test
    void successRecordsNothing() {
        WebGraphQlHandler handler = handler(Map.of("patientSummary", async(() -> GeneratedViews.summary(3))));

        WebGraphQlResponse response = execute(handler, "{ patientSummary(patientId: \"3\") { patient { id } } }");

        assertThat(response.getErrors()).isEmpty();
        assertThat(response.isValid()).isTrue();
        verifyNoInteractions(recorder);
    }

    @Test
    void syntaxAndValidationErrorsAreInvalidArgumentWithoutCause() {
        WebGraphQlHandler handler = handler(Map.of());

        execute(handler, "{ patientSummary(");
        execute(handler, "{ patientSummary(patientId: \"3\") { nope } }");
        execute(handler, "mutation { anything }");

        verify(recorder, org.mockito.Mockito.times(3)).record(Protocol.GRAPHQL, ErrorCode.INVALID_ARGUMENT, null);
        verifyNoMoreInteractions(recorder);
    }

    /**
     * Smoke finding (P5): with introspection off (default/bench), an introspection query is answered with
     * graphql-java's {@code IntrospectionDisabled} error. It is a rejected client request: INVALID_ARGUMENT without
     * cause, not INTERNAL (which logs at ERROR with a stack trace). Boot switches introspection off JVM-wide, so the
     * test restores the previous JVM-wide value.
     */
    @Test
    void disabledIntrospectionIsInvalidArgumentWithoutCause() {
        boolean previous = graphql.introspection.Introspection.enabledJvmWide(false);
        try {
            WebGraphQlResponse response = execute(handler(Map.of()), "{ __schema { queryType { name } } }");

            assertThat(response.getErrors()).singleElement()
                    .satisfies(e -> assertThat(e.getErrorType()).hasToString("IntrospectionDisabled"));
        } finally {
            graphql.introspection.Introspection.enabledJvmWide(previous);
        }
        verify(recorder).record(Protocol.GRAPHQL, ErrorCode.INVALID_ARGUMENT, null);
        verifyNoMoreInteractions(recorder);
    }

    /** An error that did not come from our resolver (none is expected) must not leave a failed request unrecorded. */
    @Test
    void anErrorNotBuiltByTheResolverIsRecordedAsInternal() {
        DataFetcher<Object> partial = env -> DataFetcherResult.newResult()
                .data(null).error(GraphqlErrorBuilder.newError(env).message("foreign").build()).build();
        WebGraphQlHandler handler = handler(Map.of("patientSummary", partial));

        execute(handler, "{ patientSummary(patientId: \"3\") { patient { id } } }");

        ArgumentCaptor<Throwable> cause = ArgumentCaptor.forClass(Throwable.class);
        verify(recorder).record(eq(Protocol.GRAPHQL), eq(ErrorCode.INTERNAL), cause.capture());
        verifyNoMoreInteractions(recorder);
        assertThat(cause.getValue()).isInstanceOf(IllegalStateException.class).hasMessageContaining("patientSummary");
    }

    /** The execution itself failing (no response at all) is one INTERNAL; the failure still propagates. */
    @Test
    void aFailedExecutionIsRecordedOnceAsInternal() {
        IllegalStateException boom = new IllegalStateException("boom");
        WebGraphQlInterceptor failing = (request, chain) -> Mono.error(boom);
        WebGraphQlHandler handler = WebGraphQlHandler.builder(service(Map.of()))
                .interceptors(List.of(new GraphQlErrorAccountingInterceptor(recorder), failing)).build();

        assertThatThrownBy(() -> execute(handler, "{ patient(id: \"3\") { id } }")).isSameAs(boom);
        verify(recorder).record(Protocol.GRAPHQL, ErrorCode.INTERNAL, boom);
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void severityTable() {
        assertThat(GraphQlErrorAccountingInterceptor.moreSevere(ErrorCode.INTERNAL, ErrorCode.UNAVAILABLE)).isTrue();
        assertThat(GraphQlErrorAccountingInterceptor.moreSevere(ErrorCode.UNAVAILABLE, ErrorCode.INVALID_ARGUMENT)).isTrue();
        assertThat(GraphQlErrorAccountingInterceptor.moreSevere(ErrorCode.INVALID_ARGUMENT, ErrorCode.PATIENT_NOT_FOUND)).isTrue();
        assertThat(GraphQlErrorAccountingInterceptor.moreSevere(ErrorCode.PATIENT_NOT_FOUND, ErrorCode.INVALID_ARGUMENT)).isFalse();
        assertThat(GraphQlErrorAccountingInterceptor.moreSevere(ErrorCode.PATIENT_NOT_FOUND, null)).isTrue();
    }

    // -------------------------------------------------------------------------------------------- wiring

    private WebGraphQlHandler handler(Map<String, DataFetcher<?>> queryFetchers) {
        return WebGraphQlHandler.builder(service(queryFetchers))
                .interceptor(new GraphQlErrorAccountingInterceptor(recorder)).build();
    }

    private DefaultExecutionGraphQlService service(Map<String, DataFetcher<?>> queryFetchers) {
        GraphQlExceptionResolver resolver = new GraphQlExceptionResolver() {
            @Override
            protected graphql.GraphQLError resolveToSingleError(Throwable ex, graphql.schema.DataFetchingEnvironment env) {
                resolverThreads.put(env.getField().getName(), Thread.currentThread().getName());
                return super.resolveToSingleError(ex, env);
            }
        };
        GraphQlSource source = GraphQlSource.schemaResourceBuilder()
                .schemaResources(new ClassPathResource("graphql/schema.graphqls"))
                .configureRuntimeWiring(wiring -> wiring.type("Query", type -> {
                    queryFetchers.forEach(type::dataFetcher);
                    return type;
                }))
                .exceptionResolvers(List.of(resolver))
                .build();
        return new DefaultExecutionGraphQlService(source);
    }

    /**
     * A data fetcher whose result (or exception) is produced on a pool thread. The pool thread completes the future
     * only after {@link #execute} has opened {@link #gate}, which it does once the subscription to the handler has
     * returned. Subscribing runs graphql-java's {@code executeAsync} synchronously on the calling thread (no scheduler
     * hop in Spring GraphQL's handler chain), so by then every continuation is attached, including the one on the
     * future Spring derives from ours ({@code ContextDataFetcherDecorator}: {@code Mono.toFuture()}). Completing the
     * future therefore runs that continuation, and with it the exception resolver, on the pool thread. Waiting for
     * dependents of our own future is not enough: Spring's subscriber is attached to it before graphql-java's
     * continuation exists (flaky at 9908621).
     */
    private <T> DataFetcher<CompletableFuture<T>> async(Supplier<T> body) {
        return env -> {
            CompletableFuture<T> future = new CompletableFuture<>();
            pool.execute(() -> {
                try {
                    if (!gate.await(5, TimeUnit.SECONDS)) {
                        future.completeExceptionally(new IllegalStateException("test gate never opened"));
                        return;
                    }
                    future.complete(body.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.completeExceptionally(e);
                } catch (RuntimeException e) {
                    future.completeExceptionally(e);
                }
            });
            return future;
        };
    }

    /** Subscribes (which executes the document up to its async fields), opens {@link #gate}, then waits. */
    private WebGraphQlResponse execute(WebGraphQlHandler handler, String document) {
        WebGraphQlRequest request = new WebGraphQlRequest(URI.create("http://localhost/graphql"), new HttpHeaders(),
                new LinkedMultiValueMap<>(), Map.of(), Map.of("query", document), "1", null);
        CompletableFuture<WebGraphQlResponse> response = handler.handleRequest(request).toFuture();
        gate.countDown();
        try {
            return response.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        } catch (InterruptedException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RuntimeException exception(ErrorCode code) {
        return switch (code) {
            case INVALID_ARGUMENT -> new InvalidRequestException("patientId", "must be a positive 64-bit integer");
            case PATIENT_NOT_FOUND -> new PatientNotFoundException(1001);
            case UNAVAILABLE -> new ServiceUnavailableException(new SQLTransientConnectionException("down"));
            default -> new IllegalStateException("internal");
        };
    }

    private static ErrorCode code(Throwable t) {
        return t instanceof com.benchmark.lab_result_service.core.api.exception.LabResultException lab ? lab.code()
                : ErrorCode.INTERNAL;
    }
}
