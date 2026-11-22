package com.benchmark.lab_result_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.benchmark.lab_result_service.core.api.enums.ErrorCode;
import com.benchmark.lab_result_service.core.api.enums.Protocol;
import com.benchmark.lab_result_service.core.api.exception.PatientNotFoundException;
import com.benchmark.lab_result_service.core.api.exception.ServiceUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.CannotCreateTransactionException;

/** Contract §3.4: one counter per (protocol, code), +1 per record; one-line WARN for UNAVAILABLE/CLIENT_ABORTED, ERROR with stack for INTERNAL. */
@ExtendWith(OutputCaptureExtension.class)
class MicrometerLabErrorRecorderTest {

    private SimpleMeterRegistry registry;
    private MicrometerLabErrorRecorder recorder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new MicrometerLabErrorRecorder(registry);
    }

    @Test
    void everyProtocolAndCodeIsRegisteredAtZero() {
        assertThat(registry.find(MicrometerLabErrorRecorder.COUNTER).counters()).hasSize(3 * 5)
                .allSatisfy(c -> assertThat(c.count()).isZero());
    }

    @Test
    void recordIncrementsExactlyItsOwnCounter() {
        recorder.record(Protocol.REST, ErrorCode.UNAVAILABLE, new ServiceUnavailableException(new RuntimeException("x")));

        assertThat(count(Protocol.REST, ErrorCode.UNAVAILABLE)).isEqualTo(1);
        double total = registry.find(MicrometerLabErrorRecorder.COUNTER).counters().stream().mapToDouble(Counter::count).sum();
        assertThat(total).isEqualTo(1);
    }

    @Test
    void unavailableIsOneWarnLineWithTheCauseAndNoStackTrace(CapturedOutput output) {
        var cause = new ServiceUnavailableException(new CannotCreateTransactionException("Could not open JPA EntityManager",
                new SQLTransientConnectionException("lab-hikari - Connection is not available, request timed out after 2000ms")));
        recorder.record(Protocol.REST, ErrorCode.UNAVAILABLE, cause);

        String line = singleLogLine(output, "lab error protocol=REST code=UNAVAILABLE");
        assertThat(line).contains("WARN").contains(CannotCreateTransactionException.class.getName())
                .contains("Could not open JPA EntityManager");
        assertThat(output.getAll()).doesNotContain("\tat ");
    }

    @Test
    void clientAbortIsOneWarnLineWithoutStackTrace(CapturedOutput output) {
        recorder.record(Protocol.GRPC, ErrorCode.CLIENT_ABORTED, new java.io.IOException("Broken pipe"));

        assertThat(singleLogLine(output, "lab error protocol=GRPC code=CLIENT_ABORTED")).contains("WARN").contains("Broken pipe");
        assertThat(output.getAll()).doesNotContain("\tat ");
        assertThat(count(Protocol.GRPC, ErrorCode.CLIENT_ABORTED)).isEqualTo(1);
    }

    @Test
    void internalIsAnErrorWithTheStackTrace(CapturedOutput output) {
        recorder.record(Protocol.GRAPHQL, ErrorCode.INTERNAL, new IllegalStateException("boom"));

        assertThat(output.getAll()).contains("ERROR").contains("lab error protocol=GRAPHQL code=INTERNAL")
                .contains("java.lang.IllegalStateException: boom").contains("\tat ");
    }

    @Test
    void expectedErrorsAreNotLoggedAboveDebug(CapturedOutput output) {
        recorder.record(Protocol.REST, ErrorCode.PATIENT_NOT_FOUND, new PatientNotFoundException(1001));
        recorder.record(Protocol.REST, ErrorCode.INVALID_ARGUMENT, null);

        assertThat(output.getAll()).doesNotContain("lab error");    // root level INFO: DEBUG lines are not printed
        assertThat(count(Protocol.REST, ErrorCode.PATIENT_NOT_FOUND)).isEqualTo(1);
        assertThat(count(Protocol.REST, ErrorCode.INVALID_ARGUMENT)).isEqualTo(1);
    }

    @Test
    void oneLineFlattensMultiLineMessages() {
        assertThat(MicrometerLabErrorRecorder.oneLine(new RuntimeException("a\n  b\tc"))).isEqualTo("cause=java.lang.RuntimeException: a b c");
        assertThat(MicrometerLabErrorRecorder.oneLine(null)).isEqualTo("cause=none");
    }

    private double count(Protocol protocol, ErrorCode code) {
        return registry.get(MicrometerLabErrorRecorder.COUNTER).tag("protocol", protocol.name()).tag("code", code.name())
                .counter().count();
    }

    private static String singleLogLine(CapturedOutput output, String marker) {
        var lines = output.getAll().lines().filter(l -> l.contains(marker)).toList();
        assertThat(lines).as("log lines containing '%s'", marker).hasSize(1);
        return lines.getFirst();
    }
}
