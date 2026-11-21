package com.benchmark.lab_result_service.adapter.graphql;

import com.benchmark.lab_result_service.core.api.enums.ErrorCode;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-request collector of the field errors of one GraphQL request (contract §7.4). {@link GraphQlErrorAccountingInterceptor}
 * creates one per request and puts it into the request's {@code GraphQLContext} under {@link #CONTEXT_KEY};
 * {@link GraphQlExceptionResolver} adds one {@link Entry} per resolved exception. It lives in the GraphQL context,
 * not in a thread-local, so it is reached the same way whichever thread completes a data fetcher.
 */
final class LabGraphQlErrors {

    static final String CONTEXT_KEY = LabGraphQlErrors.class.getName();

    record Entry(ErrorCode code, Throwable cause) {}

    private final Queue<Entry> entries = new ConcurrentLinkedQueue<>();

    void add(ErrorCode code, Throwable cause) {
        entries.add(new Entry(code, cause));
    }

    /** In the order the resolver saw them. */
    List<Entry> entries() {
        return List.copyOf(entries);
    }
}
