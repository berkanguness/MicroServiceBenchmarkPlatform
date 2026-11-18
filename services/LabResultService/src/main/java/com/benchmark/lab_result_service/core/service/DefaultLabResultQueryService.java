package com.benchmark.lab_result_service.core.service;

import com.benchmark.lab_result_service.config.TransactionConfig;
import com.benchmark.lab_result_service.core.api.dto.LabReportView;
import com.benchmark.lab_result_service.core.api.dto.PatientLabHistoryView;
import com.benchmark.lab_result_service.core.api.dto.PatientSummaryView;
import com.benchmark.lab_result_service.core.api.dto.PatientView;
import com.benchmark.lab_result_service.core.api.enums.ErrorCode;
import com.benchmark.lab_result_service.core.api.exception.InvalidRequestException;
import com.benchmark.lab_result_service.core.api.exception.LabResultException;
import com.benchmark.lab_result_service.core.api.exception.PatientNotFoundException;
import com.benchmark.lab_result_service.core.api.exception.ServiceUnavailableException;
import com.benchmark.lab_result_service.core.api.service.LabResultQueryService;
import com.benchmark.lab_result_service.persistence.repository.LabReadRepository;
import com.benchmark.lab_result_service.persistence.row.ItemRow;
import com.benchmark.lab_result_service.persistence.row.ReportRow;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The single implementation of {@link LabResultQueryService}; every protocol adapter calls it (benchmark fairness:
 * no protocol-specific query, cache or shortcut).
 *
 * <ul>
 *   <li>Statements per method exactly as contract §5.3; the rows are assembled after the transaction has ended, so the
 *       connection is held only for the queries.</li>
 *   <li>Transactions through the read-only {@link TransactionTemplate} of {@link TransactionConfig} (§5.5), not
 *       {@code @Transactional}, so failures at transaction begin are translated here, once, for all protocols.</li>
 *   <li>{@code lab.service.calls} (§5.6) is recorded explicitly, exactly once per call, with tags {@code method} and
 *       {@code outcome}; the timed region covers validation, the transaction (including connection acquisition), the
 *       queries and assembly, not serialization.</li>
 * </ul>
 */
@Service
public class DefaultLabResultQueryService implements LabResultQueryService {

    static final String TIMER = "lab.service.calls";
    static final String SUCCESS = "SUCCESS";

    /** SQLStates of class 57 with which PostgreSQL ends a session: admin_shutdown, crash_shutdown, cannot_connect_now. */
    static final Set<String> SERVER_ENDED_SESSION = Set.of("57P01", "57P02", "57P03");

    private static final String ID_FIELD = "patientId";
    private static final String ID_REASON = "must be a positive 64-bit integer";

    /** Timer {@code method} tag values; the names are the interface method names. */
    enum Method { getPatient, getPatientSummary, getPatientLabHistory }

    /** Timer {@code outcome} tag values (§5.6): SUCCESS plus every code a service call can end with. */
    static final List<String> OUTCOMES = List.of(SUCCESS, ErrorCode.INVALID_ARGUMENT.name(),
            ErrorCode.PATIENT_NOT_FOUND.name(), ErrorCode.UNAVAILABLE.name(), ErrorCode.INTERNAL.name());

    private final LabReadRepository repository;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Map<Method, Map<String, Timer>> timers = new EnumMap<>(Method.class);

    public DefaultLabResultQueryService(LabReadRepository repository,
            @Qualifier(TransactionConfig.READ_TRANSACTION_TEMPLATE) TransactionTemplate tx, MeterRegistry registry) {
        this.repository = repository;
        this.tx = tx;
        this.clock = registry.config().clock();
        for (Method method : Method.values()) {
            Map<String, Timer> byOutcome = new HashMap<>();
            for (String outcome : OUTCOMES) {
                byOutcome.put(outcome, Timer.builder(TIMER)
                        .description("LabResultQueryService calls: validation, transaction, queries and assembly")
                        .tag("method", method.name())
                        .tag("outcome", outcome)
                        .register(registry));
            }
            timers.put(method, Map.copyOf(byOutcome));
        }
    }

    @Override
    public PatientView getPatient(long patientId) {
        return timed(Method.getPatient, () -> {
            requirePositive(patientId);
            Optional<PatientView> patient = inReadTransaction(status -> repository.findPatient(patientId));
            return patient.orElseThrow(() -> new PatientNotFoundException(patientId));
        });
    }

    @Override
    public PatientSummaryView getPatientSummary(long patientId) {
        return timed(Method.getPatientSummary, () -> {
            requirePositive(patientId);
            SummaryRows rows = inReadTransaction(status -> {
                Optional<PatientView> patient = repository.findPatient(patientId);
                if (patient.isEmpty()) {
                    return null;
                }
                Optional<ReportRow> latest = repository.findLatestReport(patientId);
                if (latest.isEmpty()) {
                    return new SummaryRows(patient.get(), null, List.of());
                }
                return new SummaryRows(patient.get(), latest.get(), repository.findItemsOfReport(latest.get().id()));
            });
            if (rows == null) {
                throw new PatientNotFoundException(patientId);
            }
            ReportRow latest = rows.latestReport();
            LabReportView latestView = latest == null ? null : LabAssembler.report(latest, LabAssembler.items(rows.items()));
            return new PatientSummaryView(rows.patient(), latestView);
        });
    }

    @Override
    public PatientLabHistoryView getPatientLabHistory(long patientId) {
        return timed(Method.getPatientLabHistory, () -> {
            requirePositive(patientId);
            HistoryRows rows = inReadTransaction(status -> {
                Optional<PatientView> patient = repository.findPatient(patientId);
                if (patient.isEmpty()) {
                    return null;
                }
                List<ReportRow> reports = repository.findReports(patientId);
                if (reports.isEmpty()) {                       // never run an empty IN
                    return new HistoryRows(patient.get(), List.of(), List.of());
                }
                return new HistoryRows(patient.get(), reports,
                        repository.findItems(reports.stream().map(ReportRow::id).toList()));
            });
            if (rows == null) {
                throw new PatientNotFoundException(patientId);
            }
            return new PatientLabHistoryView(rows.patient(), LabAssembler.reports(rows.reports(), rows.items()));
        });
    }

    // ------------------------------------------------------------------------------------------------ internals

    private record SummaryRows(PatientView patient, @Nullable ReportRow latestReport, List<ItemRow> items) {}

    private record HistoryRows(PatientView patient, List<ReportRow> reports, List<ItemRow> items) {}

    private static void requirePositive(long patientId) {
        if (patientId <= 0) {
            throw new InvalidRequestException(ID_FIELD, ID_REASON);
        }
    }

    /** Records exactly one {@code lab.service.calls} sample per call, also when {@code body} throws. */
    private <T> T timed(Method method, Supplier<T> body) {
        long start = clock.monotonicTime();
        String outcome = ErrorCode.INTERNAL.name();
        try {
            T result = body.get();
            outcome = SUCCESS;
            return result;
        } catch (LabResultException e) {
            outcome = e.code().name();
            throw e;
        } finally {
            timers.get(method).get(outcome).record(clock.monotonicTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Runs {@code work} in the read transaction and translates database unavailability into
     * {@link ServiceUnavailableException} (§5.5). Every other exception propagates unchanged and becomes INTERNAL.
     *
     * <p>Chaos F2: when the connection is lost in the middle of the transaction, the callback fails with a
     * {@link DataAccessResourceFailureException}, and then the rollback on the dead connection fails too.
     * {@link TransactionTemplate} rethrows the <em>rollback</em> exception ({@code JpaSystemException: Unable to
     * rollback against JDBC Connection}) and only logs the callback's. The holder keeps the callback's exception so the
     * call is classified by what actually happened. No retry.
     */
    private <T> @Nullable T inReadTransaction(TransactionCallback<@Nullable T> work) {
        CallbackFailure callbackFailure = new CallbackFailure();
        try {
            return tx.execute(status -> {
                try {
                    return work.doInTransaction(status);
                } catch (RuntimeException e) {
                    callbackFailure.exception = e;
                    throw e;
                }
            });
        } catch (RuntimeException e) {
            throw translate(e, callbackFailure.exception);
        }
    }

    /** Per-call holder for the exception the transaction callback threw (F2). */
    private static final class CallbackFailure {
        @Nullable RuntimeException exception;
    }

    /**
     * Classifies what {@link TransactionTemplate#execute} threw.
     *
     * @param thrown          the exception that reached the service
     * @param callbackFailure the exception the callback threw, if any. When it differs from {@code thrown}, the
     *                        template replaced it with a rollback failure, and the callback's exception decides.
     * @return a {@link ServiceUnavailableException} wrapping the unavailability cause, or {@code thrown} unchanged
     */
    static RuntimeException translate(RuntimeException thrown, @Nullable RuntimeException callbackFailure) {
        if (isUnavailable(thrown)) {
            return new ServiceUnavailableException(thrown);
        }
        // The rollback failed after the callback had failed: classify by the callback's exception. An ordinary SQL
        // error masked by a failed rollback is therefore still INTERNAL (thrown is returned unchanged).
        if (callbackFailure != null && callbackFailure != thrown && isUnavailable(callbackFailure)) {
            return new ServiceUnavailableException(callbackFailure);
        }
        // The same information through Spring's own channel, when the rollback failure is a TransactionSystemException.
        if (thrown instanceof TransactionSystemException tse && tse.getApplicationException() instanceof Throwable app
                && isUnavailable(app)) {
            return new ServiceUnavailableException(app);
        }
        return thrown;
    }

    /**
     * The §5.5 list: the database cannot be reached, a connection cannot be had, a transaction cannot begin, or the
     * server ended the session.
     */
    static boolean isUnavailable(Throwable e) {
        return e instanceof CannotCreateTransactionException
                || e instanceof DataAccessResourceFailureException          // includes CannotGetJdbcConnectionException
                || e instanceof TransientDataAccessResourceException
                || e instanceof DataAccessException && hasConnectionLossSqlState(e);
    }

    /**
     * P4 (chaos case c): when PostgreSQL itself ends the session, e.g. {@code pg_terminate_backend} or the fast shutdown
     * of {@code docker stop}, the in-flight statement fails with {@code FATAL 57P01 terminating connection due to
     * administrator command}. Hibernate maps 57P01 to {@code GenericJDBCException} and Spring to
     * {@code JpaSystemException}, which is none of the types above. The SQLState decides instead: class {@code 08}
     * (connection exception) and {@code 57P01}/{@code 57P02}/{@code 57P03} (admin shutdown, crash shutdown, cannot
     * connect now). Any other SQLState stays INTERNAL.
     */
    static boolean hasConnectionLossSqlState(Throwable e) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() instanceof String state
                    && (state.startsWith("08") || SERVER_ENDED_SESSION.contains(state))) {
                return true;
            }
        }
        return false;
    }
}
