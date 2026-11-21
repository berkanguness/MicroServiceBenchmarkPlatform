package com.benchmark.lab_result_service.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.benchmark.lab_result_service.core.api.enums.ErrorCode;
import com.benchmark.lab_result_service.core.api.enums.Protocol;
import com.benchmark.lab_result_service.core.api.service.LabErrorRecorder;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * Client aborts (§3.4/§7.4) are not tested end to end (P8 manual check); this pins the classification: a disconnect
 * anywhere in the cause chain is CLIENT_ABORTED, recorded once, and nothing is written.
 */
class RestExceptionHandlerTest {

    private final LabErrorRecorder recorder = mock(LabErrorRecorder.class);
    private final RestExceptionHandler handler = new RestExceptionHandler(recorder);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/patients/1/lab-history");

    @Test
    void tomcatClientAbortWrappedByTheMessageConverterIsClientAborted() throws Exception {
        var e = new HttpMessageNotWritableException("Could not write JSON", new IOException(new ClientAbortException("Broken pipe")));

        assertThat(handler.handleOther(e, request)).as("nothing is written to a dead connection").isNull();
        verify(recorder).record(Protocol.REST, ErrorCode.CLIENT_ABORTED, e);
    }

    @Test
    void asyncRequestNotUsableIsClientAborted() throws Exception {
        var e = new AsyncRequestNotUsableException("Response not usable after response errors.");

        assertThat(handler.handleOther(e, request)).isNull();
        verify(recorder).record(Protocol.REST, ErrorCode.CLIENT_ABORTED, e);
    }

    @Test
    void springWebExceptionsAreRethrownUnrecorded() {
        var e = new HttpMediaTypeNotAcceptableException("Could not find acceptable representation");

        assertThatThrownBy(() -> handler.handleOther(e, request)).isSameAs(e);
        verifyNoInteractions(recorder);
    }

    /** Referee finding (codex #1): a "Broken pipe" message without a typed disconnect is not a client abort. */
    @Test
    void brokenPipeMessageWithoutATypedDisconnectIsInternal() throws Exception {
        var e = new UncheckedIOException("server-side producer failed", new IOException("Broken pipe"));

        assertThat(RestExceptionHandler.isClientAbort(e)).isFalse();
        assertThat(RestExceptionHandler.isClientAbort(new IOException("Connection reset by peer"))).isFalse();
        assertThat(RestExceptionHandler.isClientAbort(new java.io.EOFException())).isFalse();
        var response = handler.handleOther(e, request);
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        verify(recorder).record(Protocol.REST, ErrorCode.INTERNAL, e);
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void anOrdinaryIOExceptionIsInternal() throws Exception {
        var e = new IllegalStateException("x", new IOException("disk"));

        assertThat(handler.handleOther(e, request).getStatusCode().value()).isEqualTo(500);
        verify(recorder).record(same(Protocol.REST), same(ErrorCode.INTERNAL), any());
    }
}
