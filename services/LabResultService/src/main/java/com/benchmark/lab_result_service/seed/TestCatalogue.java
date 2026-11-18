package com.benchmark.lab_result_service.seed;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The 20-entry synthetic test catalogue of generator version 1 (contract §6.4), loaded from
 * {@code seed/test-catalogue.csv}. The list index is the catalogue index the generator draws (§6.3), so the file
 * order is part of the dataset definition. Values are synthetic and illustrative, not clinical guidance.
 */
public final class TestCatalogue {

    public static final String RESOURCE = "/seed/test-catalogue.csv";

    /** Number of entries the generator's partial Fisher–Yates shuffle draws from (§6.3). */
    public static final int SIZE = 20;

    /**
     * One catalogue entry. {@code criticalLow == 0} means "no low-critical draw" (§6.3 {@code drawValue}).
     */
    public record Entry(String code, String name, String unit,
                        double normalMin, double normalMax, double criticalLow, double criticalHigh) {
        public Entry {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(unit, "unit");
        }
    }

    private static final List<Entry> ENTRIES = load();

    private TestCatalogue() {}

    /** The catalogue in index order (immutable). */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    private static List<Entry> load() {
        List<Entry> entries = new ArrayList<>();
        for (String line : SeedResources.dataLines(RESOURCE)) {
            String[] f = line.split("\\|", -1);
            if (f.length != 7) {
                throw new IllegalStateException("Malformed catalogue line (expected 7 fields): " + line);
            }
            entries.add(new Entry(f[0], f[1], f[2], Double.parseDouble(f[3]), Double.parseDouble(f[4]),
                    Double.parseDouble(f[5]), Double.parseDouble(f[6])));
        }
        if (entries.size() != SIZE) {
            throw new IllegalStateException("Test catalogue must have " + SIZE + " entries, has " + entries.size());
        }
        return List.copyOf(entries);
    }
}
