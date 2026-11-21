package com.benchmark.lab_result_service.adapter.rest;

import com.benchmark.lab_result_service.core.api.enums.ErrorCode;
import com.benchmark.lab_result_service.core.api.enums.Protocol;
import com.benchmark.lab_result_service.core.api.exception.LabResultException;
import com.benchmark.lab_result_service.core.api.service.LabErrorRecorder;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.apache.catalina.connector.ClientAbortException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * RFC 9457 errors of the REST adapter (contract §7.1, §7.4), {@code application/problem+json} with the extra member
 * {@code code}. Every failed request of {@link PatientRestController} is recorded exactly once through the shared
 * {@link LabErrorRecorder} (§3.4); this class does not log on its own.
 *
 * <ul>
 *   <li>Scoped to {@link PatientRestController}: unmatched routes and wrong methods have no handler of ours and keep
 *       Spring's default 404/405 handling.</li>
 *   <li>Spring's own web exceptions ({@link ErrorResponse}, e.g. an unsatisfiable {@code Accept}) are rethrown
 *       unrecorded, so Spring's default resolver keeps handling them; they are not service outcomes (§7.4 lists none).</li>
 *   <li>5xx {@code detail} is fixed text, never an exception message, SQL or stack trace.</li>
 *   <li>A client that disconnected is recorded as {@code CLIENT_ABORTED} and nothing is written to the dead
 *       connection. Detection is by type only ({@link #isClientAbort}); an exception message such as "Broken pipe" is
 *       never evidence of a disconnect.</li>
 * </ul>
 */
@RestControllerAdvice(assignableTypes = PatientRestController.class)
public class RestExceptionHandler {

    static final String UNAVAILABLE_DETAIL = "Service temporarily unavailable";
    static final String INTERNAL_DETAIL = "Internal error";

    private static final URI ABOUT_BLANK = URI.create("about:blank");

    private final LabErrorRecorder recorder;

    public RestExceptionHandler(LabErrorRecorder recorder) {
        this.recorder = recorder;
    }

    @ExceptionHandler(LabResultException.class)
    public ResponseEntity<ProblemDetail> handleLabResult(LabResultException e, HttpServletRequest request) {
        ErrorCode code = e.code();
        recorder.record(Protocol.REST, code, e);
        return switch (code) {
            // 4xx: the message is ours and safe ("patientId must be a positive 64-bit integer", "Patient 1001 not found")
            case INVALID_ARGUMENT -> problem(HttpStatus.BAD_REQUEST, e.getMessage(), code, request);
            case PATIENT_NOT_FOUND -> problem(HttpStatus.NOT_FOUND, e.getMessage(), code, request);
            case UNAVAILABLE -> problem(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_DETAIL, code, request);
            // unreachable for the sealed hierarchy today; fixed text if it ever happens
            case INTERNAL, CLIENT_ABORTED -> problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_DETAIL, ErrorCode.INTERNAL, request);
        };
    }

    @ExceptionHandler(Exception.class)
    public @Nullable ResponseEntity<ProblemDetail> handleOther(Exception e, HttpServletRequest request) throws Exception {
        if (isClientAbort(e)) {
            recorder.record(Protocol.REST, ErrorCode.CLIENT_ABORTED, e);
            return null;                                   // handled; the client is gone, write nothing
        }
        if (e instanceof ErrorResponse) {
            throw e;                                       // Spring default handling (DefaultHandlerExceptionResolver)
        }
        recorder.record(Protocol.REST, ErrorCode.INTERNAL, e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_DETAIL, ErrorCode.INTERNAL, request);
    }

    /**
     * A client disconnect, decided by type only: one of these anywhere in the cause chain.
     * <ul>
     *   <li>{@link ClientAbortException}: Tomcat's connector buffers ({@code OutputBuffer}, {@code InputBuffer})
     *       throw it for an I/O failure on the client connection, so it never stems from the database or other I/O.</li>
     *   <li>{@link AsyncRequestNotUsableException}: Spring's response wrapper throws it once a write to the response
     *       failed, i.e. the response can no longer be used.</li>
     * </ul>
     * Message phrases ("Broken pipe", "Connection reset by peer", Spring's {@code DisconnectedClientHelper}) are
     * deliberately not used: an I/O failure of something else, while the client is still connected, must stay
     * {@code INTERNAL} (500), never an empty 200.
     */
    static boolean isClientAbort(Throwable e) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof ClientAbortException || t instanceof AsyncRequestNotUsableException) {
                return true;
            }
        }
        return false;
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, @Nullable String detail, ErrorCode code,
            HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(ABOUT_BLANK);                         // Spring 7 leaves type null (omitted); the contract shows it
        try {
            body.setInstance(URI.create(request.getRequestURI()));   // the request path, already percent-encoded
        } catch (IllegalArgumentException ignored) {
            // not a valid URI reference: omit "instance" rather than fail the error response
        }
        body.setProperty("code", code.name());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
