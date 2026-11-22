package com.benchmark.lab_result_service.seed;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Deterministic synthetic dataset, generator version 1 (contract §6.1–§6.3). Pure Java, no Spring, single-threaded.
 *
 * <p>The RNG consumption order below is part of the contract: the committed checksum in {@code seed/dataset-v1.json}
 * depends on it. Any change to it, to the catalogues or to the constants is a new dataset version.
 */
public final class SyntheticDatasetGenerator {

    public static final int GENERATOR_VERSION = 1;
    public static final long SEED = 4912026L;
    public static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    public static final LocalDate BIRTH_EPOCH = LocalDate.of(1940, 1, 1);
    public static final String DATASET_NAME = "ym491";

    public static final int PATIENT_COUNT = 1_000;
    public static final int REPORT_COUNT = 10_080;
    public static final int ITEM_COUNT = 100_800;
    public static final int ITEMS_PER_REPORT = 10;

    /** 1940-01-01 + [0, 23 742) days = 1940-01-01 .. 2004-12-31. */
    static final int BIRTH_DAY_RANGE = 23_742;
    static final int SECONDS_PER_DAY = 86_400;
    static final Duration REPORT_SPACING = Duration.ofDays(3);

    public static final List<String> BLOOD_TYPES = List.of("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-");
    public static final List<String> SAMPLE_TYPES = List.of("BLOOD", "SERUM", "PLASMA", "URINE", "SALIVA");

    public record PatientRecord(long id, String tcNo, String fullName, LocalDate birthDate, String bloodType) {}

    public record ReportRecord(long id, long patientId, String doctorName, String sampleType, Instant createdAt) {}

    public record ItemRecord(long id, long reportId, String testCode, String testName, double resultValue,
                             String unit, boolean critical) {}

    /** The generated rows (each list in ascending id order) and their canonical SHA-256 (§6.5). */
    public record Dataset(List<PatientRecord> patients, List<ReportRecord> reports, List<ItemRecord> items,
                          String checksumSha256) {}

    private SyntheticDatasetGenerator() {}

    /** Number of reports of patient {@code p} (§6.1): 100 for patient 1, 0 for patient 2, 10 otherwise. */
    public static int reportsOf(long p) {
        return p == 1 ? 100 : p == 2 ? 0 : 10;
    }

    public static Dataset generate() {
        List<String> firstNames = NameLists.FIRST_NAMES;
        List<String> lastNames = NameLists.LAST_NAMES;
        List<TestCatalogue.Entry> catalogue = TestCatalogue.entries();

        Random rnd = new Random(SEED);
        List<PatientRecord> patients = new ArrayList<>(PATIENT_COUNT);
        List<ReportRecord> reports = new ArrayList<>(REPORT_COUNT);
        List<ItemRecord> items = new ArrayList<>(ITEM_COUNT);
        Set<String> usedTcNos = new HashSet<>();
        long nextReportId = 1;
        long nextItemId = 1;

        for (long p = 1; p <= PATIENT_COUNT; p++) {
            // patient fields
            int[] digits = new int[10];
            String tcNo;
            do {                                                  // redraw the 10 digits only
                digits[0] = 1 + rnd.nextInt(9);
                for (int i = 1; i < 10; i++) {
                    digits[i] = rnd.nextInt(10);
                }
                tcNo = TcNo.of(digits);
            } while (!usedTcNos.add(tcNo));
            String first = firstNames.get(rnd.nextInt(NameLists.SIZE));
            String last = lastNames.get(rnd.nextInt(NameLists.SIZE));
            LocalDate birthDate = BIRTH_EPOCH.plusDays(rnd.nextInt(BIRTH_DAY_RANGE));
            String bloodType = BLOOD_TYPES.get(rnd.nextInt(BLOOD_TYPES.size()));
            patients.add(new PatientRecord(p, tcNo, first + " " + last, birthDate, bloodType));

            int n = reportsOf(p);
            for (int k = 0; k < n; k++) {                        // k = 0 is the latest report
                String doctor = "Dr. " + firstNames.get(rnd.nextInt(NameLists.SIZE)) + " "
                        + lastNames.get(rnd.nextInt(NameLists.SIZE));
                String sampleType = SAMPLE_TYPES.get(rnd.nextInt(SAMPLE_TYPES.size()));
                Instant createdAt = BASE.minus(REPORT_SPACING.multipliedBy(k))
                        .minusSeconds(rnd.nextInt(SECONDS_PER_DAY));
                long reportId = nextReportId++;
                reports.add(new ReportRecord(reportId, p, doctor, sampleType, createdAt));

                int[] idx = new int[TestCatalogue.SIZE];           // partial Fisher–Yates over [0..19]
                for (int i = 0; i < idx.length; i++) {
                    idx[i] = i;
                }
                for (int j = 0; j < ITEMS_PER_REPORT; j++) {
                    int swapWith = j + rnd.nextInt(TestCatalogue.SIZE - j);
                    int tmp = idx[j];
                    idx[j] = idx[swapWith];
                    idx[swapWith] = tmp;
                }
                int[] tests = Arrays.copyOf(idx, ITEMS_PER_REPORT);
                Arrays.sort(tests);                                // item order = catalogue order

                for (int t : tests) {
                    TestCatalogue.Entry c = catalogue.get(t);
                    double resultValue = drawValue(rnd, c);
                    boolean critical = resultValue < c.criticalLow() || resultValue > c.criticalHigh();
                    items.add(new ItemRecord(nextItemId++, reportId, c.code(), c.name(), resultValue, c.unit(),
                            critical));
                }
            }
        }

        if (patients.size() != PATIENT_COUNT || reports.size() != REPORT_COUNT || items.size() != ITEM_COUNT) {
            throw new IllegalStateException("generator volumes drifted: " + patients.size() + "/" + reports.size()
                    + "/" + items.size());
        }
        return new Dataset(List.copyOf(patients), List.copyOf(reports), List.copyOf(items),
                digest(patients, reports, items));
    }

    /** §6.3 {@code drawValue}: ~5 % critical draws, otherwise uniform in the normal range; one decimal. */
    static double drawValue(Random rnd, TestCatalogue.Entry c) {
        double u = rnd.nextDouble();
        double x;
        if (u < 0.05) {
            boolean low = (c.criticalLow() > 0) && rnd.nextBoolean();   // short-circuit: no draw when cLow == 0
            double f = rnd.nextDouble();
            x = low ? c.criticalLow() * (0.5 + 0.4 * f) : c.criticalHigh() * (1.1 + 0.5 * f);
        } else {
            x = c.normalMin() + rnd.nextDouble() * (c.normalMax() - c.normalMin());
        }
        return Math.round(x * 10) / 10.0;
    }

    private static String digest(List<PatientRecord> patients, List<ReportRecord> reports, List<ItemRecord> items) {
        CanonicalDigest digest = new CanonicalDigest();
        for (PatientRecord r : patients) {
            digest.patient(r.id(), r.tcNo(), r.fullName(), r.birthDate(), r.bloodType());
        }
        for (ReportRecord r : reports) {
            digest.report(r.id(), r.patientId(), r.doctorName(), r.sampleType(), r.createdAt());
        }
        for (ItemRecord r : items) {
            digest.item(r.id(), r.reportId(), r.testCode(), r.testName(), r.resultValue(), r.unit(), r.critical());
        }
        return digest.hex();
    }
}
