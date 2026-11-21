package com.benchmark.lab_result_service.adapter.graphql;

import com.benchmark.lab_result_service.core.api.enums.ErrorCode;
import com.benchmark.lab_result_service.core.api.enums.Protocol;
import com.benchmark.lab_result_service.core.api.service.LabErrorRecorder;
import graphql.ErrorClassification;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The only GraphQL caller of {@link LabErrorRecorder#record} (contract §7.4, P0-F8): a failed request is recorded
 * exactly once, a successful one never. The counting unit is the HTTP request, not the field.
 *
 * <p>Per request it puts a fresh {@link LabGraphQlErrors} into the {@code GraphQLContext}, which
 * {@link GraphQlExceptionResolver} fills from whichever thread resolves a field. When the response is complete it
 * derives the request's single code:
 * <ul>
 *   <li>field errors from the holder (with their exception as cause);</li>
 *   <li>request errors whose classification is graphql-java {@code InvalidSyntax} or {@code ValidationError}
 *       (they never reach the resolver) → {@code INVALID_ARGUMENT}, no cause; likewise {@code IntrospectionDisabled},
 *       graphql-java's answer to an introspection query while introspection is off (default and bench profiles):
 *       a rejected client request, not a server fault;</li>
 *   <li>any other error in the response that carries no {@code extensions.code} (none is expected) →
 *       {@code INTERNAL}, so an error from an unexpected producer is still recorded;</li>
 *   <li>the execution itself failing (no response) → {@code INTERNAL}.</li>
 * </ul>
 * If there are several, the most severe wins: {@code INTERNAL > UNAVAILABLE > INVALID_ARGUMENT > PATIENT_NOT_FOUND}.
 * Logging is the recorder's (§3.4).
 *
 * <p>The guarantee holds for the current, closed set of error producers: graphql-java's request errors and
 * {@link GraphQlExceptionResolver}. An error that carries a {@code code} extension is trusted as built by that
 * resolver; neither its value nor a matching holder entry is checked. A new producer (for example a data fetcher
 * returning {@code DataFetcherResult} errors with its own {@code code}) must be accounted for explicitly first.
 */
@Component
public class GraphQlErrorAccountingInterceptor implements WebGraphQlInterceptor {

    /** Classification name of graphql-java's {@code IntrospectionDisabledError} (graphql-java 25). */
    static final String INTROSPECTION_DISABLED = "IntrospectionDisabled";

    private final LabErrorRecorder recorder;

    public GraphQlErrorAccountingInterceptor(LabErrorRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        LabGraphQlErrors errors = new LabGraphQlErrors();
        request.configureExecutionInput((input, builder) -> {
            input.getGraphQLContext().put(LabGraphQlErrors.CONTEXT_KEY, errors);
            return input;
        });
        return chain.next(request)
                .doOnNext(response -> account(errors, response))
                .doOnError(failure -> recorder.record(Protocol.GRAPHQL, ErrorCode.INTERNAL, failure));
    }

    private void account(LabGraphQlErrors errors, WebGraphQlResponse response) {
        ErrorCode code = null;
        Throwable cause = null;
        for (LabGraphQlErrors.Entry entry : errors.entries()) {
            if (moreSevere(entry.code(), code)) {
                code = entry.code();
                cause = entry.cause();
            }
        }
        for (ResponseError error : response.getErrors()) {
            ErrorClassification type = error.getErrorType();
            if (isRequestError(type)) {
                if (moreSevere(ErrorCode.INVALID_ARGUMENT, code)) {
                    code = ErrorCode.INVALID_ARGUMENT;
                    cause = null;
                }
            } else if (!error.getExtensions().containsKey(GraphQlExceptionResolver.CODE_EXTENSION)
                    && moreSevere(ErrorCode.INTERNAL, code)) {
                code = ErrorCode.INTERNAL;
                cause = new IllegalStateException("GraphQL error not built by GraphQlExceptionResolver: "
                        + type + " at " + error.getPath());
            }
        }
        if (code != null) {
            recorder.record(Protocol.GRAPHQL, code, cause);
        }
    }

    /** Errors of the request itself, raised by graphql-java before or instead of any data fetcher. */
    static boolean isRequestError(ErrorClassification type) {
        return type == graphql.ErrorType.InvalidSyntax || type == graphql.ErrorType.ValidationError
                // graphql.introspection.IntrospectionDisabledError: ErrorClassification.errorClassification(name)
                || INTROSPECTION_DISABLED.equals(type.toString());
    }

    static boolean moreSevere(ErrorCode candidate, @Nullable ErrorCode current) {
        return current == null || severity(candidate) > severity(current);
    }

    /** INTERNAL > UNAVAILABLE > INVALID_ARGUMENT > PATIENT_NOT_FOUND (contract §7.4). */
    static int severity(ErrorCode code) {
        return switch (code) {
            case PATIENT_NOT_FOUND -> 1;
            case INVALID_ARGUMENT -> 2;
            case UNAVAILABLE -> 3;
            case INTERNAL -> 4;
            case CLIENT_ABORTED -> 0;                      // never produced by the resolver
        };
    }
}
