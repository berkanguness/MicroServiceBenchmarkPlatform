package com.benchmark.lab_result_service.persistence.row;

import java.time.Instant;

/** Flat projection of one {@code lab_report} row (contract §5.1). */
public record ReportRow(long id, String doctorName, String sampleType, Instant createdAt) {}
