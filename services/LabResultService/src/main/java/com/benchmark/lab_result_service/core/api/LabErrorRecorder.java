package com.benchmark.lab_result_service.core.api;

import org.jspecify.annotations.Nullable;

/** Single error-logging and counting policy for all adapters (R5/F15). Implemented in config by MicrometerLabErrorRecorder. */
public interface LabErrorRecorder {
    void record(Protocol protocol, ErrorCode code, @Nullable Throwable cause);
}
