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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Mapping of {@code lab_report} (V1). No {@code items} collection (contract §4.1): no read path navigates it.
 */
@Entity
@Table(name = "lab_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LabReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Column(name = "doctor_name", nullable = false, length = 100)
    private String doctorName;

    @Column(name = "sample_type", nullable = false, length = 20)
    private String sampleType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
