package com.benchmark.lab_result_service.config;

import com.benchmark.lab_result_service.core.api.enums.ErrorCode;
import com.benchmark.lab_result_service.core.api.enums.Protocol;
import com.benchmark.lab_result_service.core.api.exception.ServiceUnavailableException;
import com.benchmark.lab_result_service.core.api.service.LabErrorRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single error-logging and counting policy of all adapters (contract §3.4, R5/F15). Each adapter calls
 * {@link #record} exactly once per failed request.
 *
 * <table>
 *   <caption>Policy</caption>
 *   <tr><th>Code</th><th>Log</th><th>Counter {@code lab.errors{protocol,code}}</th></tr>
 *   <tr><td>INVALID_ARGUMENT, PATIENT_NOT_FOUND</td><td>DEBUG, one line</td><td>+1</td></tr>
 *   <tr><td>UNAVAILABLE</td><td>WARN, one line, no stack trace (cause class + message)</td><td>+1</td></tr>
 *   <tr><td>CLIENT_ABORTED</td><td>WARN, one line, no stack trace</td><td>+1</td></tr>
 *   <tr><td>INTERNAL</td><td>ERROR with stack trace</td><td>+1</td></tr>
 * </table>
 *
 * <p>All protocol × code counters are registered up front, so every series exists (at 0) from startup and the
 * error path does no meter lookup.
 */
@Component
public class MicrometerLabErrorRecorder implements LabErrorRecorder {

    static final String COUNTER = "lab.errors";

    private static final Logger log = LoggerFactory.getLogger(MicrometerLabErrorRecorder.class);

    private final Map<Protocol, Map<ErrorCode, Counter>> counters = new EnumMap<>(Protocol.class);

    public MicrometerLabErrorRecorder(MeterRegistry registry) {
        for (Protocol protocol : Protocol.values()) {
            Map<ErrorCode, Counter> byCode = new EnumMap<>(ErrorCode.class);
            for (ErrorCode code : ErrorCode.values()) {
                byCode.put(code, Counter.builder(COUNTER)
                        .description("Failed requests per protocol and error code (one per request)")
                        .tag("protocol", protocol.name())
                        .tag("code", code.name())
                        .register(registry));
            }
            counters.put(protocol, byCode);
        }
    }

    @Override
    public void record(Protocol protocol, ErrorCode code, @Nullable Throwable cause) {
        counters.get(protocol).get(code).increment();
        switch (code) {
            case INVALID_ARGUMENT, PATIENT_NOT_FOUND -> {
                if (log.isDebugEnabled()) {
                    log.debug("lab error protocol={} code={} {}", protocol, code, oneLine(cause));
                }
            }
            case UNAVAILABLE, CLIENT_ABORTED -> log.warn("lab error protocol={} code={} {}", protocol, code, oneLine(rootOf(cause)));
            case INTERNAL -> log.error("lab error protocol={} code={}", protocol, code, cause);
        }
    }

    /** Class and message on one line, never a stack trace. */
    static String oneLine(@Nullable Throwable t) {
        if (t == null) {
            return "cause=none";
        }
        String message = t.getMessage();
        String text = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        return "cause=" + t.getClass().getName() + (text.isEmpty() ? "" : ": " + text);
    }

    /**
     * For UNAVAILABLE the {@code ServiceUnavailableException} itself only says "Service temporarily unavailable";
     * the useful part is its direct cause (e.g. {@code CannotCreateTransactionException}). Client aborts are
     * reported as the wrapper the framework threw. Neither prints a stack.
     */
    private static @Nullable Throwable rootOf(@Nullable Throwable cause) {
        if (cause != null && cause.getCause() != null
                && cause instanceof ServiceUnavailableException) {
            return cause.getCause();
        }
        return cause;
    }
}
