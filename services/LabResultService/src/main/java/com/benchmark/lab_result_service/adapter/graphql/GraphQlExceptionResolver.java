package com.benchmark.lab_result_service.adapter.graphql;

import com.benchmark.lab_result_service.core.api.enums.ErrorCode;
import com.benchmark.lab_result_service.core.api.exception.LabResultException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

/**
 * Builds the GraphQL error of a failed root field (contract §7.2 error table); the field value is {@code null}.
 *
 * <table>
 *   <caption>Mapping</caption>
 *   <tr><th>Exception</th><th>classification</th><th>extensions.code</th><th>message</th></tr>
 *   <tr><td>InvalidRequestException</td><td>BAD_REQUEST</td><td>INVALID_ARGUMENT</td><td>exception message</td></tr>
 *   <tr><td>PatientNotFoundException</td><td>NOT_FOUND</td><td>PATIENT_NOT_FOUND</td><td>exception message</td></tr>
 *   <tr><td>ServiceUnavailableException</td><td>INTERNAL_ERROR</td><td>UNAVAILABLE</td><td>{@value #UNAVAILABLE_MESSAGE}</td></tr>
 *   <tr><td>anything else</td><td>INTERNAL_ERROR</td><td>INTERNAL</td><td>{@value #INTERNAL_MESSAGE}</td></tr>
 * </table>
 *
 * <p>5xx-type messages are fixed text, never an exception message, SQL or stack trace; the 4xx-type messages are our
 * own ({@code patientId must be a positive 64-bit integer}, {@code Patient 1001 not found}). This class neither logs
 * nor counts: it adds {@code (code, exception)} to the request's {@link LabGraphQlErrors}, and
 * {@link GraphQlErrorAccountingInterceptor} records the request once (§7.4).
 */
@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    static final String UNAVAILABLE_MESSAGE = "Service temporarily unavailable";
    static final String INTERNAL_MESSAGE = "Internal error";
    static final String CODE_EXTENSION = "code";

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        ErrorCode code = ex instanceof LabResultException lab ? lab.code() : ErrorCode.INTERNAL;
        ErrorType type;
        String message;
        switch (code) {
            case INVALID_ARGUMENT -> { type = ErrorType.BAD_REQUEST; message = String.valueOf(ex.getMessage()); }
            case PATIENT_NOT_FOUND -> { type = ErrorType.NOT_FOUND; message = String.valueOf(ex.getMessage()); }
            case UNAVAILABLE -> { type = ErrorType.INTERNAL_ERROR; message = UNAVAILABLE_MESSAGE; }
            // INTERNAL; CLIENT_ABORTED is never produced by the service and is never sent to a client
            default -> { code = ErrorCode.INTERNAL; type = ErrorType.INTERNAL_ERROR; message = INTERNAL_MESSAGE; }
        }
        LabGraphQlErrors holder = holder(env);
        if (holder != null) {
            holder.add(code, ex);
        }
        return GraphqlErrorBuilder.newError(env)
                .errorType(type)
                .message(message)
                .extensions(Map.of(CODE_EXTENSION, code.name()))
                .build();
    }

    /** Absent only when the interceptor is not part of the chain (e.g. a {@code @GraphQlTest} slice). */
    private static @Nullable LabGraphQlErrors holder(DataFetchingEnvironment env) {
        return env.getGraphQlContext().get(LabGraphQlErrors.CONTEXT_KEY);
    }
}
