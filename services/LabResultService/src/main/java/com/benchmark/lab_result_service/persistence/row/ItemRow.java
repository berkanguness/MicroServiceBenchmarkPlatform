package com.benchmark.lab_result_service.persistence.row;

/**
 * Flat projection of one {@code lab_test_item} row (contract §5.1). {@code reportId} is for grouping only and
 * never reaches a view (D11).
 */
public record ItemRow(long reportId, long id, String testCode, String testName, double resultValue, String unit,
        boolean critical) {}
