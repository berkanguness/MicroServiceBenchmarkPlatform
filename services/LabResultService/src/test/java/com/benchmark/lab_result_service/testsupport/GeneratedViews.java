package com.benchmark.lab_result_service.testsupport;

import com.benchmark.lab_result_service.core.api.dto.LabReportView;
import com.benchmark.lab_result_service.core.api.dto.LabTestItemView;
import com.benchmark.lab_result_service.core.api.dto.PatientLabHistoryView;
import com.benchmark.lab_result_service.core.api.dto.PatientSummaryView;
import com.benchmark.lab_result_service.core.api.dto.PatientView;
import com.benchmark.lab_result_service.seed.SyntheticDatasetGenerator;
import com.benchmark.lab_result_service.seed.SyntheticDatasetGenerator.Dataset;
import com.benchmark.lab_result_service.seed.SyntheticDatasetGenerator.PatientRecord;
import com.benchmark.lab_result_service.seed.SyntheticDatasetGenerator.ReportRecord;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The core.api views of the real (P2-generated, i.e. seeded) dataset, built in memory from
 * {@link SyntheticDatasetGenerator} with the service's ordering rules (§5.3: reports {@code createdAt DESC, id DESC},
 * items {@code id ASC}). Used as the mocked service's answers in web-slice tests, whose output is compared with golden
 * files captured from the real application on a seeded database. If this builder and the real read path ever diverge,
 * the golden comparison fails.
 */
public final class GeneratedViews {

    private static final Dataset DATASET = SyntheticDatasetGenerator.generate();
    private static final Comparator<ReportRecord> NEWEST_FIRST =
            Comparator.comparing(ReportRecord::createdAt).thenComparingLong(ReportRecord::id).reversed();

    private GeneratedViews() {}

    public static PatientView patient(long id) {
        PatientRecord p = DATASET.patients().stream().filter(r -> r.id() == id).findFirst().orElseThrow();
        return new PatientView(p.id(), p.tcNo(), p.fullName(), p.birthDate(), p.bloodType());
    }

    public static PatientSummaryView summary(long patientId) {
        List<LabReportView> reports = reports(patientId);
        return new PatientSummaryView(patient(patientId), reports.isEmpty() ? null : reports.getFirst());
    }

    public static PatientLabHistoryView history(long patientId) {
        return new PatientLabHistoryView(patient(patientId), reports(patientId));
    }

    private static List<LabReportView> reports(long patientId) {
        List<ReportRecord> reports = DATASET.reports().stream().filter(r -> r.patientId() == patientId)
                .sorted(NEWEST_FIRST).toList();
        var ids = reports.stream().map(ReportRecord::id).collect(Collectors.toSet());
        Map<Long, List<LabTestItemView>> items = DATASET.items().stream()
                .filter(i -> ids.contains(i.reportId()))
                .sorted(Comparator.comparingLong(SyntheticDatasetGenerator.ItemRecord::id))
                .collect(Collectors.groupingBy(SyntheticDatasetGenerator.ItemRecord::reportId, Collectors.mapping(
                        i -> new LabTestItemView(i.id(), i.testCode(), i.testName(), i.resultValue(), i.unit(), i.critical()),
                        Collectors.toList())));
        return reports.stream().map(r -> new LabReportView(r.id(), r.doctorName(), r.sampleType(), r.createdAt(),
                items.getOrDefault(r.id(), List.of()))).toList();
    }
}
