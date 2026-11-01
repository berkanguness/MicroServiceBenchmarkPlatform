package com.benchmark.lab_result_service.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Mapping of {@code lab_test_item} (V1). {@code critical} maps {@code is_critical}. */
@Entity
@Table(name = "lab_test_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LabTestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private LabReport report;

    @Column(name = "test_code", nullable = false, length = 20)
    private String testCode;

    @Column(name = "test_name", nullable = false, length = 100)
    private String testName;

    @Column(name = "result_value", nullable = false)
    private double resultValue;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    @Column(name = "is_critical", nullable = false)
    private boolean critical;
}
