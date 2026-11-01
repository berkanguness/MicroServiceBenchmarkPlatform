package com.benchmark.lab_result_service.persistence.repository;

import com.benchmark.lab_result_service.core.api.PatientView;
import com.benchmark.lab_result_service.persistence.entity.Patient;
import com.benchmark.lab_result_service.persistence.row.ItemRow;
import com.benchmark.lab_result_service.persistence.row.ReportRow;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The only read repository (contract §5.2). Every method is a flat JPQL constructor projection: no entity is
 * hydrated, no association is navigated beyond its FK id. The statement sequence per service method is §5.3.
 */
public interface LabReadRepository extends Repository<Patient, Long> {

    /**
     * Fully qualified name of the {@code PatientView} record used in the JPQL constructor expression. The single
     * place to change if {@code PatientView} moves package (e.g. to {@code core.api.dto}); a test pins it to
     * {@code PatientView.class.getName()}.
     */
    String PATIENT_VIEW = "com.benchmark.lab_result_service.core.api.PatientView";

    /** Fully qualified name of {@link ReportRow} for JPQL constructor expressions. */
    String REPORT_ROW = "com.benchmark.lab_result_service.persistence.row.ReportRow";

    /** Fully qualified name of {@link ItemRow} for JPQL constructor expressions. */
    String ITEM_ROW = "com.benchmark.lab_result_service.persistence.row.ItemRow";

    @Query("select new " + PATIENT_VIEW + "(p.id, p.tcNo, p.fullName, p.birthDate, p.bloodType)"
            + " from Patient p where p.id = :id")
    Optional<PatientView> findPatient(@Param("id") long id);

    @Query("select new " + REPORT_ROW + "(r.id, r.doctorName, r.sampleType, r.createdAt)"
            + " from LabReport r where r.patient.id = :patientId"
            + " order by r.createdAt desc, r.id desc")
    List<ReportRow> findReports(@Param("patientId") long patientId);

    @Query("select new " + REPORT_ROW + "(r.id, r.doctorName, r.sampleType, r.createdAt)"
            + " from LabReport r where r.patient.id = :patientId"
            + " order by r.createdAt desc, r.id desc"
            + " limit 1")
    Optional<ReportRow> findLatestReport(@Param("patientId") long patientId);

    /** Callers must never pass an empty collection (§5.3: stop before an empty {@code IN}). */
    @Query("select new " + ITEM_ROW + "(i.report.id, i.id, i.testCode, i.testName, i.resultValue, i.unit, i.critical)"
            + " from LabTestItem i where i.report.id in :reportIds"
            + " order by i.report.id, i.id")
    List<ItemRow> findItems(@Param("reportIds") Collection<Long> reportIds);

    @Query("select new " + ITEM_ROW + "(i.report.id, i.id, i.testCode, i.testName, i.resultValue, i.unit, i.critical)"
            + " from LabTestItem i where i.report.id = :reportId"
            + " order by i.id")
    List<ItemRow> findItemsOfReport(@Param("reportId") long reportId);
}
