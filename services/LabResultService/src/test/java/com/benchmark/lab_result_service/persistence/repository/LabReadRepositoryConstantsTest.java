package com.benchmark.lab_result_service.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.benchmark.lab_result_service.core.api.PatientView;
import com.benchmark.lab_result_service.persistence.row.ItemRow;
import com.benchmark.lab_result_service.persistence.row.ReportRow;
import org.junit.jupiter.api.Test;

/** The JPQL constructor FQNs are string constants; this pins them to the real classes (a move breaks here first). */
class LabReadRepositoryConstantsTest {

    @Test
    void constructorExpressionNamesMatchTheClasses() {
        assertThat(LabReadRepository.PATIENT_VIEW).isEqualTo(PatientView.class.getName());
        assertThat(LabReadRepository.REPORT_ROW).isEqualTo(ReportRow.class.getName());
        assertThat(LabReadRepository.ITEM_ROW).isEqualTo(ItemRow.class.getName());
    }
}
