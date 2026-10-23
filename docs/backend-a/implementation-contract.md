# Backend A — Implementation Contract (P0)

| Field | Value |
|---|---|
| Work / seat | `backend-a-p0` / `p0-author-claude` |
| Status | Revision 1: referee findings P0-F1…P0-F9 applied (`docs/briefs/reports/p0-referee-codex.md`) |
| Date | 2026-10-23 |
| Branch | `berkay/lab-result-service` (base `main@1890b13`) |
| Module | `services/LabResultService` (Spring Boot 4.1.1, Java 21) |
| Audience | Implementers of P1a–P8 (English). The team-facing summary is `docs/backend-a/arayuz-tablolari.md` (Turkish). |

**This file is the single source of truth for Backend A's implementation.** Phase briefs reference it, not the design reports. Authority order when sources disagree: user decisions (U1–U3, D4) > roadmap v2 (R1–R11) > design verdict (D1–D13) > base design (`design-r1-claude.md`). Appendix A lists every base-design value this contract replaces.

Evidence tags: `REPRODUCED` = checked in this P0 session against the resolved artifacts (scratch Maven repo, Boot 4.1.1 tree); `VERIFIED-EARLIER` = reproduced by an earlier round and cited there; `DIRECT SOURCE` = file/line; `INFERRED n%`; `DECISION` = chosen here, with the reason given. Anything that still needs someone else's answer is an owned open question in §12. There are no unowned TBDs.

---

## 1. Scope boundary

### 1.1 Paths Backend A owns (may create and edit)

| Path | Content |
|---|---|
| `services/LabResultService/pom.xml` | Dependencies and plugins of this module only (§9) |
| `services/LabResultService/src/main/java/com/benchmark/lab_result_service/**` | Every package in §2 **except** `adapter.grpc` |
| `services/LabResultService/src/main/resources/**` | `application*.yaml`, `db/migration/V*.sql`, `graphql/schema.graphqls`, seed catalogues |
| `services/LabResultService/src/test/**` | All tests (§10) |
| `docs/backend-a/**` | This contract, interface tables, Postman collection (`docs/backend-a/api/`), payload table, EXPLAIN artefacts, licence inventory |
| `docs/rfc/**`, `docs/adr/**` | Proposals and accepted decisions, through PRs only |

### 1.2 Left to Backend B

- Package `com.benchmark.lab_result_service.adapter.grpc`. Backend A creates no file in it, and the ArchUnit rules (§2.2) already cover it.
- `services/CommonProto/src/main/proto/lab.proto` and all generated code. The proto shape is a proposal (RFC 0001 §1, PR #3).
- The `GrpcServerExecutorProvider` bean (D10, R5). If B asks for it in PR #3, A writes it in `config` (§12, Q3).
- The `GrpcExceptionHandler` implementation of the error map in §7.4.

### 1.3 Never touched by Backend A

Root `pom.xml`; `services/PatientService/**`; `services/CommonProto/**`; `docker-compose*.yml`; Dockerfiles; k6 scripts (`tests/**`); `frontend/**`; Prometheus/Grafana configuration; `.github/**`; `README.md` (the Boot 3→4 wording fix goes to the team as a note, see the interface tables). Changes we want in those files go to their owners as PR proposals.

Benchmark-safety guardrails for every phase:
- No write endpoints. The seeder never deletes, truncates or updates rows (§6.6).
- No migration or seeding against any shared database. Tests use disposable Testcontainers PostgreSQL only.
- The local Homebrew `postgresql@16` service is not ours; never connect to or modify it.

---

## 2. Package layout and ArchUnit rules

### 2.1 Layout

Base package `com.benchmark.lab_result_service` (it exists: `LabResultServiceApplication.java`).

```
com.benchmark.lab_result_service
├── LabResultServiceApplication
├── core.api               records, LabResultQueryService, exceptions, ErrorCode, Protocol,
│                          PatientIds, LabErrorRecorder          (depends on: java.*, org.jspecify only)
├── core.service           DefaultLabResultQueryService, LabAssembler
├── persistence.entity     Patient, LabReport, LabTestItem       (schema validation + JPQL typing only)
├── persistence.row        ReportRow, ItemRow                    (flat projection rows)
├── persistence.repository LabReadRepository
├── seed                   SyntheticDatasetGenerator, TestCatalogue, NameLists, TcNo,
│                          CanonicalDigest, DatasetSeeder, DatasetVerifier, DatasetManifest
├── adapter.rest           PatientRestController, RestExceptionHandler            (A)
├── adapter.graphql        LabGraphQlController, GraphQlExceptionResolver         (A)
├── adapter.grpc           — empty in A's commits —                               (B)
└── config                 CorsConfig, LabInfoContributor, MicrometerLabErrorRecorder,
                           TransactionConfig
```

`persistence` may depend on `core.api` (JPQL builds `PatientView` directly). `core.service` depends on `core.api`, `persistence` and Micrometer. `config` may depend on anything.

### 2.2 ArchUnit rules (exactly three test methods, `ArchitectureTest`)

```java
@AnalyzeClasses(packages = "com.benchmark.lab_result_service", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // Rule 1: adapters see only core.api (plus their own framework and, for gRPC, com.benchmark.proto)
    @ArchTest
    static final ArchRule adaptersUseOnlyCoreApi = noClasses()
            .that().resideInAPackage("..adapter..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..core.service..", "..persistence..", "..seed..")
            .allowEmptyShould(true);                       // P1a: no adapter classes exist yet

    // Rule 2: core is protocol-free, and core.api is pure (JDK + jspecify)
    @ArchTest
    static final ArchRule coreIsProtocolFree = CompositeArchRule
            .of(noClasses().that().resideInAPackage("..core..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..adapter..", "org.springframework.web..", "org.springframework.graphql..",
                            "io.grpc..", "com.benchmark.proto..", "tools.jackson..", "com.fasterxml.jackson.."))
            .and(classes().that().resideInAPackage("..core.api..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "org.jspecify..", "..core.api.."));

    // Rule 3: no adapter depends on another adapter (covers B's adapter.grpc too)
    @ArchTest
    static final ArchRule adaptersAreIndependent = slices()
            .matching("com.benchmark.lab_result_service.adapter.(*)..")
            .should().notDependOnEachOther()
            .allowEmptyShould(true);                       // P1a: no adapter slices exist yet
}
```

Rule 2 stays a single `@ArchTest` field (`CompositeArchRule`). `CompositeArchRule.of/and` and `SlicesShould.notDependOnEachOther` exist in archunit 1.5.1 (`REPRODUCED`, javap). `java..` includes `java.time` and `java.util`.

Empty adapter packages (P0-F1). ArchUnit fails a rule whose `that()` matches no class ("failed to check any classes"), and in P1a no adapter exists. Rules 1 and 3 therefore carry a per-rule `.allowEmptyShould(true)`. The global `archRule.failOnEmptyShould` switch is **not** changed, so rule 2 (whose classes exist from P1a on) still fails on an empty import. `REPRODUCED` with archunit 1.5.1 in a scratch probe: without adapters both rules pass; with an `adapter.rest` class that references `persistence` and `adapter.graphql`, both rules fail with the expected violations. Once adapters exist, the dependency checks are unchanged. From P5 on, `AdapterPresenceTest` (a plain JUnit test, not a fourth ArchUnit rule) asserts that the imported classes include at least one class in `..adapter.rest..` and one in `..adapter.graphql..`, so the empty tolerance cannot hide a mis-scoped import.

P1a test invocation (the legacy `LabResultServiceApplicationTests.contextLoads` needs a datasource and is replaced only in P1, so P1a runs the two API tests by name):
`services/LabResultService/mvnw -f pom.xml -pl services/LabResultService -am test -Dtest='ArchitectureTest,CoreApiTest' -Dsurefire.failIfNoSpecifiedTests=false`
(`failIfNoSpecifiedTests=false` because the `-am` reactor also builds `CommonProto`, which has no matching tests.)

Extensibility (spec §3.3 "Desteklenebilirlik"): a WebSocket/RSocket endpoint is a new `adapter.websocket` package that injects `LabResultQueryService`. It needs **no change to the business service (`core.service`, `LabResultQueryService`) or to `persistence`**, and the three rules apply automatically. The only `core.api` change is adding a telemetry label (e.g. `WEBSOCKET`) to the `Protocol` enum.

---

## 3. `core.api` (published by P1a to Backend B)

All files are in `com.benchmark.lab_result_service.core.api`. `package-info.java` carries `@org.jspecify.annotations.NullMarked` (jspecify is on the classpath, `VERIFIED-EARLIER` design-r1 §3.2). Nothing is nullable unless annotated `@Nullable`.

### 3.1 Records

```java
public record PatientView(long id, String tcNo, String fullName, LocalDate birthDate, String bloodType) {
    public PatientView {
        Objects.requireNonNull(tcNo); Objects.requireNonNull(fullName);
        Objects.requireNonNull(birthDate); Objects.requireNonNull(bloodType);
    }
}

public record LabTestItemView(long id, String testCode, String testName, double resultValue, String unit, boolean isCritical) {
    public LabTestItemView {
        Objects.requireNonNull(testCode); Objects.requireNonNull(testName); Objects.requireNonNull(unit);
    }
}

public record LabReportView(long id, String doctorName, String sampleType, Instant createdAt, List<LabTestItemView> items) {
    public LabReportView {
        Objects.requireNonNull(doctorName); Objects.requireNonNull(sampleType); Objects.requireNonNull(createdAt);
        items = List.copyOf(items);                  // immutable, ordered by item id ASC
    }
}

/** Scenario 1: patient + latest report (by createdAt DESC, id DESC) with its items; latestReport is null when the patient has no reports. */
public record PatientSummaryView(PatientView patient, @Nullable LabReportView latestReport) {
    public PatientSummaryView { Objects.requireNonNull(patient); }
}

/** Scenario 2: patient + every report (createdAt DESC, id DESC), each with its items (id ASC). Reports without items are kept with an empty list. */
public record PatientLabHistoryView(PatientView patient, List<LabReportView> reports) {
    public PatientLabHistoryView { Objects.requireNonNull(patient); reports = List.copyOf(reports); }
}
```

Why records: Jackson derives the JSON name from the record component, so REST emits `"isCritical"`. A Lombok POJO would emit `"critical"` (design-r1 §3.2, `INFERRED 90%`; pinned by the golden JSON test in P4). There are no `patientId`/`reportId` fields inside nested objects (D11).

### 3.2 Service interface

```java
/** The single business entry point. Every protocol adapter calls exactly one method, exactly once, per request. */
public interface LabResultQueryService {

    /** @throws InvalidRequestException patientId <= 0
     *  @throws PatientNotFoundException no such patient
     *  @throws ServiceUnavailableException database unreachable / pool exhausted / transaction cannot begin */
    PatientView getPatient(long patientId);

    /** Scenario 1. Same exceptions as getPatient. */
    PatientSummaryView getPatientSummary(long patientId);

    /** Scenario 2, the full aggregate, always (no paging, no field selection). Same exceptions as getPatient. */
    PatientLabHistoryView getPatientLabHistory(long patientId);
}
```

RFC 0001 ↔ RPC mapping: `GetPatient` → `getPatient`, `GetPatientSummary` → `getPatientSummary`, `GetPatientLabHistory` → `getPatientLabHistory`.

### 3.3 Errors

```java
public enum ErrorCode {
    INVALID_ARGUMENT,     // 400 / BAD_REQUEST / INVALID_ARGUMENT
    PATIENT_NOT_FOUND,    // 404 / NOT_FOUND   / NOT_FOUND
    UNAVAILABLE,          // 503 / INTERNAL_ERROR + code / UNAVAILABLE
    INTERNAL,             // 500 / INTERNAL_ERROR / INTERNAL
    CLIENT_ABORTED        // metrics/log tag only; never sent to a client
}

public sealed abstract class LabResultException extends RuntimeException
        permits InvalidRequestException, PatientNotFoundException, ServiceUnavailableException {
    protected LabResultException(String message, @Nullable Throwable cause, boolean writableStackTrace) {
        super(message, cause, false, writableStackTrace);
    }
    public abstract ErrorCode code();
}

public final class InvalidRequestException extends LabResultException {
    private final String field;  private final String reason;
    public InvalidRequestException(String field, String reason) {          // message: field + " " + reason
        super(field + " " + reason, null, false);                           // expected error: no stack trace
        this.field = field; this.reason = reason;
    }
    public String field()  { return field; }
    public String reason() { return reason; }
    @Override public ErrorCode code() { return ErrorCode.INVALID_ARGUMENT; }
}

public final class PatientNotFoundException extends LabResultException {
    private final long patientId;
    public PatientNotFoundException(long patientId) {                      // message: "Patient 1001 not found"
        super("Patient " + patientId + " not found", null, false);
        this.patientId = patientId;
    }
    public long patientId() { return patientId; }
    @Override public ErrorCode code() { return ErrorCode.PATIENT_NOT_FOUND; }
}

public final class ServiceUnavailableException extends LabResultException {
    public ServiceUnavailableException(Throwable cause) {                  // message: "Service temporarily unavailable"
        super("Service temporarily unavailable", cause, true);
    }
    @Override public ErrorCode code() { return ErrorCode.UNAVAILABLE; }
}
```

Expected errors (400/404) carry no stack trace, so a misconfigured k6 run does not burn CPU on `fillInStackTrace`, equally for every protocol. Anything that is not a `LabResultException` is `INTERNAL`.

### 3.4 Shared helpers

```java
public enum Protocol { REST, GRAPHQL, GRPC }

/** Parses the textual ids of REST path variables and GraphQL ID arguments, with the same rule for both protocols. */
public final class PatientIds {
    private PatientIds() {}
    /** Long.parseLong(raw); on NumberFormatException (non-numeric, empty, overflow) throws
     *  InvalidRequestException("patientId", "must be a positive 64-bit integer").
     *  It does not check the sign: the service rejects <= 0 with the same field and reason. */
    public static long parse(String raw) { … }
}

/** Single error-logging and counting policy for all adapters (R5/F15). Implemented in config by MicrometerLabErrorRecorder. */
public interface LabErrorRecorder {
    void record(Protocol protocol, ErrorCode code, @Nullable Throwable cause);
}
```

`LabErrorRecorder` policy, as implemented in `config.MicrometerLabErrorRecorder`:

| Code | Log | Counter `lab.errors{protocol,code}` |
|---|---|---|
| `INVALID_ARGUMENT`, `PATIENT_NOT_FOUND` | DEBUG, one line | +1 |
| `UNAVAILABLE` | WARN, one line, no stack trace (cause class + message) | +1 |
| `CLIENT_ABORTED` (Tomcat `ClientAbortException`/`AsyncRequestNotUsableException`; gRPC `CANCELLED`) | WARN, one line, no stack | +1 |
| `INTERNAL` | ERROR **with** stack trace | +1 |

gRPC passes the id as `int64`, so it needs no `PatientIds.parse`. The service's `> 0` check applies to all three protocols.

### 3.5 What P1a delivers

The `core.api` files above, `ArchitectureTest`, `CoreApiTest` (§10). There is no stub implementation in `src/main`: B compiles against the interface and can mock it. Publication = a commit on `berkay/lab-result-service` plus a PR #3 comment pointing at it.

---

## 4. Database schema — `src/main/resources/db/migration/V1__init_schema.sql`

Flyway via `spring-boot-starter-flyway` + `flyway-database-postgresql` (F1/F2, Liquibase 5.0.3 is FSL). `spring.jpa.hibernate.ddl-auto=validate` (D2). Target server PostgreSQL 17 (D12).

```sql
-- V1__init_schema.sql — Backend A, LabResultService. PostgreSQL 17.
-- BIGINT identity BY DEFAULT: the seeder inserts explicit deterministic ids, then realigns the sequences.

CREATE TABLE patient (
    id          BIGINT       GENERATED BY DEFAULT AS IDENTITY,
    tc_no       VARCHAR(11)  NOT NULL,
    full_name   VARCHAR(100) NOT NULL,
    birth_date  DATE         NOT NULL,
    blood_type  VARCHAR(3)   NOT NULL,
    CONSTRAINT pk_patient PRIMARY KEY (id),
    CONSTRAINT uq_patient_tc_no UNIQUE (tc_no),
    CONSTRAINT ck_patient_tc_no_format CHECK (tc_no ~ '^[1-9][0-9]{10}$'),
    CONSTRAINT ck_patient_full_name_not_blank CHECK (btrim(full_name) <> ''),
    CONSTRAINT ck_patient_birth_date CHECK (birth_date >= DATE '1900-01-01'),
    CONSTRAINT ck_patient_blood_type CHECK (blood_type IN ('A+','A-','B+','B-','AB+','AB-','O+','O-'))
);

CREATE TABLE lab_report (
    id           BIGINT       GENERATED BY DEFAULT AS IDENTITY,
    patient_id   BIGINT       NOT NULL,
    doctor_name  VARCHAR(100) NOT NULL,
    sample_type  VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_lab_report PRIMARY KEY (id),
    CONSTRAINT fk_lab_report_patient FOREIGN KEY (patient_id) REFERENCES patient (id) ON DELETE RESTRICT,
    CONSTRAINT ck_lab_report_doctor_not_blank CHECK (btrim(doctor_name) <> ''),
    CONSTRAINT ck_lab_report_sample_type CHECK (sample_type IN ('BLOOD','SERUM','PLASMA','URINE','SALIVA'))
);

-- Serves: history reports (patient_id = ? ORDER BY created_at DESC, id DESC), summary (same + LIMIT 1),
--         and FK lookups (PostgreSQL does not index FK columns automatically).
CREATE INDEX ix_lab_report_patient_created ON lab_report (patient_id, created_at DESC, id DESC);

CREATE TABLE lab_test_item (
    id            BIGINT           GENERATED BY DEFAULT AS IDENTITY,
    report_id     BIGINT           NOT NULL,
    test_code     VARCHAR(20)      NOT NULL,
    test_name     VARCHAR(100)     NOT NULL,
    result_value  DOUBLE PRECISION NOT NULL,
    unit          VARCHAR(20)      NOT NULL,
    is_critical   BOOLEAN          NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_lab_test_item PRIMARY KEY (id),
    CONSTRAINT fk_lab_test_item_report FOREIGN KEY (report_id) REFERENCES lab_report (id) ON DELETE RESTRICT,
    CONSTRAINT uq_lab_test_item_report_test UNIQUE (report_id, test_code),
    CONSTRAINT ck_lab_test_item_result_finite
        CHECK (result_value NOT IN ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8))
);

-- Serves: items WHERE report_id IN (:ids) ORDER BY report_id, id  (history)  and  report_id = ? ORDER BY id (summary).
CREATE INDEX ix_lab_test_item_report_id ON lab_test_item (report_id, id);

-- Dataset provenance (Talimat step 11). Written last by the seeder; read by the verifier and /actuator/info.
CREATE TABLE dataset_meta (
    name               VARCHAR(50) NOT NULL,
    generator_version  INT         NOT NULL,
    seed               BIGINT      NOT NULL,
    patient_count      INT         NOT NULL,
    report_count       INT         NOT NULL,
    item_count         INT         NOT NULL,
    checksum_sha256    CHAR(64)    NOT NULL,
    seeded_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_dataset_meta PRIMARY KEY (name),
    CONSTRAINT ck_dataset_meta_checksum_hex CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$')
);
```

Notes:
- Both FKs are `ON DELETE RESTRICT` (D8). There are no delete paths in the application.
- `VARCHAR` + `CHECK` instead of PostgreSQL `ENUM`; `DOUBLE PRECISION` to match proto `double`, JSON number and GraphQL `Float` (design-r1 §2.2, unchanged).
- `dataset_meta.seeded_at DEFAULT now()` is provenance only. It is not part of the data checksum (§6.5).
- `dataset_meta` has no JPA entity. `validate` ignores tables without an entity; the seeder and verifier use JDBC.
- Flyway's own `flyway_schema_history` table lives in the same schema (`public`).

### 4.1 Entities (`persistence.entity`) — unchanged shape from design-r1 §3.1

`Patient`, `LabReport`, `LabTestItem`: `@Getter @NoArgsConstructor(access = PROTECTED)`, `@Id @GeneratedValue(strategy = IDENTITY) Long id`, every association `FetchType.LAZY` (including `@ManyToOne`), no `Patient.reports`, no `@Data`/`@EqualsAndHashCode`/`@ToString`. `LabReport.items` is **removed**: no read path navigates it, and removing it removes a lazy-load trap. `LabTestItem.critical` maps `is_critical`. Field types: `LocalDate`, `Instant`, `double`, `boolean`, `String`. Entities exist for `validate` and JPQL typing. The read path never hydrates them.

---

## 5. Read path (D1, R9)

### 5.1 Rows (`persistence.row`)

```java
public record ReportRow(long id, String doctorName, String sampleType, Instant createdAt) {}
public record ItemRow(long reportId, long id, String testCode, String testName, double resultValue, String unit, boolean critical) {}
```

`PatientView` is built directly by JPQL. `ItemRow.reportId` exists for grouping only and never reaches a view (D11).

### 5.2 Repository (`persistence.repository.LabReadRepository`)

```java
public interface LabReadRepository extends Repository<Patient, Long> {

    @Query("""
        select new com.benchmark.lab_result_service.core.api.PatientView(p.id, p.tcNo, p.fullName, p.birthDate, p.bloodType)
        from Patient p where p.id = :id""")
    Optional<PatientView> findPatient(@Param("id") long id);

    @Query("""
        select new com.benchmark.lab_result_service.persistence.row.ReportRow(r.id, r.doctorName, r.sampleType, r.createdAt)
        from LabReport r where r.patient.id = :patientId
        order by r.createdAt desc, r.id desc""")
    List<ReportRow> findReports(@Param("patientId") long patientId);

    @Query("""
        select new com.benchmark.lab_result_service.persistence.row.ReportRow(r.id, r.doctorName, r.sampleType, r.createdAt)
        from LabReport r where r.patient.id = :patientId
        order by r.createdAt desc, r.id desc
        limit 1""")
    Optional<ReportRow> findLatestReport(@Param("patientId") long patientId);

    @Query("""
        select new com.benchmark.lab_result_service.persistence.row.ItemRow(i.report.id, i.id, i.testCode, i.testName, i.resultValue, i.unit, i.critical)
        from LabTestItem i where i.report.id in :reportIds
        order by i.report.id, i.id""")
    List<ItemRow> findItems(@Param("reportIds") Collection<Long> reportIds);

    @Query("""
        select new com.benchmark.lab_result_service.persistence.row.ItemRow(i.report.id, i.id, i.testCode, i.testName, i.resultValue, i.unit, i.critical)
        from LabTestItem i where i.report.id = :reportId
        order by i.id""")
    List<ItemRow> findItemsOfReport(@Param("reportId") long reportId);
}
```

`r.patient.id` / `i.report.id` read the FK column without a join (Hibernate's FK-id optimisation, `INFERRED 85%`; the statement-count and EXPLAIN tests catch a regression). HQL `limit` is supported in Hibernate 6+. If the P1 test shows otherwise, replace it with a Spring Data `Limit.of(1)` parameter; the SQL must still end in `LIMIT 1` (or `FETCH FIRST 1 ROWS ONLY`).

### 5.3 Algorithms and statement counts

| Method | Statements, in order |
|---|---|
| `getPatient(id)` | `findPatient` |
| `getPatientSummary(id)` | `findPatient` → stop if empty → `findLatestReport` → stop if empty → `findItemsOfReport(latest.id)` |
| `getPatientLabHistory(id)` | `findPatient` → stop if empty → `findReports` → stop if the list is empty (never run an empty `IN`) → `findItems(reportIds)` |

Per-case statement counts (R9). They are asserted with Hibernate `Statistics.getPrepareStatementCount()` (statistics on in the test profile only):

| Case (fixture id) | `getPatient` | `getPatientSummary` | `getPatientLabHistory` |
|---|---|---|---|
| Absent patient (1001) | 1 → 404 | 1 → 404 | 1 → 404 |
| Existing, no reports (2) | 1 | 2 (`latestReport = null`) | 2 (`reports = []`) |
| Existing with reports (1, 3) | 1 | 3 | 3 |
| Latest report has 0 items (test-only fixture) | 1 | 3 (`items = []`) | 3 (report kept, `items = []`) |
| `id <= 0` | 0 → 400 | 0 → 400 | 0 → 400 |

The counts do not depend on the number of reports or items. GraphQL must produce exactly the same count as REST for the same call (P5 test).

Assembly (`core.service.LabAssembler`), one linear pass: group `ItemRow`s by `reportId` into a `LinkedHashMap<Long, List<LabTestItemView>>` (SQL order is preserved), then map the reports in SQL order, using `List.of()` when a report has no items. All lists are wrapped by the record constructors (`List.copyOf`). Ordering is deterministic: reports `created_at DESC, id DESC`, items `id ASC`.

### 5.4 IN padding

`spring.jpa.properties.hibernate.query.in_clause_parameter_padding=true` (key present in hibernate-core 7.4.5.Final, `REPRODUCED`). Hibernate pads an IN list to the next power of two, repeating the last value: 100 report ids → 128 bind parameters, 10 → 16. This bounds the number of distinct prepared statements. For Scenario 2 (patient 1) the item query always has 100 ids → 128 binds; for patients 3..1000 it has 10 → 16.

### 5.5 Transactions

- **DECISION:** `DefaultLabResultQueryService` uses a `TransactionTemplate` (bean in `config.TransactionConfig`): `readOnly=true`, `PROPAGATION_REQUIRED`, `ISOLATION_DEFAULT` (= PostgreSQL READ COMMITTED), no timeout. It does **not** use `@Transactional`. Reason: with a proxy, `CannotCreateTransactionException` (DB down, Hikari timeout at transaction begin) is thrown *outside* the service method, so the service cannot translate it into the protocol-neutral `ServiceUnavailableException` that Backend B's gRPC handler needs. With the template, the service catches it inside.
- Translation inside the service (everything else propagates and becomes `INTERNAL`):
  - `org.springframework.transaction.CannotCreateTransactionException` → `ServiceUnavailableException`
  - `org.springframework.dao.DataAccessResourceFailureException` (includes `CannotGetJdbcConnectionException`) → `ServiceUnavailableException`
  - `org.springframework.dao.TransientDataAccessResourceException` → `ServiceUnavailableException`
  - Other `DataAccessException`s are **not** translated (Codex X-F8: do not turn every DB error into an outage).
- Hikari `auto-commit` stays at its default (`true`) and is recorded in `/actuator/info` (C-F21 NIT, not adopted: no fairness effect, and one setting fewer to get wrong).
- No second-level or query cache (`hibernate.cache.use_second_level_cache=false`, `use_query_cache=false`, both defaults, set explicitly). No `@Cacheable` anywhere. `open-in-view=false`.
- The three reads are not repeatable-read-consistent with respect to concurrent writers. That is acceptable: the dataset is write-frozen during every run (no writers exist).

### 5.6 Service timer

`lab.service.calls` (Micrometer `Timer`), registered and recorded explicitly in `DefaultLabResultQueryService` (no `@Observed`, R10/V3: `management.observations.annotations.enabled` defaults to `false`, `REPRODUCED`). Tags: `method` ∈ {`getPatient`, `getPatientSummary`, `getPatientLabHistory`}, `outcome` ∈ {`SUCCESS`, `INVALID_ARGUMENT`, `PATIENT_NOT_FOUND`, `UNAVAILABLE`, `INTERNAL`}. The timed region covers validation, the transaction (including connection acquisition), the queries and assembly. It does not cover serialization. "Server request time − `lab.service.calls`" is reported as **protocol + serialization overhead**, never as serialization time (R2).

### 5.7 Index-use assertion and EXPLAIN

- P1 IT (on the P1 test fixture, §6.8): run `EXPLAIN (FORMAT JSON)` for the generated item SQL (patient 1's 100 ids). Assert that the plan contains an index access (`Index Scan`, `Index Only Scan` or `Bitmap Index Scan`) on `lab_test_item` through **an index whose leading column is `report_id`**. The leading column is resolved from the catalogue (`pg_index.indkey[0]` → `pg_attribute.attname`), not from the index name. Likewise, the report query must use an index on `lab_report` whose leading column is `patient_id`. Do not assert index names, node types beyond "index access", or plan shape (R9, X-F11). That the named indexes `ix_lab_test_item_report_id` / `ix_lab_report_patient_created` **exist** is a schema assertion in `SchemaMigrationIT`.
- P8: capture `EXPLAIN (ANALYZE, BUFFERS)` of the five statements for patients 1, 2 and 3 on the pinned PG 17 image into `docs/backend-a/explain/`.

---

## 6. Fixture (synthetic dataset)

### 6.1 Fixture table

| Patient id | Role | Reports × items | Used by |
|---|---|---|---|
| **1** | Scenario 2 (1,000 rows, U2) | **100 × 10 = 1,000 items** | k6 S2/S2b, golden JSON, payload table |
| **2** | Existing patient, **empty history** (edge case) | 0 × 0 | tests and Postman only; not used by k6 |
| **3** | Scenario 1 target (fixed, Talimat 7) | 10 × 10; latest = report **101** with 10 items | k6 S1, golden JSON, payload table |
| 4..1000 | Background volume | 10 × 10 each | selectivity / realism; optional random-id sensitivity runs |
| **1001** | First **absent** id (404 case) | — | error tests, Postman |
| 0, -1 | Invalid id (400) | — | error tests |
| `abc`, `9223372036854775808` | Non-numeric / overflow (400) | — | REST + GraphQL error tests |
| test-only (2001) | Report with 0 items; two reports with identical `created_at` (tie broken by `id DESC`) | — | inserted by `LabReadRepositoryIT` inside a rolled-back test transaction (§6.8), never by the seeder |

Totals (exact; written to `dataset_meta` and asserted by tests): **1,000 patients, 10,080 reports, 100,800 items.**
Derivation: reports = 100 (patient 1) + 0 (patient 2) + 998 × 10 = 10,080; items = 1,000 + 0 + 9,980 × 10 = 100,800. Data volume ≈ 20 MB; it is memory-resident in PostgreSQL by design, and the final report must say so (D5).

### 6.2 Id assignment (explicit, deterministic)

- Patients: id = 1..1000, in generation order.
- Reports: sequential from 1 in generation order. Patient 1 → 1..100; patient 2 → none; patient p ≥ 3 → `101 + (p − 3) × 10` … `+9`. Within a patient, the latest report gets the lowest id (k = 0 first, see §6.3). So patient 3's reports are 101..110 and its latest is 101.
- Items: sequential from 1 in generation order. Patient 1 → 1..1000; patient 3 → 1001..1100 (latest report 101 → items 1001..1010).

### 6.3 Generator (`seed.SyntheticDatasetGenerator`, pure Java, no Spring)

Constants: `GENERATOR_VERSION = 1`, `SEED = 4912026L`, `BASE = Instant.parse("2026-01-01T00:00:00Z")`, `BIRTH_EPOCH = LocalDate.of(1940, 1, 1)`, `DATASET_NAME = "ym491"`. The seed is a constant, **not** a property: a different seed is a different dataset version (Appendix A).

RNG: `new java.util.Random(4912026L)` (D6; its algorithm is specified by the Java SE API, so equal seed and equal call sequence give equal output on every JDK). Single-threaded. Consumption order is part of the contract:

```
for p in 1..1000:
    # patient fields
    do:  d1 = 1 + nextInt(9);  d2..d10 = nextInt(10) each        # 10 calls
    while tcNo(d1..d10) already used                              # redraw the 10 digits only
    first = FIRST_NAMES[nextInt(40)]; last = LAST_NAMES[nextInt(40)]
    birthDate = BIRTH_EPOCH.plusDays(nextInt(23_742))             # 1940-01-01 .. 2004-12-31
    bloodType = BLOOD_TYPES[nextInt(8)]                           # A+,A-,B+,B-,AB+,AB-,O+,O-
    n = (p == 1) ? 100 : (p == 2) ? 0 : 10
    for k in 0..n-1:                                              # k = 0 is the latest report
        doctor = "Dr. " + FIRST_NAMES[nextInt(40)] + " " + LAST_NAMES[nextInt(40)]
        sampleType = SAMPLE_TYPES[nextInt(5)]                     # BLOOD,SERUM,PLASMA,URINE,SALIVA
        createdAt = BASE − k·3 days − nextInt(86_400) seconds     # whole seconds, strictly decreasing in k
        idx = [0..19]; for j in 0..9: swap(idx[j], idx[j + nextInt(20 − j)])   # partial Fisher–Yates
        tests = sort_ascending(idx[0..9])                         # item order = catalogue order
        for t in tests:  value = drawValue(CATALOGUE[t])
```

`tcNo`: the 10 drawn digits, then `d11 = (d1 + … + d10 + 1) mod 10`. The official TC Kimlik rule requires `d11 = (d1 + … + d10) mod 10`, so **every** value is formally invalid as a real identity (D4). Every value also matches `^[1-9][0-9]{10}$` and is unique (generator `Set` + `uq_patient_tc_no`).

`drawValue(c)`:
```
u = nextDouble()
if u < 0.05:                                       # ~5 % critical
    low  = (c.criticalLow > 0) && nextBoolean()
    f    = nextDouble()
    x    = low ? c.criticalLow * (0.5 + 0.4 f) : c.criticalHigh * (1.1 + 0.5 f)
else:
    x    = c.normalMin + nextDouble() * (c.normalMax − c.normalMin)
resultValue = Math.round(x * 10) / 10.0            # one decimal
isCritical  = resultValue < c.criticalLow || resultValue > c.criticalHigh     # derived after rounding
```

`isCritical` is derived from the stored value, so it is always consistent. A value rounded back into range is simply not critical. Because of rounding `resultValue` can be `0.0` (TSH); proto3 then omits it on the wire. That is a real Protobuf property, kept and reported (design-r1 §7 asymmetry 2).

### 6.4 Catalogues (`src/main/resources/seed/`, UTF-8, committed in P2)

Test catalogue (20 entries, synthetic mnemonics; **no LOINC codes**). Columns: code, name, unit, normalMin, normalMax, criticalLow, criticalHigh. `criticalLow = 0` means "no low-critical draw".

| # | code | name | unit | nMin | nMax | cLow | cHigh |
|---|---|---|---|---|---|---|---|
| 0 | GLU | Glucose | mg/dL | 70 | 100 | 40 | 400 |
| 1 | HGB | Hemoglobin | g/dL | 12.0 | 17.5 | 7.0 | 20.0 |
| 2 | WBC | Leukocytes | 10^3/uL | 4.0 | 11.0 | 2.0 | 30.0 |
| 3 | PLT | Platelets | 10^3/uL | 150 | 400 | 50 | 1000 |
| 4 | NA | Sodium | mmol/L | 135 | 145 | 120 | 160 |
| 5 | K | Potassium | mmol/L | 3.5 | 5.1 | 2.5 | 6.5 |
| 6 | CL | Chloride | mmol/L | 98 | 107 | 80 | 120 |
| 7 | CA | Calcium | mg/dL | 8.6 | 10.2 | 6.5 | 13.0 |
| 8 | CREA | Creatinine | mg/dL | 0.6 | 1.2 | 0.3 | 4.0 |
| 9 | BUN | Urea nitrogen | mg/dL | 7 | 20 | 2 | 100 |
| 10 | ALT | Alanine aminotransferase | U/L | 7 | 56 | 0 | 1000 |
| 11 | AST | Aspartate aminotransferase | U/L | 10 | 40 | 0 | 1000 |
| 12 | ALP | Alkaline phosphatase | U/L | 44 | 147 | 0 | 1000 |
| 13 | TBIL | Total bilirubin | mg/dL | 0.1 | 1.2 | 0 | 15.0 |
| 14 | ALB | Albumin | g/dL | 3.5 | 5.0 | 1.5 | 6.5 |
| 15 | CHOL | Total cholesterol | mg/dL | 125 | 200 | 0 | 500 |
| 16 | TRIG | Triglycerides | mg/dL | 50 | 150 | 0 | 1000 |
| 17 | CRP | C-reactive protein | mg/L | 0.1 | 5.0 | 0 | 200 |
| 18 | TSH | Thyroid-stimulating hormone | mIU/L | 0.4 | 4.0 | 0.01 | 20.0 |
| 19 | HBA1C | Hemoglobin A1c | % | 4.0 | 5.6 | 0 | 14.0 |

The values are synthetic and illustrative, not clinical guidance.

Name lists (exactly 40 each, in this order; Turkish characters are intentional, because they make UTF-8 byte counting matter in QA's measurements):
- `FIRST_NAMES`: Ahmet, Ayşe, Mehmet, Fatma, Mustafa, Emine, Ali, Hatice, Hüseyin, Zeynep, Hasan, Elif, İbrahim, Meryem, Murat, Şerife, Ömer, Sultan, Yusuf, Hacer, Can, Ece, Deniz, Selin, Emre, Büşra, Burak, Gizem, Oğuz, Özge, Kerem, Merve, Barış, İrem, Tolga, Damla, Uğur, Gül, Serkan, Çağla.
- `LAST_NAMES`: Yılmaz, Kaya, Demir, Tekin, Uçar, Yıldız, Yıldırım, Öztürk, Aydın, Özdemir, Arslan, Doğan, Kılıç, Aslan, Çetin, Kara, Koç, Kurt, Özkan, Şimşek, Polat, Özcan, Korkmaz, Çakır, Erdoğan, Yavuz, Karaca, Acar, Şen, Aktaş, Güler, Yalçın, Altun, Bozkurt, Bulut, Keskin, Ünal, Turan, Erdem, Sezer.

Team members' surnames are deliberately left out. A P2 unit test asserts both lists have 40 distinct entries.

### 6.5 Canonical digest (`seed.CanonicalDigest`)

SHA-256 over UTF-8 text, **table-major, id ascending**: all patients, then all reports, then all items. One line per row, `|`-separated, terminated by `\n`:

```
P|<id>|<tcNo>|<fullName>|<birthDate ISO yyyy-MM-dd>|<bloodType>
R|<id>|<patientId>|<doctorName>|<sampleType>|<createdAt Instant.toString()>
I|<id>|<reportId>|<testCode>|<testName>|<Double.toString(resultValue)>|<unit>|<true|false>
```

Output: 64 lowercase hex characters. The generator computes it from its in-memory rows. The full verifier recomputes it from the database (`SELECT … ORDER BY id` per table, `timestamptz` read as `OffsetDateTime` → `toInstant()`). The **expected value** for `GENERATOR_VERSION = 1` lives in exactly one place, the main resource **`src/main/resources/seed/dataset-v1.json`**, read by `seed.DatasetManifest.expected()`:

```json
{ "name": "ym491", "generatorVersion": 1, "seed": 4912026,
  "patientCount": 1000, "reportCount": 10080, "itemCount": 100800,
  "checksumSha256": "<64 hex, computed and committed in P2>" }
```

Both the runtime `DatasetVerifier` (`full` mode, and the counts in `cheap` mode) and `SyntheticDatasetGeneratorTest` read it. The test asserts that the generator output (counts, constants, digest) equals the descriptor, so there is no second copy of the checksum in test code. Any generator change fails that test and requires a deliberate version bump (a new `dataset-v2.json`).

### 6.6 Seeder (`seed.DatasetSeeder`, `ApplicationRunner`, `@Order(0)`, active only when `app.seed.enabled=true`)

Everything runs in **one** transaction:
1. `SELECT pg_advisory_xact_lock(491)`.
2. Read `dataset_meta WHERE name = 'ym491'`.
   - Row present with `generator_version = 1` and `seed = 4912026` → log `dataset present, skipping seed` → done (the verifier runs next).
   - Row absent **and** `patient`, `lab_report`, `lab_test_item` all empty → go to step 3.
   - Anything else (other version/seed, or rows without a manifest) → throw `IllegalStateException("Dataset mismatch: … Reset the database volume manually.")`. Startup fails. **Never delete, truncate or update.**
3. `JdbcTemplate.batchUpdate` with explicit ids, batches of 1,000, in the order patients → reports → items (the JDBC URL has `reWriteBatchedInserts=true`).
4. `SELECT setval(pg_get_serial_sequence('<t>','id'), (SELECT max(id) FROM <t>))` for the three tables.
5. `ANALYZE patient, lab_report, lab_test_item` (allowed inside a transaction block).
6. Insert the `dataset_meta` row (counts + checksum) **last**.
7. Commit; log `seeded 1000/10080/100800 rows in <ms> ms` (P2 DoD: seed time logged).

### 6.7 Verification policy (R6, `seed.DatasetVerifier`, `ApplicationRunner`, `@Order(1)`)

`app.dataset.verify-mode`, env `APP_DATASET_VERIFY_MODE`:

| Mode | When | What | On failure |
|---|---|---|---|
| `cheap` (default) | every startup | manifest exists; `generator_version = 1`, `seed = 4912026`; `count(*)` of the three tables = manifest counts = expected 1000/10080/100800 | throw, startup fails (container exits non-zero) |
| `full` | **one-shot gate**: before every measured campaign and at P8 freeze (start once with `APP_DATASET_VERIFY_MODE=full`) | `cheap` + recompute the canonical digest from the DB and compare with the `dataset_meta` row and with `seed/dataset-v1.json` (§6.5) | throw, startup fails |
| `off` | unit/slice tests with tiny fixtures only | nothing | — |

Readiness: the runners finish before `ApplicationReadyEvent`, so `/actuator/health/readiness` reports `UP` only after seeding and verification (`INFERRED 85%`; the P2 IT asserts that a failed verification prevents readiness). An empty database without `seed` fails closed (X-F9), instead of serving 404s.

### 6.8 P1 test fixture (independent of the P2 generator, P0-F5)

P1 runs before the seeder exists, and its ITs must not depend on it. `src/test/resources/fixtures/p1-fixture.sql` is a deterministic SQL script (no `random()`, no `now()`). It has the **same shape and volume** as the production fixture, so planner statistics and index choices are representative, but simpler values:

```sql
-- p1-fixture.sql — test-only. Same shape as §6.1 (1000 / 10080 / 100800), simple deterministic values.
INSERT INTO patient (id, tc_no, full_name, birth_date, blood_type)
SELECT p, (10000000000 + p)::text, 'Test Patient ' || p, DATE '1980-01-01' + (p % 9000),
       (ARRAY['A+','A-','B+','B-','AB+','AB-','O+','O-'])[1 + p % 8]
FROM generate_series(1, 1000) AS p;

-- reports: patient 1 → ids 1..100; patient 2 → none; patient p >= 3 → 101 + (p-3)*10 + k   (k = 0 latest)
INSERT INTO lab_report (id, patient_id, doctor_name, sample_type, created_at)
SELECT CASE WHEN p = 1 THEN 1 + k ELSE 101 + (p - 3) * 10 + k END, p, 'Dr. Test ' || p,
       (ARRAY['BLOOD','SERUM','PLASMA','URINE','SALIVA'])[1 + k % 5],
       TIMESTAMPTZ '2026-01-01 00:00:00+00' - make_interval(days => 3 * k)
FROM generate_series(1, 1000) AS p
CROSS JOIN LATERAL generate_series(0, CASE WHEN p = 1 THEN 99 WHEN p = 2 THEN -1 ELSE 9 END) AS k;

-- items: 10 per report, item id = (report_id - 1) * 10 + j, test codes T01..T10
INSERT INTO lab_test_item (id, report_id, test_code, test_name, result_value, unit, is_critical)
SELECT (r.id - 1) * 10 + j, r.id, 'T' || lpad(j::text, 2, '0'), 'Test ' || j, j + 0.5, 'u', (j = 10)
FROM lab_report r CROSS JOIN generate_series(1, 10) AS j;

SELECT setval(pg_get_serial_sequence('patient', 'id'),       (SELECT max(id) FROM patient));
SELECT setval(pg_get_serial_sequence('lab_report', 'id'),    (SELECT max(id) FROM lab_report));
SELECT setval(pg_get_serial_sequence('lab_test_item', 'id'), (SELECT max(id) FROM lab_test_item));
ANALYZE patient, lab_report, lab_test_item;
```

- The script has not been executed yet (`ASSUMED`; documents-only round). `SchemaMigrationIT`/`LabReadRepositoryIT` execute it first in P1, and they assert the resulting counts 1000/10080/100800.
- It is loaded once per IT class with `@Sql(scripts = "/fixtures/p1-fixture.sql", executionPhase = BEFORE_TEST_CLASS)` on a class-owned container. The ids match §6.2, so patients 1/2/3/1001 have the same roles as in production. The tcNo values are test-only; they satisfy the DB `CHECK` but are not generator output.
- Edge cases (patient 2001: one report with 0 items, two reports with identical `created_at`) are inserted by `LabReadRepositoryIT` in the test method itself, under `@Transactional` rollback.
- Before P3 exists, repository ITs exercise the §5.3 statement sequence through a test-only helper `ReadSequence` (`src/test`), which calls the repository methods in the documented order. P3's real service is not pulled forward.
- From P2 on, ITs that load this fixture instead of seeding set `app.seed.enabled=false` and `app.dataset.verify-mode=off`. ITs that need the real dataset use the `seed` profile and `cheap`.

---

## 7. Protocol contracts

### 7.1 REST (`adapter.rest`, port 8080)

| Method & path | Service call | 200 body |
|---|---|---|
| `GET /api/v1/patients/{patientId}` | `getPatient` | `PatientView` |
| `GET /api/v1/patients/{patientId}/summary` | `getPatientSummary` | `PatientSummaryView` — **Scenario 1** (k6: patient 3) |
| `GET /api/v1/patients/{patientId}/lab-history` | `getPatientLabHistory` | `PatientLabHistoryView` — **Scenario 2** (k6: patient 1) |

- `{patientId}` is bound as `String` and parsed with `PatientIds.parse`. The parse rule is therefore identical to GraphQL, and the error is ours, not a framework `MethodArgumentTypeMismatchException`.
- Response `Content-Type: application/json`. No compression, no ETag, no `Content-Length` guarantee: S2 bodies are chunked (R2). REST adds no extra headers.
- Field names and types (exact):

| JSON path | Type | Example (illustrative; the golden files hold the real values) |
|---|---|---|
| `patient.id` | number | `3` |
| `patient.tcNo` | string (11 digits) | `"48213907653"` |
| `patient.fullName` | string | `"Zeynep Öztürk"` |
| `patient.birthDate` | string `yyyy-MM-dd` | `"1984-03-12"` |
| `patient.bloodType` | string | `"A+"` |
| `latestReport` (S1) | object or `null` | |
| `reports` (S2) | array (may be `[]`) | |
| `…id` (report) | number | `101` |
| `…doctorName` | string | `"Dr. Elif Kaya"` |
| `…sampleType` | string | `"SERUM"` |
| `…createdAt` | string ISO-8601 instant, UTC, `Z`, whole seconds | `"2025-12-31T17:03:21Z"` |
| `…items[]` | array (may be `[]`) | |
| `…items[].id` | number | `1001` |
| `…items[].testCode` / `testName` / `unit` | string | `"GLU"` / `"Glucose"` / `"mg/dL"` |
| `…items[].resultValue` | number (double) | `92.4` |
| `…items[].isCritical` | boolean | `false` |

Scenario 1 shape:
```json
{ "patient": { "id": 3, "tcNo": "…", "fullName": "…", "birthDate": "…", "bloodType": "…" },
  "latestReport": { "id": 101, "doctorName": "…", "sampleType": "…", "createdAt": "…Z",
                    "items": [ { "id": 1001, "testCode": "…", "testName": "…", "resultValue": 0.0, "unit": "…", "isCritical": false } ] } }
```
Scenario 2 shape: `{ "patient": {…}, "reports": [ { …same report object… } ] }` (100 reports × 10 items for patient 1).

Errors: RFC 9457 `ProblemDetail`, `Content-Type: application/problem+json`, produced by `RestExceptionHandler` (`@RestControllerAdvice`). `spring.mvc.problemdetails.enabled` stays `false` (default, `REPRODUCED`); our advice owns the format.

```json
{ "type": "about:blank", "title": "Not Found", "status": 404,
  "detail": "Patient 1001 not found", "instance": "/api/v1/patients/1001/lab-history", "code": "PATIENT_NOT_FOUND" }
```

`detail` for 5xx is fixed text (`"Service temporarily unavailable"` / `"Internal error"`), never an exception message, SQL or stack trace. Unmatched routes and wrong methods keep Spring's default 404/405 handling: they are not part of the contract, and k6 never calls them.

### 7.2 GraphQL (`adapter.graphql`, `POST /graphql` on 8080)

`src/main/resources/graphql/schema.graphqls`:

```graphql
type Query {
  patient(id: ID!): Patient
  "Scenario 1 — profile + most recent report with its items"
  patientSummary(patientId: ID!): PatientSummary
  "Scenario 2 — full lab history"
  patientLabHistory(patientId: ID!): PatientLabHistory
}

type Patient {
  id: ID!
  tcNo: String!
  fullName: String!
  "ISO-8601 date, yyyy-MM-dd"
  birthDate: String!
  bloodType: String!
}

type LabReport {
  id: ID!
  doctorName: String!
  sampleType: String!
  "ISO-8601 instant, UTC, e.g. 2025-12-31T17:03:21Z"
  createdAt: String!
  items: [LabTestItem!]!
}

type LabTestItem {
  id: ID!
  testCode: String!
  testName: String!
  resultValue: Float!
  unit: String!
  isCritical: Boolean!
}

type PatientSummary { patient: Patient!  latestReport: LabReport }
type PatientLabHistory { patient: Patient!  reports: [LabReport!]! }
```

Resolvers (D9): three `@QueryMapping` methods taking `@Argument String id` / `@Argument String patientId`, parsed with `PatientIds.parse`. Each calls one service method and returns the core record. Nested fields are default property reads on the records. There is no `@SchemaMapping`, `@BatchMapping`, DataLoader, custom scalar, depth/complexity instrumentation or `PreparsedDocumentProvider`. `birthDate`/`createdAt` serialize through graphql-java's `String` coercion (`toString()`), which equals Jackson 3's ISO output (`INFERRED 80%`; pinned by the P7a equivalence test). GraphQL `ID` is a JSON string: a protocol-inherent asymmetry (D11).

Sample operations (they go into the Postman collection; QA uses S1/S2/S2b verbatim):

```graphql
# S1 — Scenario 1, all fields. variables: {"id":"3"}
query S1($id: ID!) {
  patientSummary(patientId: $id) {
    patient { id tcNo fullName birthDate bloodType }
    latestReport { id doctorName sampleType createdAt
      items { id testCode testName resultValue unit isCritical } }
  }
}

# S2 — Scenario 2, all fields. variables: {"id":"1"}
query S2($id: ID!) {
  patientLabHistory(patientId: $id) {
    patient { id tcNo fullName birthDate bloodType }
    reports { id doctorName sampleType createdAt
      items { id testCode testName resultValue unit isCritical } }
  }
}

# S2b — over-fetch experiment, test name + result only. variables: {"id":"1"}
query S2b($id: ID!) {
  patientLabHistory(patientId: $id) { reports { items { testName resultValue } } }
}
```

S2b calls the **same** `getPatientLabHistory`: same 3 statements, same rows, same assembly. Only serialization differs (deliberate, identical query plan; design-r1 §6.3). The report must say so. Additional Postman-only samples: `patient(id:"3")`, empty history `patientLabHistory(patientId:"2")`, not found `"1001"`, bad id `"abc"`.

HTTP: `POST /graphql`, `Content-Type: application/json`, **`Accept: application/json`**, body `{"query": "...", "operationName": "S1", "variables": {"id": "3"}}`. Field errors return HTTP 200 with an `errors` array. Bench settings: GraphiQL off, introspection off (§8).

Errors (`GraphQlExceptionResolver`, a `DataFetcherExceptionResolverAdapter`); the field value is `null`:

| Exception | `errors[].extensions.classification` (Spring `ErrorType`) | `errors[].extensions.code` | `message` |
|---|---|---|---|
| `InvalidRequestException` | `BAD_REQUEST` | `INVALID_ARGUMENT` | exception message |
| `PatientNotFoundException` | `NOT_FOUND` | `PATIENT_NOT_FOUND` | exception message |
| `ServiceUnavailableException` | `INTERNAL_ERROR` | `UNAVAILABLE` | `Service temporarily unavailable` |
| any other | `INTERNAL_ERROR` | `INTERNAL` | `Internal error` |
| query syntax/validation error (graphql-java, request error) | `InvalidSyntax` / `ValidationError` | — | graphql-java default; counted once as `INVALID_ARGUMENT` by the interceptor (§7.4) |

### 7.3 gRPC (proposal; Backend B implements, port 9091)

The shape is RFC 0001 §1 (= verdict D7): `Patient`, `LabReport`, `PatientSummaryResponse`, `PatientLabHistoryResponse` with `reserved 1, 2`; RPCs `GetPatient`, `GetPatientSummary`, `GetPatientLabHistory`; `created_at = google.protobuf.Timestamp`; `birth_date` = ISO string. Until PR #3 is accepted, this is a proposal (§12 Q4). Adapter rules: one service call per RPC, mapping only, no repository/entity access (ArchUnit rule 1).

### 7.4 Error mapping (all protocols)

| `ErrorCode` | Trigger | REST | GraphQL | gRPC (proposal, B) |
|---|---|---|---|---|
| `INVALID_ARGUMENT` | non-numeric/overflow id (`PatientIds.parse`), `id <= 0` (service) | 400, `code=INVALID_ARGUMENT` | 200, `BAD_REQUEST`, `code=INVALID_ARGUMENT` | `INVALID_ARGUMENT` |
| `PATIENT_NOT_FOUND` | no such patient | 404, `code=PATIENT_NOT_FOUND` | 200, `NOT_FOUND`, `code=PATIENT_NOT_FOUND` | `NOT_FOUND` |
| `UNAVAILABLE` | `ServiceUnavailableException` (DB down, Hikari timeout 2000 ms, tx cannot begin) | 503, `code=UNAVAILABLE` | 200, `INTERNAL_ERROR`, `code=UNAVAILABLE` | `UNAVAILABLE` |
| `INTERNAL` | anything else | 500, `code=INTERNAL` | 200, `INTERNAL_ERROR`, `code=INTERNAL` | `INTERNAL` |
| `CLIENT_ABORTED` | client disconnected / cancelled | — (log + counter) | — | `CANCELLED` (log + counter) |

`LabErrorRecorder.record(protocol, code, cause)` is called **exactly once per failed request**. The counting unit is the request (one HTTP request, one gRPC call), not the field. Hooks per protocol:
- **REST:** `RestExceptionHandler`. One exception per request, so one call.
- **gRPC (B):** the `GrpcExceptionHandler`, once per call. Client cancellation is recorded as `CLIENT_ABORTED`.
- **GraphQL:** `adapter.graphql.GraphQlErrorAccountingInterceptor`, a `WebGraphQlInterceptor`, is the **only** GraphQL caller of `record` (P0-F8). Per request it puts a mutable `LabGraphQlErrors` holder into the `GraphQLContext` (`request.configureExecutionInput(...)`). `GraphQlExceptionResolver` only builds the `GraphQLError` (classification + `extensions.code`) and adds `(code, throwable)` to the holder; it does not log or count. When the response completes, the interceptor computes the request's single code: field errors from the holder, plus request errors from `response.getErrors()` whose classification is graphql-java `InvalidSyntax` or `ValidationError` (these never reach the resolver), which count as `INVALID_ARGUMENT` with no cause. If there are several, the most severe wins: `INTERNAL` > `UNAVAILABLE` > `INVALID_ARGUMENT` > `PATIENT_NOT_FOUND`. Then it calls `record(GRAPHQL, code, cause)` once and logs per §3.4 (INTERNAL with the stack trace of the recorded throwable). A document with two failing root fields therefore counts as one failed request. The benchmark documents have a single root field, so this changes nothing for valid runs.
- **Client aborts** (all protocols) are not automatically tested. They are checked manually in the P8 shakedown (§10, R8).

---

## 8. Configuration

Files: `src/main/resources/application.yaml` (default), `application-seed.yaml`, `application-bench.yaml`, `application-bench-nomon.yaml`, `application-docs.yaml`; `src/test/resources/application-test.yaml`. **Delete `src/main/resources/application.properties` in P1** (it would override the YAML; R10). Every key below was checked against the Boot 4.1.1 / spring-grpc / springdoc configuration metadata (`REPRODUCED`), except `spring.jpa.properties.*` entries, which are free-form Hibernate settings checked against hibernate-core 7.4.5.Final (`REPRODUCED`). Map-style keys (`management.metrics.enable.*`, `management.observations.enable.*`, `management.metrics.tags.*`) are valid map entries of keys present in the metadata.

### 8.1 Properties per profile

`—` = not set (inherits default). Profiles are activated in this order: `bench,seed`, and for the monitoring-off arm `bench,bench-nomon,seed` (later profiles win).

| Key | Boot default | `default` (application.yaml) | `seed` | `bench` | `bench-nomon` (overlay on bench) | `docs` | test |
|---|---|---|---|---|---|---|---|
| `spring.application.name` | — | `lab-result-service` | | | | | |
| `server.port` | 8080 | `8080` | | | | | random (`RANDOM_PORT`) |
| `management.server.port` | — (same as server) | `8081` | | | | | random |
| `spring.grpc.server.port` | — (no default) | `9091` | | | | | `0` (random) |
| `server.tomcat.threads.max` | 200 | `200` (explicit, D10) | | | | | |
| `server.tomcat.max-keep-alive-requests` | 100 | — | | **`-1`** (R1) | | | |
| `server.tomcat.keep-alive-timeout` | — (connection timeout) | — | | **`60s`** (R1) | | | |
| `server.http2.enabled` | false | — | | `false` (recorded, R1) | | | |
| `server.compression.enabled` | false | — | | `false` (recorded, R1) | | | |
| `spring.threads.virtual.enabled` | false | `false` (explicit) | | | | | |
| `spring.datasource.url` | — | `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:labdb}?reWriteBatchedInserts=true` | | | | | Testcontainers `@ServiceConnection` |
| `spring.datasource.username` / `password` | — | `${DB_USER:lab}` / `${DB_PASSWORD:lab}` | | | | | container |
| `spring.datasource.hikari.pool-name` | — | `lab-hikari` | | | | | |
| `spring.datasource.hikari.maximum-pool-size` | (10) | `20` (D12) | | | | | |
| `spring.datasource.hikari.minimum-idle` | (= max) | `20` (fixed pool) | | | | | |
| `spring.datasource.hikari.connection-timeout` | (30000) | `2000` (D12) | | | | | |
| `spring.datasource.hikari.max-lifetime` | (1800000) | `1800000` | | | | | |
| `spring.jpa.open-in-view` | true | `false` | | | | | |
| `spring.jpa.hibernate.ddl-auto` | — | `validate` | | | | | |
| `spring.jpa.show-sql` | false | `false` | | | | | |
| `spring.jpa.properties.hibernate.query.in_clause_parameter_padding` | false | `true` | | | | | |
| `spring.jpa.properties.hibernate.jdbc.time_zone` | — | `UTC` | | | | | |
| `spring.jpa.properties.hibernate.default_batch_fetch_size` | — | `100` (safety net only) | | | | | |
| `spring.jpa.properties.hibernate.cache.use_second_level_cache` / `use_query_cache` | false | `false` / `false` | | | | | |
| `spring.jpa.properties.hibernate.generate_statistics` | false | — | | | | | **`true`** |
| `spring.flyway.enabled` | true | `true` | | | | | |
| `spring.flyway.locations` | `classpath:db/migration` | — | | | | | |
| `spring.graphql.http.path` | `/graphql` | `/graphql` | | | | | |
| `spring.graphql.schema.introspection.enabled` | **true** | `false` | | `false` | | `true` | `true` |
| `spring.graphql.graphiql.enabled` | false | `false` | | `false` | | `true` | |
| `spring.grpc.server.reflection.enabled` | **true** | `true` | | **`false`** (RFC 3.3) | | `true` | |
| `spring.grpc.server.observation.enabled` | true | — | | — | **`false`** | | |
| `spring.grpc.server.keepalive.connection.max-age` / `max-idle-time` | — (none) | — (no forced recycling, R1) | | — | | | |
| `management.endpoints.web.exposure.include` | `health` | `health,info,prometheus` | | | | | |
| `management.endpoint.health.probes.enabled` | true | `true` | | | | | |
| `management.info.java.enabled` / `os.enabled` / `process.enabled` | false | `true` / `true` / `true` | | | | | |
| `management.info.build.enabled` | true | — | | | | | |
| `management.metrics.tags.application` | — | `lab-result-service` | | | | | |
| `management.prometheus.metrics.export.enabled` | true | `true` | | `true` | **`false`** | | |
| `management.observations.enable.all` | — | — | | — | **`false`** | | |
| `management.metrics.enable.all` | — | — | | — | **`false`** | | |
| `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled` (only if springdoc is added, R4) | — | `false` | | `false` | `false` | `true` | |
| `app.cors.allowed-origins` (A, R3) | — | `${APP_CORS_ALLOWED_ORIGINS:}` (empty = CORS off) | | ignored: `CorsConfig` is `@Profile("!bench & !bench-nomon")` | ignored | | |
| `app.seed.enabled` (A) | — | `false` | **`true`** | | | | per test |
| `app.dataset.verify-mode` (A) | — | `${APP_DATASET_VERIFY_MODE:cheap}` | | | | | `off` for slice tests; ITs set `cheap`/`full` explicitly |

Notes:
- `bench-nomon` = observations off + all meters denied + Prometheus export off + gRPC observation off (R1/F14 full definition). `/actuator/prometheus` returns 404 and `lab.service.calls` becomes a no-op meter. What the off-arm does **not** remove (JVM itself, external cAdvisor/Prometheus host load) is stated in QA's D13 method (§12 Q7).
- Percentile histograms stay off (no `management.metrics.distribution.percentiles-histogram.*`). k6 is the source of truth for p95/p99.
- `management.server.port=8081` starts a child context with its own Tomcat and thread pool (R7; shared CPU).
- CORS (R3): `config.CorsConfig` registers one `WebMvcConfigurer` mapping for `/api/**` and `/graphql`, methods `GET, POST, OPTIONS`, origins from `app.cors.allowed-origins` (comma-separated). Two independent conditions: (1) the class is annotated **`@Profile("!bench & !bench-nomon")`**, so under `bench`/`bench-nomon` the configurer bean does not exist; (2) outside bench it registers a mapping only when the list is non-empty. Condition (1) does not depend on property-source precedence: an environment variable `APP_CORS_ALLOWED_ORIGINS` outranks the empty `app.cors.allowed-origins` in `application-bench.yaml` (referee `REPRODUCED` with Spring Core 7.0.9), so a property value alone cannot guarantee "off in bench" (P0-F3). The only way to get CORS in a benchmark container is to drop the bench profile, which `/actuator/info` `activeProfiles` records. The global CORS configuration also reaches GraphQL's `RouterFunctionMapping` (referee `REPRODUCED`, Spring MVC 7.0.9). `spring.graphql.cors.*` (exists, `REPRODUCED`) is **not** used, so there is one mechanism. `Timing-Allow-Origin`/`exposedHeaders` are added only if Frontend asks (§12 Q1).
- Logging: no SQL logging and no per-request access log in any profile. Default Logback console appender.

### 8.2 Environment variables (consumed by A's configuration)

| Env var | Maps to | Default | Notes |
|---|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | datasource URL | `localhost` / `5432` / `labdb` | |
| `DB_USER` / `DB_PASSWORD` | datasource credentials | `lab` / `lab` | DevOps injects the real password |
| `SPRING_PROFILES_ACTIVE` | profiles | — | `bench,seed` (seed is idempotent, so it is safe on every boot); monitoring-off arm `bench,bench-nomon,seed` |
| `APP_DATASET_VERIFY_MODE` | `app.dataset.verify-mode` | `cheap` | `full` once before each measured campaign / at P8 freeze |
| `APP_CORS_ALLOWED_ORIGINS` | `app.cors.allowed-origins` | empty | ignored under `bench` |
| `JAVA_TOOL_OPTIONS` | JVM flags | — | DevOps: `-XX:+UseG1GC -Xms512m -Xmx512m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError -XX:+PrintCommandLineFlags` (values proposed; DevOps sizes, §12 Q6) |

### 8.3 Ports and probes

| Port | Content | Notes |
|---|---|---|
| 8080 | REST `/api/v1/**`, GraphQL `/graphql` | Tomcat, HTTP/1.1, plaintext |
| 8081 | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info`, `/actuator/prometheus` | separate Tomcat; Prometheus scrape target |
| 9091 | gRPC (Netty, h2c plaintext) | not 9090 (Prometheus default, D10) |

---

## 9. Build and dependencies

### 9.1 Dependencies to add to `services/LabResultService/pom.xml`

"Managed" = version from the Spring Boot 4.1.1 BOM (no `<version>` element). Every row was resolved in this session into a scratch repository (`dependency:get`, `REPRODUCED`); licences were read from the resolved POMs (`REPRODUCED`).

| Phase | Coordinates | Scope | Version | Licence |
|---|---|---|---|---|
| P1 | `org.springframework.boot:spring-boot-starter-flyway` | compile | managed (4.1.1) | Apache-2.0 |
| P1 | `org.flywaydb:flyway-database-postgresql` | compile | managed (**12.4.0**; Central has 13.11.1, do **not** override the BOM) | Apache-2.0 (flyway-parent 12.4.0) |
| P1 | `org.springframework.boot:spring-boot-starter-flyway-test` | test | managed (4.1.1) | Apache-2.0 |
| P1 | `org.springframework.boot:spring-boot-testcontainers` | test | managed (4.1.1) | Apache-2.0 |
| P1 | `org.testcontainers:testcontainers-postgresql` | test | managed (2.0.5, Testcontainers 2.x artifact name) | MIT |
| P1 | `org.testcontainers:testcontainers-junit-jupiter` | test | managed (2.0.5) | MIT |
| P1a | `com.tngtech.archunit:archunit-junit5` | test | **pinned `1.5.1`** (not in the BOM; latest release on Central, 2026-09-25) | Apache-2.0 (the shaded ASM inside is BSD) |
| P6 | `io.micrometer:micrometer-registry-prometheus` | compile | managed (1.17.1, Micrometer BOM) | Apache-2.0 |
| P4 (optional, R4) | `org.springdoc:springdoc-openapi-starter-webmvc-ui` | compile | **pinned `3.1.1`** (not in the BOM; built against Boot 4.1.0) | Apache-2.0 |

Already present and unchanged: actuator, data-jpa, graphql, webmvc, postgresql (runtime, 42.7.13), lombok, common-proto, grpc-server, and their `-test` starters. No AspectJ starter (explicit Timer, §5.6). No Datafaker, no `graphql-java-extended-scalars`, no `git-commit-id-maven-plugin` (LGPL).

springdoc is added only if, in P4, it resolves **and** the application starts on 4.1.1 with it. Otherwise the Postman collection alone satisfies R4.

### 9.2 Plugins

```xml
<!-- build/plugins, P1: bind failsafe (V1: the parent only has it in pluginManagement) -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <!-- version 3.5.6 and executions integration-test + verify are inherited from
         spring-boot-starter-parent 4.1.1 pluginManagement (REPRODUCED, parent pom l.118–130) -->
</plugin>

<!-- P6: build-info for /actuator/info -->
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <executions>
        <execution>
            <goals><goal>build-info</goal></goals>
            <configuration>
                <additionalProperties>
                    <git.sha>${git.sha}</git.sha>
                </additionalProperties>
            </configuration>
        </execution>
    </executions>
</plugin>
```

with `<properties><git.sha>unknown</git.sha></properties>`, overridden by `-Dgit.sha=$GIT_SHA` in the image build.

Test naming: surefire runs `*Test`/`*Tests`; failsafe runs `*IT`. Every test that needs PostgreSQL ends in `IT`. The existing `LabResultServiceApplicationTests.contextLoads` becomes `LabResultServiceApplicationIT` on the Testcontainers base (it would fail under surefire once a datasource is required).

### 9.3 Build recipe (for DevOps)

- Build context: **repository root**. The module needs the root parent POM and the `CommonProto` reactor module.
- There is **no wrapper at the repository root**. The module wrapper is `services/LabResultService/mvnw` (`distributionType=only-script`, it downloads Maven 3.10.0 at build time; no `maven-wrapper.jar` needed, `DIRECT SOURCE` `.mvn/wrapper/maven-wrapper.properties`). Command from the repo root:
  `services/LabResultService/mvnw -f pom.xml -pl services/LabResultService -am -DskipTests -Dgit.sha=$GIT_SHA package`
  (The P0 brief's `./mvnw -pl services/LabResultService -am …` assumes a root wrapper that does not exist. Use the line above, or DevOps adds a root wrapper in their own change.)
- Artifact: `services/LabResultService/target/lab-result-service-0.0.1-SNAPSHOT.jar` (Boot executable jar).
- Builder image JDK 21, runtime image JRE 21 (e.g. `eclipse-temurin:21-jdk` / `eclipse-temurin:21-jre`, digests pinned by DevOps). Never the JDK 23/26 found on the developer laptop (`java.version=21` compiles with `--release 21`).
- `-DskipTests` in `docker build`: Testcontainers needs Docker, which is not available inside the image build. Tests run in dev/CI with `services/LabResultService/mvnw -f pom.xml -pl services/LabResultService -am verify`.

---

## 10. Test plan and Definition of Done per phase

Unit tests (`*Test`) need no Docker. `*IT` use `AbstractPostgresIT`: `@Testcontainers`, `@Container @ServiceConnection static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")` (the DevOps-pinned digest replaces the tag once known, §12 Q9), profile `test`.

| Phase | Test class | Asserts |
|---|---|---|
| P1a | `ArchitectureTest` | the three rules of §2.2 |
| P1a | `CoreApiTest` | record compact constructors reject nulls and copy lists (mutating the source list does not change the record); `PatientIds.parse`: `"3"`→3, `"abc"`/`""`/`"9223372036854775808"` → `InvalidRequestException(field="patientId")`; exception `code()` values; expected exceptions have an empty stack trace |
| P1 | `SchemaMigrationIT` | Flyway applies V1 on an empty PG 17; `ddl-auto=validate` passes; named indexes and constraints exist; CHECKs reject `tc_no='01234567890'`, blank names, unknown blood/sample type, `NaN`; `uq_lab_test_item_report_test` rejects duplicates; `DELETE FROM patient` with reports fails (RESTRICT) |
| P1 | `LabReadRepositoryIT` (P1 fixture, §6.8) | the per-case statement counts of §5.3 via `ReadSequence` (incl. test-only empty report and tied `created_at`); ordering `created_at DESC, id DESC`, items `id ASC`; the item SQL for 100 ids has 128 bind markers (padding); index use per §5.7 (leading column `report_id` / `patient_id`, not index names) |
| P1 | `StartupDbDownIT` | startup-failure case, kept separate from runtime outages: with the datasource URL at a closed port, the application context **fails to start** (Flyway/validate cannot run). There is no HTTP assertion; readiness is never reached |
| P1 | `LabResultServiceApplicationIT` | the context starts against Testcontainers PG (replaces `LabResultServiceApplicationTests`) |
| P2 | `SyntheticDatasetGeneratorTest` | generator output equals `seed/dataset-v1.json` (counts, version, seed, SHA-256); two runs are identical; counts 1000/10080/100800; patient 1 = 100×10, patient 2 = 0, patient 3 = 10×10 with latest report 101 / items 1001..1010; ids as in §6.2; `createdAt` strictly decreasing per patient, whole seconds; `isCritical` consistent with the catalogue; item codes unique per report |
| P2 | `TcNoTest` | every generated tcNo matches the regex, is unique, and **fails** an independently written official TC Kimlik validator (both check-digit rules) |
| P2 | `NameListsTest` / `TestCatalogueTest` | 40 + 40 distinct names; 20 entries; `criticalLow < normalMin`, `normalMax < criticalHigh` |
| P2 | `DatasetSeederIT` | seeds an empty DB; counts and manifest are right; `nextval` after seeding > max id for each table; second start skips; mismatching manifest → startup fails; rows without manifest → startup fails; row counts unchanged after every failure (nothing deleted); `full` mode passes on a good DB and fails after a row is altered in the disposable container; seed time is logged |
| P3 | `DefaultLabResultQueryServiceTest` (mocked repository) | assembly/grouping/order; null `latestReport`; empty history; `id <= 0` → `InvalidRequestException` with no repository call; not found; `CannotCreateTransactionException` / `CannotGetJdbcConnectionException` → `ServiceUnavailableException`; other `DataAccessException` propagates; `lab.service.calls` recorded with `method` and `outcome` tags |
| P3 | `LabResultQueryServiceIT` (seeded) | patient 1 history = 100 × 10; patient 3 summary = report 101 with 10 items; patient 2 empty; 1001 → not found |
| P4 | `PatientRestControllerTest` (`@WebMvcTest`, mocked service) | golden JSON S1 and S2 (`"isCritical"`, ISO dates, numeric ids); ProblemDetail for 400 (`abc`, overflow, `0`), 404, 503, 500 with sanitized `detail` and `application/problem+json` |
| P4 | `RestErrorIT` | Runtime outages **after a healthy startup** (P0-F2). Each case uses its own class-owned container (not the shared base container) plus `@DirtiesContext`, and first asserts `GET …/3/summary` → 200. (a) **Pool exhaustion:** context with `spring.datasource.hikari.maximum-pool-size=1`, `minimum-idle=1`; the test holds the only connection (`dataSource.getConnection()`) and calls the endpoint → 503 `UNAVAILABLE` after ≈ `connection-timeout` (2000 ms). (b) **DB down at transaction begin:** `HikariDataSource.getHikariPoolMXBean().softEvictConnections()`, then `postgres.stop()`, then the call → Hikari cannot acquire a connection → `CannotCreateTransactionException` inside `TransactionTemplate.execute` (`INFERRED 85%`, Hikari `SQLTransientConnectionException` wrapped by `JpaTransactionManager.doBegin`; this test is what proves it) → `ServiceUnavailableException` → 503 `UNAVAILABLE` with sanitized `detail`. Both cases assert `lab.errors{protocol=REST,code=UNAVAILABLE}` +1 and `lab.service.calls{outcome=UNAVAILABLE}` +1. Startup checks are **not** disabled; startup failure is covered separately by `StartupDbDownIT` and `DatasetSeederIT` |
| P4 | `CorsConfigTest` | empty property → no `Access-Control-Allow-Origin`; with an origin → preflight OK on `/api/**` and `/graphql`; profile `bench` **with** `APP_CORS_ALLOWED_ORIGINS=http://example.test` set as an environment-level property → no CORS headers and no `CorsConfig` bean (guards the env-override case) |
| P4 | Postman | `docs/backend-a/api/lab-result-service.postman_collection.json` + `local.postman_environment.json` run green against a local instance (manual run, result noted in the P4 report) |
| P5 | `LabGraphQlControllerTest` (`@GraphQlTest`, mocked service) | S1/S2/S2b responses; error classification + `extensions.code` for each row of §7.2; malformed ID `"abc"` → `BAD_REQUEST`/`INVALID_ARGUMENT` |
| P5 | `GraphQlStatementCountIT` | S1/S2/S2b statement counts == REST counts (3/3/3 for patients 3/1/1; 2 for patient 2) |
| P5 | `GraphQlErrorAccountingIT` (full context, `HttpGraphQlTester`; the interceptor is web-layer, so not in `@GraphQlTest`) | a syntax error (`{ patientSummary(`) → `errors[0].extensions.classification = InvalidSyntax`, `lab.errors{protocol=GRAPHQL,code=INVALID_ARGUMENT}` +1; one document with two failing root fields (`a: patientSummary(patientId:"1001") b: patientSummary(patientId:"abc")`) → two `errors` entries but the counter goes up by exactly **1** (code `INVALID_ARGUMENT`); a not-found request → +1 `PATIENT_NOT_FOUND` |
| P5 | `AdapterPresenceTest` | the production import contains classes in `..adapter.rest..` and `..adapter.graphql..` (§2.2) |
| P6 | `ObservabilityBenchIT` (`bench`) | `/actuator/prometheus` on the management port contains `http_server_requests_seconds`, `graphql_request_seconds`, `hikaricp_connections`, `lab_service_calls_seconds`, `lab_errors_total`, `jvm_memory_used_bytes`, `process_cpu_usage`; Tomcat `maxKeepAliveRequests == -1` |
| P6 | `ObservabilityNomonIT` (`bench,bench-nomon`) | `/actuator/prometheus` → 404; `lab.service.calls` is a no-op; the service still answers |
| P6 | `InfoEndpointIT` | `/actuator/info` contains every key of §11.2 |
| P7a | `ProtocolEquivalenceIT` (seeded, `RANDOM_PORT`) | for patients 1, 2, 3 and 500: REST JSON and GraphQL `data` normalized to one canonical tree (ids → long, ISO strings kept, doubles compared exactly) are equal; each request causes exactly one service call and the same statement count; writes `target/payload-sizes.json` (§11.3) |
| P7b | `ProtocolEquivalenceIT` (gRPC leg) | gRPC response normalized (`Timestamp` → ISO, absent defaults → `0.0`/`false`) equals the canonical tree; gRPC sizes added to the table |

| Phase | Definition of Done (refined from roadmap v2) |
|---|---|
| P0 | This contract and `arayuz-tablolari.md` exist; every external item has an owner (§12). |
| P1a | `core.api` + `ArchitectureTest` + `CoreApiTest` green with the P1a invocation of §2.2 (adapter rules tolerate empty packages); published to B (commit + PR #3 comment); B can compile against it. |
| P1 | `services/LabResultService/mvnw -f pom.xml -pl services/LabResultService -am verify` log shows failsafe `Tests run: N` with N > 0 and 0 failures; `application.properties` deleted. |
| P2 | Unit + `DatasetSeederIT` green; checksum committed in `seed/dataset-v1.json`; seed time logged. |
| P3 | Service unit + IT green. |
| M1 | P1–P3 + REST S1/S2 on `bench,seed`: the image builds with the §9.3 recipe and `GET :8080/api/v1/patients/3/summary` / `…/1/lab-history` answer 200; DevOps and QA are notified. |
| P4 | WebMvc + error ITs green; Postman collection runs locally; CORS property works; springdoc kept or dropped with the reason noted. |
| P5 | GraphQL tests green; statement counts equal REST; sample operations in Postman. |
| P6 | Required meters present in `bench`, absent in `bench-nomon`; `/actuator/info` complete; keep-alive settings verified; error/abort policy + `lab.errors` implemented. |
| P7a | Canonical trees equal for REST and GraphQL; `payload-sizes.json` emitted. |
| P7b | All three protocols equal (needs B's adapter). |
| P8 | With DevOps: compose check (readiness gating, ports 8080/8081/9091); one `full` verify run; EXPLAIN (ANALYZE, BUFFERS) on PG 17 committed; image digests, `payload-sizes.json` and the licence inventory (`docs/backend-a/licences.md`) committed. Staged shakedown with QA: 50 VU → 200 VU × 2 min → 500 VU + spike: 0 protocol errors at 200 VU; saturation behaviour at 500/spike documented; run book reviewed. **Manual client-abort check (R8, P0-F4):** (1) HTTP, by A + QA: 20 × `curl --max-time 0.05 http://…:8080/api/v1/patients/1/lab-history`, and the same with a `POST /graphql` S2 body, against an otherwise idle service. (2) gRPC, by **B**: 20 × `grpcurl -plaintext -max-time 0.05 … LabService/GetPatientLabHistory` (`patient_id` 1). Expected evidence, recorded in the P8 report: every detected abort produces one WARN line with no stack trace (§3.4); `lab_errors_total{code="CLIENT_ABORTED"}` grows by at most 20 per protocol (an abort that lands after the response was fully written is not detectable, so fewer is acceptable); no ERROR/stack-trace lines; no 5xx; Hikari `active` returns to 0; the next normal request returns 200. No automated chaos test. |

---

## 11. Metrics and metadata

### 11.1 Required meters (present in `bench`, absent in `bench-nomon`)

| Meter (Micrometer name → Prometheus) | Source | Purpose |
|---|---|---|
| `http.server.requests` → `http_server_requests_seconds_*` (tags `uri`, `status`, `outcome`, `method`) | Spring MVC observation | REST latency/errors; `uri="/graphql"` covers GraphQL transport |
| `graphql.request` → `graphql_request_seconds_*`; `graphql.datafetcher` | Spring GraphQL observation | GraphQL errors live inside HTTP 200, so this is the only server-side view of them |
| `grpc.server.*` | spring-grpc observation (B's adapter) | gRPC status codes |
| `lab.service.calls` → `lab_service_calls_seconds_*` (tags `method`, `outcome`) | explicit Timer (§5.6) | protocol-neutral service time |
| `lab.errors` → `lab_errors_total` (tags `protocol`, `code`) | `LabErrorRecorder` | unified error counts incl. client aborts |
| `hikaricp.connections.*` (`active`, `idle`, `pending`, `timeout`, `acquire`) | Hikari | pool saturation at 500 VU / spike |
| `jvm.*`, `process.cpu.usage`, `system.cpu.usage`, `jvm.gc.pause` | Boot defaults | complements cAdvisor |

Common tag `application="lab-result-service"`. No percentile histograms. Scrape `http://lab-result-service:8081/actuator/prometheus` every 15 s (DevOps).

### 11.2 `/actuator/info` contents (port 8081)

| Key | Source |
|---|---|
| `build.{group,artifact,name,version,time}`, `build.git.sha` | `build-info` goal (§9.2) |
| `java.*`, `os.*`, `process.*` | `management.info.{java,os,process}.enabled=true` |
| `lab.dataset.{name,generatorVersion,seed,patientCount,reportCount,itemCount,checksumSha256,seededAt,verifyMode,verifiedAt}` | `LabInfoContributor` from `dataset_meta` + verifier result |
| `lab.runtime.activeProfiles` | `Environment` |
| `lab.runtime.tomcat.{threadsMax,maxKeepAliveRequests,keepAliveTimeout,acceptCount,maxConnections,maxQueueCapacity}` | bound `server.tomcat.*` |
| `lab.runtime.{http2Enabled,compressionEnabled,virtualThreads}` | bound properties |
| `lab.runtime.hikari.{maximumPoolSize,minimumIdle,connectionTimeoutMs,autoCommit}` | `HikariDataSource` |
| `lab.runtime.grpc.{port,reflectionEnabled,executorThreads}` | properties; `executorThreads` from B's `GrpcServerExecutorProvider` if present, else `"default (unbounded cached pool)"` |
| `lab.runtime.jvm.{inputArguments,gcNames,availableProcessors,maxHeapBytes}` | `RuntimeMXBean`, `GarbageCollectorMXBean`s, `Runtime` |

QA saves `GET :8081/actuator/info` next to every run's raw output (Talimat 1, 11).

### 11.3 `payload-sizes.json` (R2)

Written by `ProtocolEquivalenceIT` to `services/LabResultService/target/payload-sizes.json`; the frozen copy is committed at P8 as `docs/backend-a/payload-sizes.json`.

```json
{
  "schemaVersion": 1,
  "generatedBy": "ProtocolEquivalenceIT",
  "gitSha": "…",
  "dataset": { "name": "ym491", "generatorVersion": 1, "seed": 4912026, "checksumSha256": "…" },
  "entries": [
    { "scenario": "S1",  "protocol": "REST",    "operation": "GET /api/v1/patients/3/summary",     "patientId": 3,
      "requestBodyBytes": 0,   "responseBodyBytes": 0, "measure": "utf8-http-body" },
    { "scenario": "S1",  "protocol": "GRAPHQL", "operation": "POST /graphql S1",                   "patientId": 3,
      "requestBodyBytes": 0,   "responseBodyBytes": 0, "measure": "utf8-http-body" },
    { "scenario": "S1",  "protocol": "GRPC",    "operation": "LabService/GetPatientSummary",       "patientId": 3,
      "requestBodyBytes": 0,   "responseBodyBytes": 0, "measure": "protobuf-serialized-size" },
    { "scenario": "S2",  "protocol": "REST",    "operation": "GET /api/v1/patients/1/lab-history", "patientId": 1, "…": "…" },
    { "scenario": "S2",  "protocol": "GRAPHQL", "operation": "POST /graphql S2",                   "patientId": 1, "…": "…" },
    { "scenario": "S2",  "protocol": "GRPC",    "operation": "LabService/GetPatientLabHistory",    "patientId": 1, "…": "…" },
    { "scenario": "S2b", "protocol": "GRAPHQL", "operation": "POST /graphql S2b",                  "patientId": 1, "…": "…" }
  ]
}
```

(`0` and `"…"` are placeholders; the test writes real values.) Definitions: `utf8-http-body` = bytes of the HTTP body as sent (UTF-8; the GraphQL body includes the `{"data":…}` envelope and the request JSON with query text and variables); `protobuf-serialized-size` = `message.getSerializedSize()` (no gRPC 5-byte frame prefix, no HTTP/2 framing). Headers and transport framing are excluded everywhere. Network traffic is a separate quantity (cAdvisor, DevOps/QA). gRPC rows are added in P7b.

---

## 12. Open questions (owned)

| # | Question | Owner | Blocks | Default until answered |
|---|---|---|---|---|
| Q1 | Browser → gRPC route for İG-5 (BFF in PatientService vs same-origin proxy vs gRPC-Web); where the UI's "ms"/"KB" for the gRPC button come from; whether Frontend needs `Timing-Allow-Origin`/exposed headers | B + Frontend (+ DevOps for a proxy) | Frontend's gRPC button; final CORS value (P4) | REST/GraphQL CORS via `app.cors.allowed-origins`, empty |
| Q2 | Who owns the "one-click load test" trigger (k6 runner API) | B + QA + Frontend | İG-5 completeness (team milestone) | none; outside A |
| Q3 | `GrpcServerExecutorProvider` (fixed 200 threads): who writes it, lifecycle/shutdown, queue policy | B (PR #3, RFC 3.1) | P6 `/actuator/info` `grpc.executorThreads`; P7b; measured runs | B writes it in `adapter.grpc`/its config; A reports "default" until then |
| Q4 | Proto shape acceptance (RFC 1.1 reserved vs V2, 1.2 Timestamp, 1.3 birthDate string, 1.4 protobuf versions) | B (PR #3) | P7b, gRPC payload rows | RFC 0001 proposal |
| Q5 | Who authors the compose `postgres` service block (image@digest, flags, healthcheck, volume) | DevOps (A offers to draft it) | P8 | A's proposal in the interface tables; DevOps owns the file |
| Q6 | Final heap size, CPU/memory limits for the service, PostgreSQL, k6, Prometheus, cAdvisor, Grafana; cpuset separation | DevOps | measured runs, P8 shakedown | proposal in the interface tables |
| Q7 | D13 overhead method: what the off-arm excludes (cAdvisor/Prometheus host load), number of pairs, uncertainty bound | QA | the ≤3 % claim | `bench` vs `bench,bench-nomon`, ≥5 randomized pairs |
| Q8 | U3 confirmation: k6 → LabResultService directly for all three protocols; BFF timing reported separately, if at all | B + QA | measured runs | direct (U3) |
| Q9 | Exact PostgreSQL 17 minor and image digest | DevOps | P8 EXPLAIN artefacts; test container tag | `postgres:17-alpine` |
| Q10 | CI (`services/LabResultService/mvnw -f pom.xml -pl services/LabResultService -am verify` with Docker on GitHub Actions) | DevOps | nothing on A's path | local `verify` |

---

## Appendix A — Superseded values (base design → this contract)

| Topic | Base design (`design-r1-claude.md`) | Now | Authority |
|---|---|---|---|
| gRPC port | 9090 (§8, §9, compose ports) | **9091** | D10, R11 |
| Scenario 1 target | patient 2 | **patient 3** | D5, R11 |
| Patient 2 | S1 target, 12 × 8 | **existing, empty history** | D5, R11 |
| Volume | 2,000 patients, 1–8 reports × 4–12 items, ≈9,000 reports / ≈72,000 items | **1,000 patients, 10,080 reports, 100,800 items** (3..1000 = 10 × 10) | D5, R11 |
| History statements | 2 (patient + joined rows) | **3** (patient, reports, items `IN`) | D1 (E1 evidence), R11 |
| Summary statements | 3 (latest id + joined rows) | 3 (patient, latest report, items of report) | D1 |
| Row records | `HistoryRow` (joined), `PatientRow` | `ReportRow` + `ItemRow`; `PatientView` built directly | D1, review clash §6 |
| `lab_test_item` FK | `ON DELETE CASCADE` | **`ON DELETE RESTRICT`** | D8, R11 |
| `LabReport.items` association | `@OneToMany` | removed | D1 (no entity navigation) |
| GraphQL dates | `scalar DateTime` + `@SchemaMapping birthDate` | **`String!`**, no scalar, no `@SchemaMapping` | D3, R10, R11 |
| Hikari `connection-timeout` | 5000 ms | **2000 ms** | D12, R11 |
| RNG | `SplittableRandom(491_2026L)` | **`java.util.Random(4912026L)`** | D6, R11 |
| Seed switch | `lab.seed.enabled` / `LAB_SEED_ENABLED` | **`app.seed.enabled` via `seed` profile** (`SPRING_PROFILES_ACTIVE=bench,seed`) | D6, R11 |
| Seed value | property `lab.seed.value` | **constant** in the generator | DECISION (a different seed is a different dataset version) |
| Startup verification | full checksum re-verify on every start | **`cheap`** on every start, **`full`** one-shot gate (`app.dataset.verify-mode`) | R6 |
| PostgreSQL | `postgres:18-alpine` | **PostgreSQL 17** (digest pinned by DevOps) | D12, R11 |
| Generator details | ~20 tests, ~40×40 names, `BASE − offset` | exact catalogue, lists, draw order, id scheme, digest format (§6) | DECISION (needed for a reproducible golden checksum) |
| Service timer | `@Observed` | explicit `Timer` | R10, V3 |
| Transaction | `@Transactional(readOnly = true)` | `TransactionTemplate(readOnly)` | DECISION §5.5 (translate tx-begin failures to `ServiceUnavailableException`) |
| Exceptions | `PatientNotFoundException`, `InvalidRequestException` | + `ServiceUnavailableException`, `ErrorCode`, `PatientIds`, `LabErrorRecorder`, `Protocol` | R5/R8, DECISION §3.3–3.4 |
| DB-unavailable mapping | `CannotGetJdbcConnectionException` / Hikari timeout / any `TransientDataAccessException` | `CannotCreateTransactionException`, `DataAccessResourceFailureException`, `TransientDataAccessResourceException` only | R8, X-F8 |
| Error logging | 5xx at ERROR with stack | per-code policy (§3.4) + `lab.errors` counter | R5 (F15) |
| Tomcat keep-alive | not set (100 requests/connection) | `bench`: `-1`, `keep-alive-timeout 60s` | R1 |
| `bench-nomon` | observations off + export off + gRPC observation off | + `management.metrics.enable.all=false` | R1/F14 |
| Actuator exposure | `health,info,prometheus,metrics` | `health,info,prometheus` | DECISION (smaller surface; `metrics` unused) |
| GraphQL introspection | off in bench | off in default and bench, on in `docs` and test | R4 |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | G1 + explicit heap + `ActiveProcessorCount` (§8.2) | R5 |
| ArchUnit | "pin 1.4.x" | **1.5.1** | `REPRODUCED` (Central) |
| springdoc | "Boot-4 version unverified" | **3.1.1** resolves (Apache-2.0); optional, `docs` profile only | R4, `REPRODUCED` |
| Failsafe | not bound | declared in module `build/plugins` | V1 |
| `application.properties` | kept | deleted in P1 | R10 |
| Testcontainers PG | unspecified | `postgres:17-alpine` | D12 |
| Build command | `mvnw -f pom.xml -pl … -am -DskipTests compile` (design check) | §9.3 recipe (module wrapper; there is no root wrapper) | F19, `DIRECT SOURCE` |
| Roadmap | P3 publishes `core.api` | **P1a** publishes `core.api` | Roadmap v2 |
| Sentinel result caps (101 reports / 1,001 items, clash idea) | — | **none**; fixture verification guarantees sizes | D1 |
| EXPLAIN check | exact index names in plan | "item query uses an index whose leading column is `report_id`" (index names only checked for existence); full EXPLAIN (ANALYZE, BUFFERS) at P8 | R9, P0-F5 |

## Appendix B — Conflicts resolved in P0

1. **Root `./mvnw`** (P0 brief) vs repository (no root wrapper): the contract uses the module wrapper with `-f pom.xml` (§9.3) and flags it to DevOps.
2. **R6 "verify-dataset run / readiness step"** vs review proposal `app.seed.verify-checksum=true`: one property `app.dataset.verify-mode=cheap|full|off`. Full mode runs as a startup step before readiness.
3. **`@Transactional` (base) vs the R8 requirement to map tx-begin failures** for every protocol, including B's gRPC adapter: `TransactionTemplate` inside the service (§5.5).
4. **GraphQL CORS**: Boot's `spring.graphql.cors.*` exists, but R3 asks for one property covering both paths, so one `WebMvcConfigurer` is used and `spring.graphql.cors.*` stays unset.
5. **Flyway version**: Central's latest is 13.11.1, but the BOM manages 12.4.0. The BOM wins (no override); licence verified for 12.4.0.
