package com.benchmark.lab_result_service.core.service;

import com.benchmark.lab_result_service.core.api.dto.LabReportView;
import com.benchmark.lab_result_service.core.api.dto.LabTestItemView;
import com.benchmark.lab_result_service.persistence.row.ItemRow;
import com.benchmark.lab_result_service.persistence.row.ReportRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flat rows to views (contract §5.3 "Assembly"), one linear pass. Items are grouped by {@code reportId} in SQL order
 * (id ASC); reports keep their SQL order (createdAt DESC, id DESC); a report without items gets {@code List.of()}.
 * {@code ItemRow.reportId} is for grouping only and never reaches a view (D11).
 */
final class LabAssembler {

    private LabAssembler() {}

    static List<LabReportView> reports(List<ReportRow> reports, List<ItemRow> items) {
        Map<Long, List<LabTestItemView>> byReport = new LinkedHashMap<>();
        for (ItemRow item : items) {
            byReport.computeIfAbsent(item.reportId(), id -> new ArrayList<>()).add(item(item));
        }
        List<LabReportView> views = new ArrayList<>(reports.size());
        for (ReportRow report : reports) {
            views.add(report(report, byReport.getOrDefault(report.id(), List.of())));
        }
        return views;
    }

    static LabReportView report(ReportRow report, List<LabTestItemView> items) {
        return new LabReportView(report.id(), report.doctorName(), report.sampleType(), report.createdAt(), items);
    }

    static List<LabTestItemView> items(List<ItemRow> items) {
        List<LabTestItemView> views = new ArrayList<>(items.size());
        for (ItemRow item : items) {
            views.add(item(item));
        }
        return views;
    }

    private static LabTestItemView item(ItemRow row) {
        return new LabTestItemView(row.id(), row.testCode(), row.testName(), row.resultValue(), row.unit(),
                row.critical());
    }
}
