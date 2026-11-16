package com.benchmark.lab_result_service.persistence.repository;

import com.benchmark.lab_result_service.config.TransactionConfig;
import com.benchmark.lab_result_service.core.api.PatientView;
import com.benchmark.lab_result_service.persistence.row.ItemRow;
import com.benchmark.lab_result_service.persistence.row.ReportRow;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test-only stand-in for the P3 service (contract §6.8): calls {@link LabReadRepository} in exactly the §5.3 order,
 * inside the read-only {@link TransactionConfig} template, without assembling views. It exists so P1 can pin the
 * statement counts before {@code DefaultLabResultQueryService} exists; P3's real service is not pulled forward.
 *
 * <p>{@code id <= 0} throws before any statement (the service's 400 path, 0 statements). An absent patient yields
 * {@link Optional#empty()} after exactly one statement (the 404 path).
 */
public class ReadSequence {

    public record SummaryRows(PatientView patient, @Nullable ReportRow latestReport, List<ItemRow> items) {}

    public record HistoryRows(PatientView patient, List<ReportRow> reports, List<ItemRow> items) {}

    private final LabReadRepository repository;
    private final TransactionTemplate tx;

    public ReadSequence(LabReadRepository repository,
            @Qualifier(TransactionConfig.READ_TRANSACTION_TEMPLATE) TransactionTemplate tx) {
        this.repository = repository;
        this.tx = tx;
    }

    public Optional<PatientView> patient(long id) {
        requirePositive(id);
        return tx.execute(status -> repository.findPatient(id));
    }

    public Optional<SummaryRows> summary(long id) {
        requirePositive(id);
        return tx.execute(status -> {
            Optional<PatientView> patient = repository.findPatient(id);
            if (patient.isEmpty()) {
                return Optional.empty();
            }
            Optional<ReportRow> latest = repository.findLatestReport(id);
            if (latest.isEmpty()) {
                return Optional.of(new SummaryRows(patient.get(), null, List.of()));
            }
            List<ItemRow> items = repository.findItemsOfReport(latest.get().id());
            return Optional.of(new SummaryRows(patient.get(), latest.get(), items));
        });
    }

    public Optional<HistoryRows> history(long id) {
        requirePositive(id);
        return tx.execute(status -> {
            Optional<PatientView> patient = repository.findPatient(id);
            if (patient.isEmpty()) {
                return Optional.empty();
            }
            List<ReportRow> reports = repository.findReports(id);
            if (reports.isEmpty()) {                       // never run an empty IN
                return Optional.of(new HistoryRows(patient.get(), List.of(), List.of()));
            }
            List<ItemRow> items = repository.findItems(reports.stream().map(ReportRow::id).toList());
            return Optional.of(new HistoryRows(patient.get(), reports, items));
        });
    }

    private static void requirePositive(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("patientId must be a positive 64-bit integer");
        }
    }
}
